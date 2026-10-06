// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.application;

import com.aws.aqp.api.AqpExceptionMappers;
import com.aws.aqp.api.PingResource;
import com.aws.aqp.api.QueryRESTController;
import com.aws.aqp.api.RequestIdFilter;
import com.aws.aqp.auth.AqpAuthenticator;
import com.aws.aqp.auth.AqpAuthorizer;
import com.aws.aqp.auth.AqpUser;
import com.aws.aqp.core.AggregationEngine;
import com.aws.aqp.core.Aggregator;
import com.aws.aqp.core.DuckDbEngine;
import com.aws.aqp.core.EngineType;
import com.aws.aqp.core.PartiQLEngine;
import com.aws.aqp.connectors.CassandraExtractor;
import com.aws.aqp.connectors.ConnectionDDBFactory;
import com.aws.aqp.connectors.ConnectionKeyspacesFactory;
import com.aws.aqp.connectors.Extractor;
import com.aws.aqp.connectors.DatabaseType;
import com.aws.aqp.connectors.DynamodbExtractor;
import com.aws.aqp.health.ConnectionHealthCheck;
import com.aws.aqp.health.DynamoDbHealthCheck;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheck;
import com.datastax.oss.driver.api.core.CqlSession;
import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.auth.basic.BasicCredentialAuthFilter;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.lifecycle.AutoCloseableManager;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public class App extends Application<AppConfiguration> {

    private static final String KEYSPACES_HEALTH_CHECK = "keyspaces-tcp-dependency";
    private static final String DYNAMODB_HEALTH_CHECK = "ddb-http-dependency";
    private static final String REALM = "aggregation-query-proxy";
    private static final Logger LOGGER = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        new App().run(args);
    }

    @Override
    public String getName() {
        return "simple-query-aggregator-proxy";
    }

    /**
     * Enables {@code ${ENV_VAR}} substitution in the YAML config, so secrets can be injected at
     * runtime instead of being written into the file and baked into the image. Strict: a
     * referenced variable that is not set (and has no {@code ${VAR:-default}}) fails startup,
     * rather than leaving the literal placeholder in place as a guessable secret.
     */
    @Override
    public void initialize(Bootstrap<AppConfiguration> bootstrap) {
        bootstrap.setConfigurationSourceProvider(new SubstitutingSourceProvider(
                bootstrap.getConfigurationSourceProvider(), new EnvironmentVariableSubstitutor(true)));
    }

    @Override
    public void run(AppConfiguration appConfiguration, Environment environment) {
        if (appConfiguration.getClientSecret() != null) {
            throw new IllegalStateException("'clientSecret' is no longer supported. Configure one entry "
                    + "per client under 'users', each with its own 'secret' and 'roles'. See the README.");
        }

        Extractor extractor = buildExtractor(appConfiguration, environment);

        environment.jersey().register(new AuthDynamicFeature(new BasicCredentialAuthFilter.Builder<AqpUser>()
                .setAuthenticator(new AqpAuthenticator(appConfiguration.getUsers()))
                .setAuthorizer(new AqpAuthorizer())
                .setRealm(REALM)
                .buildAuthFilter()));
        environment.jersey().register(new AuthValueFactoryProvider.Binder<>(AqpUser.class));
        environment.jersey().register(RolesAllowedDynamicFeature.class);

        environment.jersey().register(new AqpExceptionMappers.InvalidQuery());
        environment.jersey().register(new AqpExceptionMappers.ResultTooLarge());
        environment.jersey().register(new AqpExceptionMappers.QueryTimeout());
        environment.jersey().register(new AqpExceptionMappers.Aggregation());
        environment.jersey().register(new AqpExceptionMappers.DataStore());
        environment.jersey().register(new AqpExceptionMappers.Cql());
        environment.jersey().register(new AqpExceptionMappers.SdkClient());

        environment.jersey().register(new RequestIdFilter());
        AggregationEngine engine = buildEngine(appConfiguration);
        probeEngine(engine, appConfiguration.getAggregationEngine());
        // Which engine serves this process, visible on the admin port next to the query metrics.
        environment.metrics().register(MetricRegistry.name(Aggregator.class, "engine"),
                (Gauge<String>) () -> appConfiguration.getAggregationEngine().name());
        environment.jersey().register(new QueryRESTController(extractor, engine, environment.metrics()));
        // Unauthenticated liveness probe for load-balancer target health checks (ECS/Fargate).
        environment.jersey().register(new PingResource());
    }

    /** The configured aggregation engine. Fails startup on an unknown name (see EngineType). */
    static AggregationEngine buildEngine(AppConfiguration appConfiguration) {
        EngineType type = appConfiguration.getAggregationEngine();
        switch (type) {
            case PARTIQL:
                return new PartiQLEngine();
            case DUCKDB:
                return new DuckDbEngine(appConfiguration.getMaxResultBytes());
            default:
                throw new IllegalStateException("Unsupported aggregation engine: " + type);
        }
    }

    /**
     * Runs one trivial query through the engine and refuses to start if it fails, so a broken
     * engine (for example a native library that cannot load on this platform) is a clear
     * startup error rather than a 500 on the first request.
     */
    static void probeEngine(AggregationEngine engine, EngineType type) {
        try {
            engine.query("SELECT COUNT(*) AS c FROM probe", "{\"probe\":[{\"x\":1}]}");
            LOGGER.info("Aggregation engine: {}", type);
        } catch (RuntimeException e) {
            throw new IllegalStateException(String.format(
                    "Aggregation engine %s failed its startup probe, refusing to start", type), e);
        }
    }

    /**
     * Builds the one client for the configured data store, shares it between the health check and
     * the extractor, and closes it on shutdown. Fails startup with a message if the store is not
     * reachable.
     * <p>
     * The health check and the extractor previously each built their own client — two connection
     * pools — and the health check's was never closed.
     */
    private Extractor buildExtractor(AppConfiguration appConfiguration, Environment environment) {
        DatabaseType databaseType = appConfiguration.getDatabaseType();

        switch (databaseType) {
            case KEYSPACES:
                CqlSession session = new ConnectionKeyspacesFactory(appConfiguration).buildSession();
                environment.lifecycle().manage(new AutoCloseableManager(session));
                registerAndRequireHealthy(environment, KEYSPACES_HEALTH_CHECK, new ConnectionHealthCheck(session));
                return new CassandraExtractor(appConfiguration, session);

            case DYNAMODB:
                DynamoDbClient client = new ConnectionDDBFactory(appConfiguration).build();
                environment.lifecycle().manage(new AutoCloseableManager(client));
                registerAndRequireHealthy(environment, DYNAMODB_HEALTH_CHECK,
                        new DynamoDbHealthCheck(client, appConfiguration.getDynamoHealthCheckTable()));
                return new DynamodbExtractor(appConfiguration, client);

            default:
                throw new IllegalStateException("Unsupported data store: " + databaseType);
        }
    }

    /**
     * Registers a health check and refuses to start if the dependency is not reachable.
     * <p>
     * The name is passed once and used for both the registration and the lookup. They were
     * previously two different string literals for DynamoDB ("ddb-http-dependency" registered,
     * "dynamodb-endpoint" looked up), so the lookup threw NoSuchElementException and the
     * application could never start in DynamoDB mode at all.
     */
    private void registerAndRequireHealthy(Environment environment, String name, HealthCheck healthCheck) {
        environment.healthChecks().register(name, healthCheck);
        HealthCheck.Result result = environment.healthChecks().runHealthCheck(name);
        if (!result.isHealthy()) {
            throw new IllegalStateException(String.format(
                    "Dependency health check '%s' failed, refusing to start: %s",
                    name, result.getMessage()), result.getError());
        }
        LOGGER.info("Dependency health check '{}' passed", name);
    }
}
