// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.connectors;

import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.config.DriverExecutionProfile;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the security-relevant settings of the shipped Keyspaces driver configuration. */
class KeyspacesConnectorConfTest {

    private static final File CONF = new File("conf", ConnectionKeyspacesFactory.CONFIG_FILE_NAME);

    private static DriverExecutionProfile profile() {
        return DriverConfigLoader.fromFile(CONF).getInitialConfig().getDefaultProfile();
    }

    @Test
    void usesSigV4() {
        String authProvider = profile().getString(DefaultDriverOption.AUTH_PROVIDER_CLASS);
        assertEquals("software.aws.mcs.auth.SigV4AuthProvider", authProvider);
        assertDoesNotThrow(() -> Class.forName(authProvider), "SigV4 plugin is not on the classpath");
    }

    @Test
    void validatesTheServerHostname() {
        assertTrue(profile().getBoolean(DefaultDriverOption.SSL_HOSTNAME_VALIDATION));
    }

    @Test
    void containsNoCredentials() throws Exception {
        assertFalse(profile().isDefined(DefaultDriverOption.AUTH_PROVIDER_PASSWORD),
                "a password is configured; use SigV4 or inject it from the environment");
        // Also guard the raw text, in case a credential is added under an unexpected key.
        for (String line : Files.readAllLines(CONF.toPath(), StandardCharsets.UTF_8)) {
            String code = line.strip();
            if (code.startsWith("#")) {
                continue;
            }
            assertFalse(code.toLowerCase(Locale.ROOT).startsWith("password"), () -> "credential in conf: " + code);
        }
    }

    @Test
    void derivesTheEndpointFromTheRegion() {
        List<String> contactPoints = profile().getStringList(DefaultDriverOption.CONTACT_POINTS);
        String region = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        assertEquals(List.of("cassandra." + region + ".amazonaws.com:9142"), contactPoints);
        assertEquals(region, profile().getString(DefaultDriverOption.LOAD_BALANCING_LOCAL_DATACENTER));
    }
}
