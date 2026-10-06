// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.health;

import com.codahale.metrics.health.HealthCheck;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest;

/**
 * Checks DynamoDB reachability with a real, cheap API call over the application's own client.
 * <p>
 * When {@code dynamoHealthCheckTable} is configured, the probe is {@code DescribeTable} on that
 * table, so the task role needs no account-wide permission: {@code dynamodb:ListTables} cannot
 * be scoped to a resource, which made least-privilege IAM impossible. Without the setting it
 * falls back to {@code ListTables(limit=1)}, keeping zero-config local runs working.
 * <p>
 * This replaced an {@code HttpHealthCheck} pointed at the service endpoint: a bare HTTP GET to
 * {@code dynamodb.<region>.amazonaws.com} is never a 2xx — DynamoDB only answers signed POSTs.
 */
public class DynamoDbHealthCheck extends HealthCheck {

    private final DynamoDbClient dynamoDbClient;
    private final String table;

    /** @param table table to {@code DescribeTable}, or {@code null} to use {@code ListTables} */
    public DynamoDbHealthCheck(DynamoDbClient dynamoDbClient, String table) {
        this.dynamoDbClient = dynamoDbClient;
        this.table = table;
    }

    @Override
    protected Result check() {
        if (table != null) {
            dynamoDbClient.describeTable(DescribeTableRequest.builder().tableName(table).build());
        } else {
            dynamoDbClient.listTables(ListTablesRequest.builder().limit(1).build());
        }
        return Result.healthy();
    }
}
