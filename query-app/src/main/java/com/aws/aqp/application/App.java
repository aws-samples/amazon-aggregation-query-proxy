// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.application;

import com.aws.aqp.api.QueryRESTController;
import com.aws.aqp.auth.AqpAuthenticator;
import com.aws.aqp.auth.AqpAuthorizer;
import com.aws.aqp.auth.AqpUser;
import com.aws.aqp.connectors.CassandraExtractor;
import com.aws.aqp.connectors.Extractor;
import com.aws.aqp.connectors.DatabaseType;
import com.aws.aqp.connectors.DynamodbExtractor;
import com.aws.aqp.health.ConnectionHealthCheck;
import io.dropwizard.Application;
import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.auth.basic.BasicCredentialAuthFilter;
import io.dropwizard.health.http.HttpHealthCheck;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;

public class App extends Application<AppConfiguration> {

    public static void main(String[] args) throws Exception {
        new App().run(args);
    }

    @Override
    public String getName() {
        return "simple-query-aggregator-proxy";
    }

    @Override
    public void initialize(Bootstrap<AppConfiguration> bootstrap) {
        // nothing to do yet
    }

    @Override
    public void run(AppConfiguration appConfiguration, Environment environment) {
        Extractor extractor = null;

        if (appConfiguration.getServiceName().equals(DatabaseType.KEYSPACES.toString())) {
            environment.healthChecks().register("keyspaces-tcp-dependency", new ConnectionHealthCheck(appConfiguration));
            extractor = new CassandraExtractor(appConfiguration);
        }
        if (appConfiguration.getServiceName().equals(DatabaseType.DYNAMODB.toString())) {
            String ddbEndpoint = System.getProperty("dynamodb-endpoint",String.format("https://dynamodb.%s.amazonaws.com", appConfiguration.getAwsRegion().toLowerCase()));
            environment.healthChecks().register("ddb-http-dependency",
                    new HttpHealthCheck(ddbEndpoint));
            extractor = new DynamodbExtractor(appConfiguration);
        }

        environment.jersey().register(new AuthDynamicFeature(new BasicCredentialAuthFilter.Builder<AqpUser>()
                .setAuthenticator(new AqpAuthenticator(appConfiguration))
                .setAuthorizer(new AqpAuthorizer())
                .setRealm("Xnimvw3rrszlaEXAMPLE=")
                .buildAuthFilter()));
        environment.jersey().register(new AuthValueFactoryProvider.Binder<>(AqpUser.class));
        environment.jersey().register(RolesAllowedDynamicFeature.class);

        environment.jersey().register(new QueryRESTController(extractor));
        environment.lifecycle().manage(extractor);

    }
}
