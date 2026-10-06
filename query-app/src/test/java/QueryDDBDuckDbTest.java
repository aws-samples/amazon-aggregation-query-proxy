// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

import com.aws.aqp.application.App;
import com.aws.aqp.application.AppConfiguration;
import com.aws.aqp.connectors.ConnectionDDBFactory;
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
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTTP surface with {@code aggregationEngine: DUCKDB}, against DynamoDB Local: the full
 * stack — push-down, DuckDB load and evaluation, response envelope, error mapping, metrics —
 * driven through the API exactly as QueryDDBTest drives the default engine.
 */
@ExtendWith(DropwizardExtensionsSupport.class)
@Timeout(value = 2, unit = TimeUnit.MINUTES)
@EnabledIf("dynamoDbLocalIsReachable")
class QueryDDBDuckDbTest {

    private static final String TABLE = "duckTable";
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            ResourceHelpers.resourceFilePath("keyspaces-aggregation-query-proxy.yaml"),
            ConfigOverride.config("aggregationEngine", "DUCKDB"));

    private static String authorization;

    @BeforeAll
    static void setup() {
        authorization = "Basic " + Base64.getEncoder().encodeToString(
                String.format("small-query-app:%s",
                        EXT.getConfiguration().getUsers().get("small-query-app").getSecret())
                        .getBytes(StandardCharsets.UTF_8));

        var ddb = new ConnectionDDBFactory(new AppConfiguration()).buildDDBLocalSession();
        DDBUtils.deleteTableIfExists(ddb, TABLE);
        DDBUtils.createTable(ddb, TABLE, "pk");
        DDBUtils.putItemInTable(ddb, TABLE, "r1", "1", "test");
        DDBUtils.putItemInTable(ddb, TABLE, "r2", "2", "test");
        DDBUtils.putItemInTable(ddb, TABLE, "r3", "3", "test");
        DDBUtils.putItemInTable(ddb, TABLE, "r4", "12345678901234567890", "big");
    }

    private static HttpResponse<String> post(String query) throws JsonProcessingException {
        return Unirest.post(String.format("http://localhost:%d/query-aggregation", EXT.getLocalPort()))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .body(MAPPER.writeValueAsString(Map.of("query", query)))
                .asString();
    }

    @Test
    void aggregatesWithGroupByThroughTheFullStack() throws JsonProcessingException {
        HttpResponse<String> response = post(
                "select type, count(pk) as c, sum(clicks) as s FROM duckTable GROUP BY type ORDER BY type");
        assertEquals(200, response.getStatus(), response::getBody);
        JsonNode rows = MAPPER.readTree(response.getBody()).get("response").get(0).get("resultSet");
        assertEquals(2, rows.size(), rows::toString);
        assertEquals("big", rows.get(0).get("type").asText());
        assertEquals("test", rows.get(1).get("type").asText());
        assertEquals(6, rows.get(1).get("s").asInt());
    }

    /** Exactness end to end: DynamoDB, JSON, DuckDB appender, HTTP — digit for digit. */
    @Test
    void preservesLargeIntegersExactly() throws JsonProcessingException {
        HttpResponse<String> response = post("select sum(clicks) as total FROM duckTable");
        assertEquals(200, response.getStatus(), response::getBody);
        assertTrue(response.getBody().contains("12345678901234567896"),
                () -> "large integer sum was not exact: " + response.getBody());
    }

    @Test
    void mapsDuckDbBinderErrorsTo400() throws JsonProcessingException {
        HttpResponse<String> response = post("select sum(type) as s FROM duckTable");
        assertEquals(400, response.getStatus(), response::getBody);
        assertTrue(response.getBody().contains("could not evaluate"), response::getBody);
    }

    @Test
    void theEngineGaugeSaysDuckDb() throws JsonProcessingException {
        post("select count(pk) as c FROM duckTable");
        HttpResponse<String> metrics = Unirest
                .get(String.format("http://127.0.0.1:%d/metrics", EXT.getAdminPort()))
                .asString();
        JsonNode root = MAPPER.readTree(metrics.getBody());
        assertEquals("DUCKDB",
                root.get("gauges").get("com.aws.aqp.core.Aggregator.engine").get("value").asText());
    }
}
