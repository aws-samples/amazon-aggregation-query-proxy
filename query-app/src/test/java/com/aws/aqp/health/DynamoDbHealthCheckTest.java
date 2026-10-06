// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.health;

import com.codahale.metrics.health.HealthCheck;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamoDbHealthCheckTest {

    private final List<String> calls = new ArrayList<>();

    private DynamoDbClient client() {
        return (DynamoDbClient) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DynamoDbClient.class}, (proxy, method, args) -> {
                    calls.add(method.getName());
                    if ("describeTable".equals(method.getName())) {
                        return DescribeTableResponse.builder().table(TableDescription.builder()
                                .tableName(((DescribeTableRequest) args[0]).tableName()).build()).build();
                    }
                    if ("listTables".equals(method.getName()) && args[0] instanceof ListTablesRequest) {
                        return ListTablesResponse.builder().build();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /**
     * With a configured table the probe is DescribeTable, which IAM can scope to that table's
     * ARN; the account-wide ListTables (unscopable) must not be called at all.
     */
    @Test
    void probesTheConfiguredTableWithDescribeTable() throws Exception {
        HealthCheck.Result result = new DynamoDbHealthCheck(client(), "orders").execute();
        assertTrue(result.isHealthy());
        assertEquals(List.of("describeTable"), calls);
    }

    @Test
    void fallsBackToListTablesWhenNoTableIsConfigured() throws Exception {
        HealthCheck.Result result = new DynamoDbHealthCheck(client(), null).execute();
        assertTrue(result.isHealthy());
        assertEquals(List.of("listTables"), calls);
    }
}
