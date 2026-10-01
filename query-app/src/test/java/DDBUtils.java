// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test fixtures for DynamoDB Local.
 * <p>
 * Failures propagate as exceptions. The previous version called System.exit(1) on error, which
 * killed the Surefire JVM instead of failing the test that caused it.
 */
public class DDBUtils {

    private static final int BATCH_WRITE_MAX_ITEMS = 25;

    public static String createTable(DynamoDbClient ddb, String tableName, String key) {
        DynamoDbWaiter dbWaiter = ddb.waiter();
        CreateTableRequest request = CreateTableRequest.builder()
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName(key)
                        .attributeType(ScalarAttributeType.S)
                        .build())
                .keySchema(KeySchemaElement.builder()
                        .attributeName(key)
                        .keyType(KeyType.HASH)
                        .build())
                .provisionedThroughput(ProvisionedThroughput.builder()
                        .readCapacityUnits(10L)
                        .writeCapacityUnits(10L)
                        .build())
                .tableName(tableName)
                .build();

        CreateTableResponse response = ddb.createTable(request);
        dbWaiter.waitUntilTableExists(DescribeTableRequest.builder().tableName(tableName).build());
        return response.tableDescription().tableName();
    }

    /** Drops the table if it is there. Tolerates absence, so a clean environment works. */
    public static void deleteTableIfExists(DynamoDbClient ddb, String tableName) {
        try {
            ddb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
            ddb.waiter().waitUntilTableNotExists(
                    DescribeTableRequest.builder().tableName(tableName).build());
        } catch (ResourceNotFoundException e) {
            // Nothing to delete.
        }
    }

    public static void putItemInTable(DynamoDbClient ddb,
                                      String tableName,
                                      String pk,
                                      String clicks,
                                      String type) {

        String boundQuery = String.format("INSERT INTO %s VALUE {'pk':'%s','clicks':%s,'type':'%s'}", tableName, pk, clicks, type);

        ExecuteStatementRequest request = ExecuteStatementRequest.builder().
                statement(boundQuery).
                build();

        ddb.executeStatement(request);
    }

    /**
     * Writes {@code count} items, each carrying a filler attribute of {@code payloadBytes}, so a
     * scan of the table exceeds DynamoDB's 1 MB page limit and the extractor has to paginate.
     */
    public static void putBulkItems(DynamoDbClient ddb, String tableName, int count, int payloadBytes) {
        String payload = "x".repeat(payloadBytes);
        List<WriteRequest> batch = new ArrayList<>(BATCH_WRITE_MAX_ITEMS);

        for (int i = 0; i < count; i++) {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("pk", AttributeValue.builder().s(String.format("bulk-%05d", i)).build());
            item.put("clicks", AttributeValue.builder().n("1").build());
            item.put("type", AttributeValue.builder().s("bulk").build());
            item.put("filler", AttributeValue.builder().s(payload).build());
            batch.add(WriteRequest.builder()
                    .putRequest(PutRequest.builder().item(item).build())
                    .build());

            if (batch.size() == BATCH_WRITE_MAX_ITEMS || i == count - 1) {
                flush(ddb, tableName, batch);
                batch.clear();
            }
        }
    }

    private static void flush(DynamoDbClient ddb, String tableName, List<WriteRequest> batch) {
        Map<String, List<WriteRequest>> pending = Map.of(tableName, new ArrayList<>(batch));
        while (!pending.isEmpty()) {
            BatchWriteItemResponse response = ddb.batchWriteItem(
                    BatchWriteItemRequest.builder().requestItems(pending).build());
            pending = response.unprocessedItems();
        }
    }
}
