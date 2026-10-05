// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.application;

import io.dropwizard.configuration.ConfigurationValidationException;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.FileConfigurationSourceProvider;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.configuration.UndefinedEnvironmentVariableException;
import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.jersey.validation.Validators;
import org.apache.commons.text.StringSubstitutor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Loads conf/keyspaces-aggregation-query-proxy.yaml the way the application does. */
class ShippedConfigurationTest {

    private static final String CONFIG = "conf/keyspaces-aggregation-query-proxy.yaml";

    private static AppConfiguration load(StringSubstitutor substitutor) throws Exception {
        return new YamlConfigurationFactory<>(AppConfiguration.class, Validators.newValidator(),
                Jackson.newObjectMapper(), "dw")
                .build(new SubstitutingSourceProvider(new FileConfigurationSourceProvider(), substitutor), CONFIG);
    }

    /** Stands in for the process environment, which a test cannot set. */
    private static StringSubstitutor environment(Map<String, String> variables) {
        StringSubstitutor substitutor = new StringSubstitutor(variables);
        substitutor.setEnableUndefinedVariableException(true);
        substitutor.setValueDelimiter(":-");
        return substitutor;
    }

    @Test
    void loadsWithSecretsFromTheEnvironment() throws Exception {
        AppConfiguration config = load(environment(Map.of(
                "AQP_REPORTING_APP_SECRET", "a-sufficiently-long-secret")));

        assertEquals("a-sufficiently-long-secret", config.getUsers().get("reporting-app").getSecret());
        assertNull(config.getClientSecret(), "legacy shared secret must not be configured");
        assertEquals("KEYSPACES", config.getServiceName());
    }

    /** An unset secret variable must fail startup, not become the literal "${...}" secret. */
    @Test
    void refusesToStartWhenASecretIsNotProvided() {
        assertTrue(System.getenv("AQP_REPORTING_APP_SECRET") == null,
                "test assumes AQP_REPORTING_APP_SECRET is not set in the build environment");
        assertThrows(UndefinedEnvironmentVariableException.class,
                () -> load(new EnvironmentVariableSubstitutor(true)));
    }

    @Test
    void rejectsShortSecrets() {
        ConfigurationValidationException e = assertThrows(ConfigurationValidationException.class,
                () -> load(environment(Map.of("AQP_REPORTING_APP_SECRET", "short"))));
        assertTrue(e.getMessage().contains("secret"), e::getMessage);
    }

    @Test
    void bindsTheAdminConnectorToLoopback() throws Exception {
        String yaml = new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(CONFIG)));
        assertTrue(yaml.contains("bindHost: 127.0.0.1"), "admin connector must not listen on all interfaces");
    }
}
