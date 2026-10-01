// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.health;

import com.codahale.metrics.health.HealthCheck;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest;

/**
 * Checks DynamoDB reachability with a real, cheap API call over the application's own client.
 * <p>
 * This replaces an {@code HttpHealthCheck} pointed at the service endpoint. A bare HTTP GET to
 * {@code dynamodb.<region>.amazonaws.com} is not a 2xx — DynamoDB only answers signed POSTs — so
 * that check could not pass even when the dependency was perfectly healthy.
 */
public class DynamoDbHealthCheck extends HealthCheck {

    private final DynamoDbClient dynamoDbClient;

    public DynamoDbHealthCheck(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    @Override
    protected Result check() {
        dynamoDbClient.listTables(ListTablesRequest.builder().limit(1).build());
        return Result.healthy();
    }
}
