// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.connectors;

import com.aws.aqp.application.AppConfiguration;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.type.codec.TypeCodecs;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.retry.backoff.BackoffStrategy;
import software.amazon.awssdk.core.retry.conditions.RetryCondition;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.io.File;
import java.net.URI;
import java.time.Duration;

public class ConnectionKeyspacesFactory {

    private final AppConfiguration appConfiguration;

    public ConnectionKeyspacesFactory(AppConfiguration appConfiguration) {
        this.appConfiguration = appConfiguration;
    }

    public CqlSession buildSession() {
        final File configFile = new File(String.format("%s/%s", appConfiguration.getPathToKeyspacesConfigFile(), "KeyspacesConnector.conf"));

        return CqlSession.builder()
                .withConfigLoader(DriverConfigLoader.fromFile(configFile))
                .addTypeCodecs(TypeCodecs.ZONED_TIMESTAMP_UTC)
                .build();
    }

}
