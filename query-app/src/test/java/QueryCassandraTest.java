// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

import com.aws.aqp.application.App;
import com.aws.aqp.application.AppConfiguration;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.BatchStatementBuilder;
import com.datastax.oss.driver.api.core.cql.DefaultBatchType;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the Amazon Keyspaces code path — CQL push-down with SELECT JSON, async multi-page reads,
 * aggregation — against a local Apache Cassandra on 127.0.0.1:9042. Keyspaces speaks the same
 * CQL protocol; what this cannot cover is SigV4 and TLS to the managed endpoint.
 * <p>
 * Skipped when nothing is listening on 9042. CI provides a Cassandra service container.
 */
@ExtendWith(DropwizardExtensionsSupport.class)
// A hung backend call fails that one test within 2 minutes instead of stalling the build.
@Timeout(value = 2, unit = TimeUnit.MINUTES)
@EnabledIf("cassandraIsReachable")
class QueryCassandraTest {

    private static final String KEYSPACE = "aqp_test";
    private static final int ORDERS = 1000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Created lazily: the extension must not be constructed (and try to connect) when skipped.
    private static DropwizardAppExtension<AppConfiguration> EXT = cassandraIsReachable()
            ? new DropwizardAppExtension<>(App.class,
                    ResourceHelpers.resourceFilePath("cassandra-test.yaml"),
                    ConfigOverride.config("pathToKeyspacesConfigFile", ResourceHelpers.resourceFilePath("cassandra")))
            : null;

    private static String authorization;

    @SuppressWarnings("unused") // referenced by @EnabledIf
    static boolean cassandraIsReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 9042), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Session for creating fixtures. DDL on a freshly started node (as in CI) can take longer
     * than the driver's 2s default request timeout.
     */
    private static CqlSession fixtureSession() {
        return CqlSession.builder()
                .addContactPoint(new InetSocketAddress("127.0.0.1", 9042))
                .withLocalDatacenter("datacenter1")
                .withConfigLoader(DriverConfigLoader.programmaticBuilder()
                        .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(60))
                        .build())
                .build();
    }

    /**
     * 1000 orders across 10 customers (partitions) and 3 regions. Every order has amount 2, so
     * rows are identical after projection: any de-duplication would show up as a wrong total.
     */
    @BeforeAll
    static void seed() {
        authorization = "Basic " + Base64.getEncoder().encodeToString(
                "small-query-app:test-only-secret-small-0001".getBytes(StandardCharsets.UTF_8));

        try (CqlSession session = fixtureSession()) {
            session.execute("CREATE KEYSPACE IF NOT EXISTS " + KEYSPACE
                    + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}");
            session.execute("DROP TABLE IF EXISTS " + KEYSPACE + ".orders");
            session.execute("CREATE TABLE " + KEYSPACE + ".orders ("
                    + "customer text, order_id int, region text, amount int, "
                    + "PRIMARY KEY (customer, order_id))");
            PreparedStatement insert = session.prepare("INSERT INTO " + KEYSPACE
                    + ".orders (customer, order_id, region, amount) VALUES (?, ?, ?, ?)");
            String[] regions = {"north", "south", "east"};
            for (int c = 0; c < 10; c++) {
                BatchStatementBuilder batch = BatchStatement.builder(DefaultBatchType.UNLOGGED);
                for (int i = 0; i < ORDERS / 10; i++) {
                    int orderId = c * 1000 + i;
                    batch.addStatement(insert.bind("c" + c, orderId, regions[orderId % 3], 2));
                }
                session.execute(batch.build());
            }
        }
    }

    private static HttpResponse<String> post(String query) throws JsonProcessingException {
        return Unirest.post(String.format("http://localhost:%d/query-aggregation", EXT.getLocalPort()))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .body(MAPPER.writeValueAsString(Map.of("query", query)))
                .asString();
    }

    private static JsonNode rows(String query) throws JsonProcessingException {
        HttpResponse<String> response = post(query);
        assertEquals(200, response.getStatus(), () -> query + " -> " + response.getBody());
        return MAPPER.readTree(response.getBody()).get("response").get(0).get("resultSet");
    }

    /** 1000 rows at page size 50 is 20 pages; all must arrive, none collapsed. */
    @Test
    void readsEveryPageAndKeepsIdenticalRows() throws JsonProcessingException {
        JsonNode body = MAPPER.readTree(post(
                "SELECT COUNT(amount) AS n, SUM(amount) AS total FROM " + KEYSPACE + ".orders").getBody());
        JsonNode row = body.get("response").get(0).get("resultSet").get(0);
        assertEquals(ORDERS, row.get("n").asInt(), body::toString);
        assertEquals(2 * ORDERS, row.get("total").asInt(), body::toString);
        assertEquals(ORDERS, body.get("stats").get("rowsRetrieved").asInt());
    }

    @Test
    void pushesThePartitionKeyPredicateDown() throws JsonProcessingException {
        JsonNode body = MAPPER.readTree(post("SELECT COUNT(order_id) AS n FROM " + KEYSPACE
                + ".orders WHERE customer = 'c3'").getBody());
        assertEquals(100, body.get("response").get(0).get("resultSet").get(0).get("n").asInt(), body::toString);
        // Only the partition was read, not the table.
        assertEquals(100, body.get("stats").get("rowsRetrieved").asInt(), body::toString);
    }

    /** region is not selected; it must still be fetched to group by it. */
    @Test
    void groupsByANonKeyColumnThatIsNotSelected() throws JsonProcessingException {
        JsonNode rows = rows("SELECT SUM(amount) AS total FROM " + KEYSPACE + ".orders GROUP BY region");
        assertEquals(3, rows.size(), rows::toString);
        int sum = 0;
        for (JsonNode r : rows) {
            sum += r.get("total").asInt();
        }
        assertEquals(2 * ORDERS, sum);
    }

    /** ALLOW FILTERING is CQL-only; it must reach Cassandra, not the aggregation engine. */
    @Test
    void passesAllowFilteringThrough() throws JsonProcessingException {
        JsonNode rows = rows("SELECT region, COUNT(order_id) AS n FROM " + KEYSPACE
                + ".orders WHERE region = 'north' GROUP BY region ALLOW FILTERING");
        assertEquals(1, rows.size(), rows::toString);
        assertEquals("north", rows.get(0).get("region").asText());
        assertTrue(rows.get(0).get("n").asInt() > 300, rows::toString);
    }

    @Test
    void appliesLimitToGroups() throws JsonProcessingException {
        JsonNode rows = rows("SELECT region, SUM(amount) AS total FROM " + KEYSPACE
                + ".orders GROUP BY region LIMIT 2");
        assertEquals(2, rows.size(), rows::toString);
    }

    /**
     * Case-sensitive column names arrive from SELECT JSON under keys that keep their quotes, and
     * the aggregation step previously failed with "No such binding".
     */
    @Test
    void aggregatesCaseSensitiveColumns() throws JsonProcessingException {
        try (CqlSession session = fixtureSession()) {
            session.execute("DROP TABLE IF EXISTS " + KEYSPACE + ".mixed_case");
            session.execute("CREATE TABLE " + KEYSPACE + ".mixed_case (pk int PRIMARY KEY, \"Amount\" int, \"Region\" text)");
            session.execute("INSERT INTO " + KEYSPACE + ".mixed_case (pk, \"Amount\", \"Region\") VALUES (1, 5, 'n')");
            session.execute("INSERT INTO " + KEYSPACE + ".mixed_case (pk, \"Amount\", \"Region\") VALUES (2, 7, 'n')");
            session.execute("INSERT INTO " + KEYSPACE + ".mixed_case (pk, \"Amount\", \"Region\") VALUES (3, 1, 's')");
        }

        JsonNode rows = rows("SELECT \"Region\", SUM(\"Amount\") AS total FROM " + KEYSPACE
                + ".mixed_case GROUP BY \"Region\" ORDER BY \"Region\"");
        assertEquals(2, rows.size(), rows::toString);
        assertEquals("n", rows.get(0).get("Region").asText(), rows::toString);
        assertEquals(12, rows.get(0).get("total").asInt(), rows::toString);
    }

    /** The LIMIT reaches Cassandra, so only that many rows are read. */
    @Test
    void readsNoMoreRowsThanAPlainLimitNeeds() throws JsonProcessingException {
        JsonNode body = MAPPER.readTree(post("SELECT order_id, amount FROM " + KEYSPACE + ".orders LIMIT 7").getBody());
        assertEquals(7, body.get("response").get(0).get("resultSet").size(), body::toString);
        assertEquals(7, body.get("stats").get("rowsRetrieved").asInt(), body::toString);
    }

    /** But an aggregate's LIMIT must not cap the rows read: this must still count all 1000. */
    @Test
    void doesNotCapRowsForAnAggregateLimit() throws JsonProcessingException {
        JsonNode rows = rows("SELECT COUNT(*) AS n FROM " + KEYSPACE + ".orders LIMIT 1");
        assertEquals(ORDERS, rows.get(0).get("n").asInt(), rows::toString);
    }

    /** COUNT(*) projects the partition key from schema metadata instead of every column. */
    @Test
    void countStarFetchesOnlyThePartitionKey() throws JsonProcessingException {
        JsonNode body = MAPPER.readTree(post("SELECT COUNT(*) AS n FROM " + KEYSPACE + ".orders").getBody());
        assertEquals(ORDERS, body.get("response").get(0).get("resultSet").get(0).get("n").asInt(), body::toString);
        long payload = body.get("stats").get("payloadSizeBytes").asLong();
        // {"customer": "c3"} is 18 bytes; whole rows (4 columns) are about 70.
        assertTrue(payload < ORDERS * 25L, () -> "fetched whole rows: " + payload + " bytes");
    }

    @Test
    void mapsCqlErrorsToClientErrors() throws JsonProcessingException {
        // Filtering on a non-key column without ALLOW FILTERING is rejected by Cassandra.
        HttpResponse<String> response = post("SELECT COUNT(order_id) AS n FROM " + KEYSPACE
                + ".orders WHERE region = 'north'");
        assertTrue(response.getStatus() >= 400 && response.getStatus() < 500,
                () -> "expected a 4xx, got " + response.getStatus() + ": " + response.getBody());
    }
}
