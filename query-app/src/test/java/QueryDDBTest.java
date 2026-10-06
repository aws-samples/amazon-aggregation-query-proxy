// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

import com.aws.aqp.application.App;
import com.aws.aqp.application.AppConfiguration;
import com.aws.aqp.connectors.ConnectionDDBFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests against DynamoDB Local (expected on http://localhost:8000).
 * <p>
 * Renamed from {@code QueryTestDDB}, which matched none of Surefire's default include patterns
 * ({@code Test*}, {@code *Test}, {@code *Tests}, {@code *TestCase}) and therefore never ran in
 * any build. That is how a startup defect that made DynamoDB mode impossible to launch survived
 * unnoticed.
 * <p>
 * Assertions are JUnit assertions. The previous version used the bare {@code assert} keyword,
 * which is a no-op whenever assertions are not enabled, so the suite could pass vacuously.
 * <p>
 * Skipped, not failed, when DynamoDB Local is not running, so the unit tests remain runnable
 * without it.
 */
@ExtendWith(DropwizardExtensionsSupport.class)
// A hung backend call fails that one test within 2 minutes instead of stalling the build.
@Timeout(value = 2, unit = TimeUnit.MINUTES)
@EnabledIf("dynamoDbLocalIsReachable")
class QueryDDBTest {

    private static final String TABLE = "testTable";

    @SuppressWarnings("unused") // referenced by @EnabledIf
    static boolean dynamoDbLocalIsReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 8000), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static DropwizardAppExtension<AppConfiguration> EXT = new DropwizardAppExtension<>(
            App.class,
            ResourceHelpers.resourceFilePath("keyspaces-aggregation-query-proxy.yaml"));

    private static String encoding;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    public static void setup() {
        encoding = Base64.getEncoder().encodeToString(
                String.format("small-query-app:%s",
                        EXT.getConfiguration().getUsers().get("small-query-app").getSecret())
                        .getBytes(StandardCharsets.UTF_8));

        ConnectionDDBFactory testConnection = new ConnectionDDBFactory(new AppConfiguration());
        var dynamoDB = testConnection.buildDDBLocalSession();

        DDBUtils.deleteTableIfExists(dynamoDB, TABLE);
        DDBUtils.createTable(dynamoDB, TABLE, "pk");

        DDBUtils.putItemInTable(dynamoDB, TABLE, "Record1", "1", "test");
        DDBUtils.putItemInTable(dynamoDB, TABLE, "Record2", "2", "test");
        DDBUtils.putItemInTable(dynamoDB, TABLE, "Record3", "3", "test");
        DDBUtils.putItemInTable(dynamoDB, TABLE, "Record4", "4", "test");
    }

    /** Encodes the statement as a single path segment; a raw '{' or '"' is not legal in a URI. */
    private static String url(String sql) {
        String encoded = URLEncoder.encode(sql, StandardCharsets.UTF_8).replace("+", "%20");
        return String.format("http://localhost:%d/query-aggregation/%s", EXT.getLocalPort(), encoded);
    }

    private static HttpResponse<String> send(String sql) {
        return Unirest.get(url(sql))
                .header("Authorization", String.format("Basic %s", encoding))
                .asString();
    }

    private static JsonNode query(String sql) throws JsonProcessingException {
        HttpResponse<String> response = send(sql);
        assertEquals(200, response.getStatus(),
                () -> "unexpected status for [" + sql + "]: " + response.getBody());
        return MAPPER.readTree(response.getBody());
    }

    private static JsonNode firstRow(JsonNode body) {
        return body.get("response").get(0).get("resultSet").get(0);
    }

    @Test
    void selectCountUpperCase() throws JsonProcessingException {
        assertEquals(4, firstRow(query("SELECT COUNT(pk) as CNT FROM testTable")).get("CNT").asInt());
    }

    @Test
    void selectCountLowerCase() throws JsonProcessingException {
        assertEquals(4, firstRow(query("select count(pk) as CNT FROM testTable")).get("CNT").asInt());
    }

    @Test
    void selectCountGroupByCase() throws JsonProcessingException {
        JsonNode row = firstRow(query("select SUM(clicks) as cnt, type fROM testTable GROUP by type"));
        assertEquals(10, row.get("cnt").asInt());
        assertEquals("test", row.get("type").asText());
    }

    @Test
    void selectCountWhereClause() throws JsonProcessingException {
        assertEquals(1, firstRow(
                query("select count(pk) as CNT FROM testTable where pk = 'Record1' ")).get("CNT").asInt());
    }

    @Test
    void selectCountWhereClauseIn() throws JsonProcessingException {
        assertEquals(3, firstRow(
                query("select count(pk) as CNT FROM testTable where pk in ('Record1','Record2','Record3')"))
                .get("CNT").asInt());
    }

    @Test
    void selectCountGroupByAndOtherColumns() throws JsonProcessingException {
        JsonNode row = firstRow(query(
                "select type, count(pk) as CNT from testTable where pk in ('Record1','Record2','Record3','Record4') group by type"));
        assertEquals(4, row.get("CNT").asInt());
        assertEquals("test", row.get("type").asText());
    }

    /**
     * The push-down projection here is just {@code clicks}, so all four rows serialize to the
     * same JSON. Collecting rows into a Set collapsed them into one and SUM came back as 1
     * instead of 10.
     */
    @Test
    void doesNotDropRowsThatLookIdenticalAfterProjection() throws JsonProcessingException {
        ConnectionDDBFactory factory = new ConnectionDDBFactory(new AppConfiguration());
        var ddb = factory.buildDDBLocalSession();
        String table = "dupTable";
        DDBUtils.deleteTableIfExists(ddb, table);
        DDBUtils.createTable(ddb, table, "pk");
        // Distinct keys, identical projected value.
        DDBUtils.putItemInTable(ddb, table, "d1", "10", "dup");
        DDBUtils.putItemInTable(ddb, table, "d2", "10", "dup");
        DDBUtils.putItemInTable(ddb, table, "d3", "10", "dup");
        DDBUtils.putItemInTable(ddb, table, "d4", "10", "dup");

        JsonNode row = firstRow(query("select sum(clicks) as total FROM dupTable"));
        assertEquals(40, row.get("total").asInt(),
                "duplicate projected rows were de-duplicated away");
    }

    /**
     * DynamoDB caps a page at 1 MB of scanned data. Reading only the first page and aggregating
     * it was reported as a complete answer, so any larger table produced a silently low count.
     */
    @Test
    void paginatesBeyondTheOneMegabytePageLimit() throws JsonProcessingException {
        ConnectionDDBFactory factory = new ConnectionDDBFactory(new AppConfiguration());
        var ddb = factory.buildDDBLocalSession();
        String table = "pagedTable";
        DDBUtils.deleteTableIfExists(ddb, table);
        DDBUtils.createTable(ddb, table, "pk");

        int items = 1500;
        DDBUtils.putBulkItems(ddb, table, items, 1024);

        JsonNode row = firstRow(query("select count(pk) as CNT FROM pagedTable"));
        assertEquals(items, row.get("CNT").asInt(),
                "result set was truncated at the first 1 MB page");
    }

    /**
     * The GROUP BY key is not in the SELECT list. The regex planner never projected it, so every
     * row arrived without it and collapsed into a single group.
     */
    @Test
    void groupsByAColumnThatIsNotSelected() throws JsonProcessingException {
        var ddb = new ConnectionDDBFactory(new AppConfiguration()).buildDDBLocalSession();
        String table = "zipTable";
        DDBUtils.deleteTableIfExists(ddb, table);
        DDBUtils.createTable(ddb, table, "pk");
        DDBUtils.putItemInTable(ddb, table, "z1", "10", "A");
        DDBUtils.putItemInTable(ddb, table, "z2", "10", "A");
        DDBUtils.putItemInTable(ddb, table, "z3", "5", "B");

        JsonNode rows = query("select sum(clicks) as total FROM zipTable GROUP BY type")
                .get("response").get(0).get("resultSet");
        assertEquals(2, rows.size(), rows::toString);
    }

    /** LIMIT caps the groups returned; it used to be pushed down and cap the rows summed. */
    @Test
    void limitAppliesToGroupsNotRows() throws JsonProcessingException {
        JsonNode body = query("select type, sum(clicks) as total FROM testTable GROUP BY type LIMIT 1");
        assertEquals(10, firstRow(body).get("total").asInt(),
                "LIMIT was applied to input rows instead of output groups");
    }

    @Test
    void rejectsUnsupportedShapesWith400() {
        for (String statement : new String[]{
                "select count(pk) as c FROM testTable t",
                "select count(pk) as c FROM testTable -- trailing comment"}) {
            HttpResponse<String> response = send(statement);
            assertEquals(400, response.getStatus(),
                    () -> "expected 400 for [" + statement + "], got " + response.getStatus()
                            + ": " + response.getBody());
        }
    }

    /** ORDER BY on a SELECT alias works since the PartiQL 1.x upgrade (it was a 400 before). */
    @Test
    void ordersBySelectAlias() throws JsonProcessingException {
        JsonNode row = firstRow(query(
                "select type, sum(clicks) as total FROM testTable GROUP BY type ORDER BY total DESC"));
        assertEquals(10, row.get("total").asInt());
    }

    /** A data-dependent evaluation failure is the caller's to fix: 400, not 500. */
    @Test
    void aggregationEvaluationErrorsAre400() {
        HttpResponse<String> response = send("select sum(type) as s FROM testTable");
        assertEquals(400, response.getStatus(), response::getBody);
    }

    /** A GET must not be able to mutate or drop data. */
    @Test
    void rejectsNonSelectStatements() {
        for (String statement : new String[]{
                "DELETE FROM testTable WHERE pk='Record1'",
                "INSERT INTO testTable VALUE {'pk':'x'}",
                "UPDATE testTable SET clicks=99 WHERE pk='Record1'",
                "SELECT count(pk) FROM testTable; DELETE FROM testTable WHERE pk='Record1'"}) {
            HttpResponse<String> response = send(statement);
            assertEquals(400, response.getStatus(),
                    () -> "expected 400 for [" + statement + "], got " + response.getStatus()
                            + ": " + response.getBody());
        }
    }

    /** Nothing above reached the data store. */
    @Test
    void rejectedWritesLeaveDataIntact() throws JsonProcessingException {
        assertEquals(4, firstRow(query("SELECT COUNT(pk) as CNT FROM testTable")).get("CNT").asInt());
    }

    /** POST keeps the query, and its literal values, out of URLs and access logs. */
    @Test
    void acceptsTheQueryInAPostBody() throws JsonProcessingException {
        HttpResponse<String> response = Unirest
                .post(String.format("http://localhost:%d/query-aggregation", EXT.getLocalPort()))
                .header("Authorization", String.format("Basic %s", encoding))
                .header("Content-Type", "application/json")
                .body(MAPPER.writeValueAsString(java.util.Map.of(
                        "query", "select count(pk) as CNT FROM testTable where pk = 'Record1'")))
                .asString();
        assertEquals(200, response.getStatus(), response::getBody);
        assertEquals(1, firstRow(MAPPER.readTree(response.getBody())).get("CNT").asInt());
    }

    @Test
    void rejectsAPostWithoutAQuery() {
        HttpResponse<String> response = Unirest
                .post(String.format("http://localhost:%d/query-aggregation", EXT.getLocalPort()))
                .header("Authorization", String.format("Basic %s", encoding))
                .header("Content-Type", "application/json")
                .body("{}")
                .asString();
        assertEquals(422, response.getStatus(), response::getBody);
    }

    @Test
    void rejectsWrongSecretsAndUnknownUsers() {
        for (String credentials : new String[]{
                "small-query-app:test-only-secret-large-0002",   // another user's secret
                "small-query-app:test-only-secret-small-000",    // prefix of the right secret
                "nobody:test-only-secret-small-0001"}) {
            HttpResponse<String> response = Unirest
                    .get(url("SELECT COUNT(pk) as CNT FROM testTable"))
                    .header("Authorization", "Basic " + Base64.getEncoder()
                            .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                    .asString();
            assertEquals(401, response.getStatus(), () -> "accepted " + credentials);
        }
    }

    /**
     * End to end through DynamoDB, Ion, PartiQL, Jackson and the HTTP response: integers beyond
     * 64 bits, and decimals beyond double precision, must come back digit for digit.
     */
    @Test
    void preservesLargeIntegersAndDecimalsExactly() throws JsonProcessingException {
        var ddb = new ConnectionDDBFactory(new AppConfiguration()).buildDDBLocalSession();
        String table = "bigNumbers";
        DDBUtils.deleteTableIfExists(ddb, table);
        DDBUtils.createTable(ddb, table, "pk");
        DDBUtils.putItemInTable(ddb, table, "b1", "12345678901234567890", "big");
        DDBUtils.putItemInTable(ddb, table, "b2", "1", "big");
        DDBUtils.putItemInTable(ddb, table, "d1", "0.12345678901234567890123", "dec");

        HttpResponse<String> response = send(
                "select type, sum(clicks) as total FROM bigNumbers GROUP BY type ORDER BY type");
        assertEquals(200, response.getStatus(), response::getBody);
        // Compared as text: a JSON parser in the test could itself round the number.
        assertTrue(response.getBody().contains("\"total\":12345678901234567891"),
                () -> "large integer sum was not exact: " + response.getBody());
        assertTrue(response.getBody().contains("0.12345678901234567890123"),
                () -> "high-precision decimal was rounded: " + response.getBody());
    }

    /**
     * The path form cannot carry a '/' (Jetty rejects %2F in a path as ambiguous, with an HTML
     * 400); the query-string form can.
     */
    @Test
    void acceptsSlashesInTheQueryStringForm() throws JsonProcessingException {
        String query = "select sum(clicks / 2) as half FROM testTable where pk <> 'a/b'";
        HttpResponse<String> response = Unirest
                .get(String.format("http://localhost:%d/query-aggregation", EXT.getLocalPort()))
                .queryString("query", query)
                .header("Authorization", String.format("Basic %s", encoding))
                .asString();
        assertEquals(200, response.getStatus(), response::getBody);
        // clicks 1..4, integer division by 2: 0 + 1 + 1 + 2
        assertEquals(4, firstRow(MAPPER.readTree(response.getBody())).get("half").asInt(), response::getBody);
    }

    @Test
    void rejectsAnEmptyQueryStringForm() {
        HttpResponse<String> response = Unirest
                .get(String.format("http://localhost:%d/query-aggregation", EXT.getLocalPort()))
                .header("Authorization", String.format("Basic %s", encoding))
                .asString();
        assertEquals(400, response.getStatus(), response::getBody);
    }

    /** A plain projection's LIMIT is applied while reading, not after reading every row. */
    @Test
    void readsNoMoreRowsThanAPlainLimitNeeds() throws JsonProcessingException {
        JsonNode body = query("select pk, clicks FROM testTable LIMIT 2");
        assertEquals(2, body.get("response").get(0).get("resultSet").size(), body::toString);
        assertEquals(2, body.get("stats").get("rowsRetrieved").asInt(), body::toString);
    }

    /** COUNT(*) now fetches only the hash key, so wide items do not inflate the payload. */
    @Test
    void countStarFetchesOnlyTheKey() throws JsonProcessingException {
        var ddb = new ConnectionDDBFactory(new AppConfiguration()).buildDDBLocalSession();
        String table = "wideItems";
        DDBUtils.deleteTableIfExists(ddb, table);
        DDBUtils.createTable(ddb, table, "pk");
        int items = 200;
        DDBUtils.putBulkItems(ddb, table, items, 2048);

        JsonNode body = query("select count(*) as n FROM wideItems");
        assertEquals(items, firstRow(body).get("n").asInt(), body::toString);
        long payload = body.get("stats").get("payloadSizeBytes").asLong();
        // Each key row is {"pk":"bulk-00000"}: 18 bytes. Whole items would be over 2 KB each.
        assertTrue(payload < items * 50L, () -> "fetched whole items: " + payload + " bytes");
    }

    @Test
    void reportsRowsAndConsumedCapacity() throws JsonProcessingException {
        JsonNode stats = query("select count(pk) as CNT FROM testTable").get("stats");
        assertEquals(4, stats.get("rowsRetrieved").asInt(), stats::toString);
        assertTrue(stats.get("payloadSizeBytes").asLong() > 0, stats::toString);
        // DynamoDB Local does not return ConsumedCapacity for ExecuteStatement (it does for Scan),
        // so its presence cannot be asserted here; DynamodbExtractorTest covers the request and
        // the summation. When it is reported it must be a positive number.
        if (stats.has("consumedReadCapacityUnits")) {
            assertTrue(stats.get("consumedReadCapacityUnits").asDouble() > 0, stats::toString);
        }
    }

    @Test
    void assignsARequestId() {
        HttpResponse<String> response = send("select count(pk) as CNT FROM testTable");
        String id = response.getHeaders().getFirst("X-Request-Id");
        assertTrue(id != null && !id.isBlank(), "no X-Request-Id header");
    }

    @Test
    void propagatesAPlainCallerRequestIdAndReplacesAnUnsafeOne() {
        HttpResponse<String> plain = Unirest.get(url("select count(pk) as CNT FROM testTable"))
                .header("Authorization", String.format("Basic %s", encoding))
                .header("X-Request-Id", "trace-abc.123")
                .asString();
        assertEquals("trace-abc.123", plain.getHeaders().getFirst("X-Request-Id"));

        // Would otherwise be written verbatim into logs: a forged log line.
        HttpResponse<String> unsafe = Unirest.get(url("select count(pk) as CNT FROM testTable"))
                .header("Authorization", String.format("Basic %s", encoding))
                .header("X-Request-Id", "x INFO forged-log-entry")
                .asString();
        String replaced = unsafe.getHeaders().getFirst("X-Request-Id");
        assertTrue(replaced.matches("[0-9a-f-]{36}"), () -> "unsafe id was kept: " + replaced);
    }

    @Test
    void publishesQueryMetrics() throws JsonProcessingException {
        query("select count(pk) as CNT FROM testTable");

        HttpResponse<String> metrics = Unirest
                .get(String.format("http://127.0.0.1:%d/metrics", EXT.getAdminPort()))
                .asString();
        JsonNode root = MAPPER.readTree(metrics.getBody());
        assertTrue(root.get("histograms").get("com.aws.aqp.core.Aggregator.rows").get("count").asLong() > 0);
        assertTrue(root.get("timers").get("com.aws.aqp.core.Aggregator.retrieve").get("count").asLong() > 0);
        assertTrue(root.get("timers").fieldNames().hasNext());
        boolean resourceTimer = false;
        for (var it = root.get("timers").fieldNames(); it.hasNext(); ) {
            resourceTimer |= it.next().startsWith("com.aws.aqp.api.QueryRESTController.");
        }
        assertTrue(resourceTimer, "no @Timed metric for the resource");
        assertEquals("PARTIQL",
                root.get("gauges").get("com.aws.aqp.core.Aggregator.engine").get("value").asText(),
                "the engine gauge must name the configured aggregation engine");
    }

    /**
     * The ALB target health check (ECS/Fargate) can only reach the application port, where
     * everything else requires credentials; /ping must answer without them, cheaply, uncached.
     */
    @Test
    void pingAnswersWithoutCredentials() throws JsonProcessingException {
        HttpResponse<String> response = Unirest
                .get(String.format("http://localhost:%d/ping", EXT.getLocalPort()))
                .asString();
        assertEquals(200, response.getStatus(), response::getBody);
        assertEquals("ok", MAPPER.readTree(response.getBody()).get("status").asText());
        assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
    }

    @Test
    void requiresAuthentication() {
        HttpResponse<String> response = Unirest
                .get(url("SELECT COUNT(pk) as CNT FROM testTable"))
                .asString();
        assertEquals(401, response.getStatus(),
                "unauthenticated requests must be rejected, and the exception mappers must not "
                        + "intercept the auth filter's response");
    }
}
