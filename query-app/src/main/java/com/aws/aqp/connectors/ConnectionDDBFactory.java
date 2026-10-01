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

    /*
     * Each ExecuteStatement page is its own API call, so these bound one page, not the whole
     * query. Every retry must be able to happen inside the call timeout, or the retry count
     * only misdescribes the client's behaviour. The SDK's throttling back-off (legacy mode:
     * 500ms base, doubling, 20s cap) makes the worst case, with each attempt running to its
     * attempt timeout, 4 x 5s + (0.5 + 1 + 2)s = 23.5s, inside the 30s call timeout.
     * ConnectionDDBFactoryTest checks this against the SDK's own constants.
     *
     * History: 64 retries under a 10s timeout, then 8 under 30s — the latter needed 35.8-71.5s
     * of back-off alone, so neither could ever use its budget.
     */
    static final int NUM_RETRIES_DDB = 3;
    static final Duration API_CALL_ATTEMPT_TIMEOUT_DDB = Duration.ofSeconds(5);
    static final Duration API_CALL_TIMEOUT_DDB = Duration.ofSeconds(30);
    public final static String LOCAL_ENDPOINT = "http://localhost:8000";

    private final AppConfiguration appConfiguration;

    public ConnectionDDBFactory(AppConfiguration appConfiguration) {
        this.appConfiguration = appConfiguration;

    }

    /** The client for the configured target: DynamoDB Local when {@code localDDB} is set. */
    public DynamoDbClient build() {
        return Boolean.TRUE.equals(appConfiguration.getLocalDDB()) ? buildDDBLocalSession() : buildDDBSession();
    }

    public DynamoDbClient buildDDBSession() {
        Region region = Region.of(appConfiguration.getAwsRegion());
        return DynamoDbClient.builder()
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .retryPolicy(RetryPolicy.builder()
                                .numRetries(NUM_RETRIES_DDB)
                                .backoffStrategy(BackoffStrategy.defaultStrategy())
                                .throttlingBackoffStrategy(BackoffStrategy.defaultThrottlingStrategy())
                                .retryCondition(RetryCondition.defaultRetryCondition())
                                .build())
                        .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT_DDB)
                        .apiCallTimeout(API_CALL_TIMEOUT_DDB)
                        .build())
                .region(region)
                .build();
    }

    public DynamoDbClient buildDDBLocalSession() {
        return DynamoDbClient.builder()
                .endpointOverride(URI.create(LOCAL_ENDPOINT))
                // The region is meaningless for local DynamoDb but required for client builder validation
                .region(Region.US_EAST_1)
                // DynamoDB Local validates the access key format and rejects anything that is not
                // AKID-shaped with UnrecognizedClientException, so the previous "dummy-key" made
                // every local run fail. These are the published AWS example credentials.
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                "AKIAIOSFODNN7EXAMPLE",
                                "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")))
                .build();
    }
}
