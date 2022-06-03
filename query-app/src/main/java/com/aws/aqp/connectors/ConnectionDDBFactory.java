// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.connectors;

import com.aws.aqp.application.AppConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.retry.backoff.BackoffStrategy;
import software.amazon.awssdk.core.retry.conditions.RetryCondition;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.time.Duration;

public class ConnectionDDBFactory {

    private final static int NUM_RETRIES_DDB = 64;
    private final static int API_CALL_TIMEOUT_DDB = 10000;

    private final AppConfiguration appConfiguration;

    public ConnectionDDBFactory(AppConfiguration appConfiguration) {
        this.appConfiguration = appConfiguration;

    }

    public DynamoDbClient buildDDBSession() {
        Region region = Region.of(appConfiguration.getAwsRegion());
        return DynamoDbClient.builder()
                .overrideConfiguration(ClientOverrideConfiguration
                        .builder().retryPolicy(RetryPolicy.builder()
                                .numRetries(NUM_RETRIES_DDB)
                                .backoffStrategy(BackoffStrategy.defaultStrategy()).
                                        throttlingBackoffStrategy(BackoffStrategy.defaultThrottlingStrategy()).
                                        numRetries(NUM_RETRIES_DDB).
                                        retryCondition(RetryCondition.defaultRetryCondition()).build()).apiCallTimeout(Duration.ofMillis(API_CALL_TIMEOUT_DDB)).build()
                )
                .region(region)
                .build();
    }

    public DynamoDbClient buildDDBLocalSession() {
        return DynamoDbClient.builder()
                .endpointOverride(URI.create("http://localhost:8000"))
                // The region is meaningless for local DynamoDb but required for client builder validation
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("dummy-key", "dummy-secret")))
                .build();
    }
}
