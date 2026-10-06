// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.connectors;

import com.aws.aqp.application.AppConfiguration;
import com.aws.aqp.core.JsonHelper;
import com.aws.aqp.core.errors.QueryTimeoutException;
import com.aws.aqp.core.errors.ResultTooLargeException;
import com.aws.aqp.core.sql.Dialect;
import com.aws.aqp.core.sql.QueryPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnConsumedCapacity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The client is owned by the application, which closes it on shutdown. */
public class DynamodbExtractor extends Extractor {

    private final DynamoDbClient dynamoDbClient;
    private final long maxRows;
    private final long maxResultBytes;
    private final long queryTimeoutSeconds;
    /**
     * Hash key attribute per table, or empty if it could not be described (for example no
     * dynamodb:DescribeTable permission). A table's key schema cannot change, so entries never
     * expire; a newly granted permission takes effect on restart.
     */
    private final Map<String, Optional<String>> hashKeys = new ConcurrentHashMap<>();

    private static final Logger LOGGER = LoggerFactory.getLogger(DynamodbExtractor.class);

    public DynamodbExtractor(AppConfiguration appConfiguration, DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
        this.maxRows = appConfiguration.getMaxRows();
        this.maxResultBytes = appConfiguration.getMaxResultBytes();
        this.queryTimeoutSeconds = appConfiguration.getQueryTimeoutSeconds();
    }

    /**
     * DynamoDB PartiQL has no {@code SELECT JSON}; items arrive as attribute maps and are
     * converted below. The statement is rendered here rather than by stripping a {@code json}
     * marker from a CQL-shaped string — that strip was once {@code query.replace("json", "")},
     * which also mangled any table or column whose name contained "json".
     */
    @Override
    public ExtractResult execute(QueryPlan plan) {
        // A query that references no column (COUNT(*)) needs only something present on every
        // item: the table's hash key, which a GSI also always carries. Fetching every attribute
        // instead could trip maxResultBytes long before maxRows on a table with large items.
        Optional<String> key = plan.needsNoColumns() ? hashKey(plan) : Optional.empty();
        String statement = key.isPresent()
                ? plan.pushDownStatement(Dialect.DYNAMODB_PARTIQL, List.of(quote(key.get())))
                : plan.pushDownStatement(Dialect.DYNAMODB_PARTIQL);
        // DynamoDB PartiQL has no LIMIT; a pushable one is applied here while paging instead.
        long rowLimit = plan.rowLimit().orElse(Long.MAX_VALUE);
        LOGGER.debug("Push-down statement: {}", statement);

        // A List, not a Set: two items that agree on every projected attribute serialize to
        // identical JSON, and a Set silently collapsed them, under-reporting COUNT and SUM.
        // The projection usually excludes the primary key, so this was easy to trigger.
        List<String> rows = new ArrayList<>();
        long byteCount = 0;
        String nextToken = null;
        int pages = 0;
        double consumedCapacity = 0;
        boolean capacityReported = false;

        // The read budget spans all pages: each ExecuteStatement call is individually capped by
        // the SDK, but the number of pages is not, so without this a large scan could run for
        // minutes - past any deployment stopTimeout - with no 504 ever produced.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(queryTimeoutSeconds);

        // DynamoDB caps a page at 1 MB. Reading one page and aggregating it was reported as a
        // complete answer, so any larger result set produced a silently wrong aggregate.
        // Exceptions are no longer swallowed here: they propagate to the ExceptionMapper, which
        // turns them into a meaningful status instead of the NullPointerException that used to
        // follow from dereferencing a null response.
        do {
            if (System.nanoTime() > deadline) {
                throw new QueryTimeoutException(String.format(
                        "DynamoDB did not return a complete result set within %d seconds.", queryTimeoutSeconds));
            }
            ExecuteStatementRequest request = ExecuteStatementRequest.builder()
                    .statement(statement)
                    .nextToken(nextToken)
                    // Makes the RCU cost of each query visible; otherwise an expensive scan
                    // shows up only on the table's bill.
                    .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                    // Evaluate no more items than are still needed (it caps items read, not
                    // returned, so a filtered query may still need further pages).
                    .limit(rowLimit == Long.MAX_VALUE ? null : (int) Math.min(Integer.MAX_VALUE, rowLimit - rows.size()))
                    .build();
            ExecuteStatementResponse response = dynamoDbClient.executeStatement(request);
            pages++;
            if (response.consumedCapacity() != null && response.consumedCapacity().capacityUnits() != null) {
                consumedCapacity += response.consumedCapacity().capacityUnits();
                capacityReported = true;
            }

            for (Map<String, AttributeValue> item : response.items()) {
                if (rows.size() >= rowLimit) {
                    break;
                }
                String json = JsonHelper.toJson(item).toString();
                rows.add(json);
                byteCount += json.getBytes(StandardCharsets.UTF_8).length;

                if (rows.size() > maxRows) {
                    throw new ResultTooLargeException(String.format(
                            "Query matched more than the configured maximum of %d rows. "
                                    + "Narrow the WHERE clause or raise maxRows.", maxRows));
                }
                if (byteCount > maxResultBytes) {
                    throw new ResultTooLargeException(String.format(
                            "Result set exceeded the configured maximum of %d bytes. "
                                    + "Narrow the WHERE clause or raise maxResultBytes.", maxResultBytes));
                }
            }
            nextToken = response.nextToken();
        } while (nextToken != null && rows.size() < rowLimit);

        LOGGER.debug("Retrieved {} rows ({} bytes, {} RCU) from DynamoDB across {} page(s)",
                rows.size(), byteCount, capacityReported ? consumedCapacity : "n/a", pages);

        return new ExtractResult(
                String.format("{\"resultSet\":[%s]}", String.join(",", rows)),
                rows.size(), byteCount, capacityReported ? consumedCapacity : null);
    }


    private Optional<String> hashKey(QueryPlan plan) {
        String table = unquote(plan.sourceParts().get(0));
        return hashKeys.computeIfAbsent(table, name -> {
            try {
                return dynamoDbClient.describeTable(DescribeTableRequest.builder().tableName(name).build())
                        .table().keySchema().stream()
                        .filter(k -> k.keyType() == KeyType.HASH)
                        .map(KeySchemaElement::attributeName)
                        .findFirst();
            } catch (ResourceNotFoundException e) {
                throw e; // Not cached: the table may be created later. Mapped to 404.
            } catch (RuntimeException e) {
                LOGGER.warn("Could not describe table {} ({}); COUNT(*)-style queries on it will fetch "
                        + "all attributes. Grant dynamodb:DescribeTable to avoid this.", name, e.getMessage());
                return Optional.empty();
            }
        });
    }

    private static String unquote(String identifier) {
        if (identifier.length() >= 2 && identifier.startsWith("\"") && identifier.endsWith("\"")) {
            return identifier.substring(1, identifier.length() - 1).replace("\"\"", "\"");
        }
        return identifier;
    }

    private static String quote(String attribute) {
        return '"' + attribute.replace("\"", "\"\"") + '"';
    }
}
