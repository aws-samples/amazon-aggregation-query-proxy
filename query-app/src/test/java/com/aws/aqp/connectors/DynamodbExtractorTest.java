// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.connectors;

import com.aws.aqp.application.AppConfiguration;
import com.aws.aqp.core.errors.ResultTooLargeException;
import com.aws.aqp.core.sql.QueryPlanner;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnConsumedCapacity;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the extractor with a scripted client, so paging and accounting run without a server. */
class DynamodbExtractorTest {

    private final List<ExecuteStatementRequest> requests = new ArrayList<>();
    private final AtomicInteger describes = new AtomicInteger();
    /** What DescribeTable does: return a table keyed on "pk", or throw this. */
    private RuntimeException describeFailure;

    private DynamoDbClient client(List<ExecuteStatementResponse> pages) {
        return (DynamoDbClient) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DynamoDbClient.class}, (proxy, method, args) -> {
                    if ("executeStatement".equals(method.getName())
                            && args[0] instanceof ExecuteStatementRequest) {
                        requests.add((ExecuteStatementRequest) args[0]);
                        return pages.get(requests.size() - 1);
                    }
                    if ("describeTable".equals(method.getName()) && args[0] instanceof DescribeTableRequest) {
                        describes.incrementAndGet();
                        if (describeFailure != null) {
                            throw describeFailure;
                        }
                        return DescribeTableResponse.builder().table(TableDescription.builder()
                                .tableName(((DescribeTableRequest) args[0]).tableName())
                                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                                .build()).build();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ExecuteStatementResponse page(int items, String nextToken, Double rcu) {
        List<Map<String, AttributeValue>> rows = new ArrayList<>();
        for (int i = 0; i < items; i++) {
            rows.add(Map.of("amount", AttributeValue.builder().n("1").build()));
        }
        ExecuteStatementResponse.Builder b = ExecuteStatementResponse.builder().items(rows).nextToken(nextToken);
        if (rcu != null) {
            b.consumedCapacity(ConsumedCapacity.builder().capacityUnits(rcu).build());
        }
        return b.build();
    }

    private static AppConfiguration config(long maxRows) {
        AppConfiguration config = new AppConfiguration();
        config.setMaxRows(maxRows);
        return config;
    }

    private ExtractResult run(AppConfiguration config, List<ExecuteStatementResponse> pages) {
        return run(config, pages, "select sum(amount) as t FROM t");
    }

    private ExtractResult run(AppConfiguration config, List<ExecuteStatementResponse> pages, String query) {
        return new DynamodbExtractor(config, client(pages)).execute(new QueryPlanner().plan(query));
    }

    /** COUNT(*) fetched every attribute of every item; now only the hash key. */
    @Test
    void projectsOnlyTheHashKeyForCountStar() {
        DynamodbExtractor extractor = new DynamodbExtractor(config(1000), client(List.of(
                page(2, null, 1.0), page(2, null, 1.0))));
        extractor.execute(new QueryPlanner().plan("SELECT COUNT(*) AS c FROM \"my-table\" WHERE pk = 'a'"));
        extractor.execute(new QueryPlanner().plan("SELECT COUNT(*) AS c FROM \"my-table\" WHERE pk = 'b'"));

        assertEquals("SELECT \"pk\" FROM \"my-table\" WHERE pk = 'a'", requests.get(0).statement());
        assertEquals(1, describes.get(), "key schema should be described once and cached");
    }

    /** A GSI always carries the table's key, so the table's hash key is right for an index too. */
    @Test
    void usesTheTableKeyWhenQueryingAnIndex() {
        run(config(1000), List.of(page(1, null, 1.0)), "SELECT COUNT(*) AS c FROM \"orders\".\"by_zip\"");
        assertEquals("SELECT \"pk\" FROM \"orders\".\"by_zip\"", requests.get(0).statement());
    }

    /** Without dynamodb:DescribeTable the query still works, just less efficiently. */
    @Test
    void fallsBackToAllAttributesWhenTheTableCannotBeDescribed() {
        describeFailure = DynamoDbException.builder().statusCode(400).message("AccessDeniedException").build();
        run(config(1000), List.of(page(1, null, 1.0)), "SELECT COUNT(*) AS c FROM t");
        assertEquals("SELECT * FROM t", requests.get(0).statement());
    }

    @Test
    void doesNotDescribeWhenColumnsAreReferenced() {
        run(config(1000), List.of(page(1, null, 1.0)), "SELECT SUM(amount) AS s FROM t");
        assertEquals(0, describes.get());
    }

    /**
     * DynamoDB PartiQL has no LIMIT, so a pushable one is applied while paging: each request's
     * Limit asks for no more than the rows still needed, and paging stops once they are read.
     */
    @Test
    void stopsPagingAtAPushedDownLimit() {
        ExtractResult result = run(config(1000), List.of(
                page(3, "token-1", 1.0),
                page(3, "token-2", 1.0),
                page(3, null, 1.0)), "SELECT amount FROM t LIMIT 4");

        assertEquals(4, result.rowCount());
        assertEquals(2, requests.size(), "read a page after the limit was reached");
        assertEquals(4, requests.get(0).limit());
        assertEquals(1, requests.get(1).limit());
    }

    @Test
    void setsNoRequestLimitWhenTheLimitCannotBePushed() {
        run(config(1000), List.of(page(3, null, 1.0)), "SELECT SUM(amount) AS s FROM t LIMIT 1");
        assertNull(requests.get(0).limit());
    }

    @Test
    void followsNextTokenAndSumsCapacityAcrossPages() {
        ExtractResult result = run(config(1000), List.of(
                page(3, "token-1", 1.5),
                page(2, "token-2", 2.0),
                page(1, null, 0.5)));

        assertEquals(3, requests.size());
        assertNull(requests.get(0).nextToken());
        assertEquals("token-1", requests.get(1).nextToken());
        assertEquals("token-2", requests.get(2).nextToken());
        assertEquals(6, result.rowCount());
        assertEquals(4.0, result.consumedReadCapacityUnits());
        assertTrue(result.byteCount() > 0);
    }

    @Test
    void asksForConsumedCapacityOnEveryPage() {
        run(config(1000), List.of(page(1, "t", 1.0), page(1, null, 1.0)));
        for (ExecuteStatementRequest request : requests) {
            assertEquals(ReturnConsumedCapacity.TOTAL, request.returnConsumedCapacity());
            assertEquals("SELECT amount FROM t", request.statement());
        }
    }

    /** DynamoDB Local, for one, omits it; that must read as "unknown", not as zero. */
    @Test
    void reportsNoCapacityWhenTheStoreDoesNot() {
        assertNull(run(config(1000), List.of(page(2, null, null))).consumedReadCapacityUnits());
    }

    /**
     * The read budget spans all pages. Without the deadline the pagination loop had no total
     * time bound, so AQP_QUERY_TIMEOUT_SECONDS was a no-op in DynamoDB mode and a slow
     * multi-page scan could outlive any deployment stopTimeout with no 504.
     */
    @Test
    void stopsPagingWhenTheReadBudgetIsExhausted() {
        AppConfiguration config = config(1_000_000);
        config.setQueryTimeoutSeconds(1);
        DynamoDbClient slow = (DynamoDbClient) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DynamoDbClient.class}, (proxy, method, args) -> {
                    if ("executeStatement".equals(method.getName())) {
                        requests.add((ExecuteStatementRequest) args[0]);
                        Thread.sleep(1200); // one slow page eats the whole budget
                        return page(3, "next-token", 1.0);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        assertThrows(com.aws.aqp.core.errors.QueryTimeoutException.class,
                () -> new DynamodbExtractor(config, slow)
                        .execute(new QueryPlanner().plan("select sum(amount) as t FROM t")));
        assertEquals(1, requests.size(), "requested another page after the budget was exhausted");
    }

    /** The budget is enforced while paging, before later pages are requested. */
    @Test
    void stopsPagingOnceTheRowBudgetIsExceeded() {
        assertThrows(ResultTooLargeException.class, () -> run(config(4), List.of(
                page(3, "token-1", 1.0),
                page(3, "token-2", 1.0),
                page(3, null, 1.0))));
        assertEquals(2, requests.size(), "kept reading after the budget was exceeded");
    }
}
