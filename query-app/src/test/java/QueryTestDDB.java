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
import org.junit.jupiter.api.extension.ExtendWith;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import java.util.Base64;

@ExtendWith(DropwizardExtensionsSupport.class)
class QueryTestDDB {

    private static DropwizardAppExtension<AppConfiguration> EXT = new DropwizardAppExtension<>(
            App.class,
            ResourceHelpers.resourceFilePath("keyspaces-aggregation-query-proxy.yaml"));

    private static String encoding;

    @BeforeAll
    public static void setup() {
        encoding = Base64.getEncoder().encodeToString((String.format("small-query-app:%s", EXT.getConfiguration().getClientSecret()).getBytes()));
        ConnectionDDBFactory testConnection = new ConnectionDDBFactory(new AppConfiguration());
        var dynamoDB = testConnection.buildDDBLocalSession();
        String tableName = "testTable";
        String key = "pk";
        dynamoDB.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
        DDBUtils.createTable(dynamoDB, tableName, key);

        DDBUtils.putItemInTable(dynamoDB, tableName, "Record1", "1", "test");
        DDBUtils.putItemInTable(dynamoDB, tableName, "Record2", "2", "test");
        DDBUtils.putItemInTable(dynamoDB, tableName, "Record3", "3", "test");
        DDBUtils.putItemInTable(dynamoDB, tableName, "Record4", "4", "test");

    }

    @Test
    void selectCountUpperCase() throws JsonProcessingException {
        HttpResponse<String> response = Unirest.get(String.format("http://localhost:%d/query-aggregation/%s", EXT.getLocalPort(), "SELECT COUNT(pk) as CNT FROM testTable"))
                .header("Authorization", String.format("Basic %s", encoding))
                .asString();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode actualObj = mapper.readTree(response.getBody());
        assert (actualObj.get("response").get(0).get("resultSet").get(0).get("CNT").asInt() == 4);
    }

    @Test
    void selectCountLowerCase() throws JsonProcessingException {
        HttpResponse<String> response = Unirest.get(String.format("http://localhost:%d/query-aggregation/%s", EXT.getLocalPort(), "select count(pk) as CNT FROM testTable"))
                .header("Authorization", String.format("Basic %s", encoding))
                .asString();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode actualObj = mapper.readTree(response.getBody());
        assert (actualObj.get("response").get(0).get("resultSet").get(0).get("CNT").asInt() == 4);
    }

    @Test
    void selectCountGroupByCase() throws JsonProcessingException {
        HttpResponse<String> response = Unirest.get(String.format("http://localhost:%d/query-aggregation/%s", EXT.getLocalPort(), "select SUM(clicks) as cnt, type fROM testTable GROUP by type"))
                .header("Authorization", String.format("Basic %s", encoding))
                .asString();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode actualObj = mapper.readTree(response.getBody());
        assert (actualObj.get("response").get(0).get("resultSet").get(0).get("cnt").asInt() == 10);
        assert (actualObj.get("response").get(0).get("resultSet").get(0).get("type").asText().equals("test"));
    }

    @Test
    void selectCountWhereClause() throws JsonProcessingException {
        HttpResponse<String> response = Unirest.get(String.format("http://localhost:%d/query-aggregation/%s", EXT.getLocalPort(), "select count(pk) as CNT FROM testTable where pk = 'Record1' "))
                .header("Authorization", String.format("Basic %s", encoding))
                .asString();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode actualObj = mapper.readTree(response.getBody());
        assert (actualObj.get("response").get(0).get("resultSet").get(0).get("CNT").asInt() == 1);
    }

    @Test
    void selectCountWhereClauseIn() throws JsonProcessingException {
        HttpResponse<String> response = Unirest.get(String.format("http://localhost:%d/query-aggregation/%s", EXT.getLocalPort(), "select count(pk) as CNT FROM testTable where pk in ('Record1','Record2','Record3')"))
                .header("Authorization", String.format("Basic %s", encoding))
                .asString();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode actualObj = mapper.readTree(response.getBody());
        assert (actualObj.get("response").get(0).get("resultSet").get(0).get("CNT").asInt() == 3);
    }

    @Test
    void selectCountGroupByAndOtherColumns() throws JsonProcessingException {
        HttpResponse<String> response = Unirest.get(String.format("http://localhost:%d/query-aggregation/%s", EXT.getLocalPort(), "select type, count(pk) as CNT from testTable where pk in ('Record1','Record2','Record3','Record4') group by type"))
                .header("Authorization", String.format("Basic %s", encoding))
                .asString();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode actualObj = mapper.readTree(response.getBody());
        assert (actualObj.get("response").get(0).get("resultSet").get(0).get("CNT").asInt() == 4);
        assert (actualObj.get("response").get(0).get("resultSet").get(0).get("type").asText().equals("test"));
    }
}
