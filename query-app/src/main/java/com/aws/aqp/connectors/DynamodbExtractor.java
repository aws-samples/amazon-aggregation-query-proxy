// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.connectors;

import com.aws.aqp.application.AppConfiguration;
import com.aws.aqp.core.JsonHelper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;

public class DynamodbExtractor extends Extractor {

    private DynamoDbClient dynamoDbClient;
    private final ConnectionDDBFactory connectionDDBFactory;
    private final AppConfiguration appConfiguration;

    private static ExecuteStatementRequest createExecuteStatementRequest(String query) {
        ExecuteStatementRequest request = ExecuteStatementRequest.builder().
                statement(query).
                build();
        return request;
    }

    public DynamodbExtractor(AppConfiguration appConfiguration) {
        connectionDDBFactory = new ConnectionDDBFactory(appConfiguration);
        this.appConfiguration = appConfiguration;
    }

    private static void handleCommonErrors(Exception exception) {
        try {
            throw exception;
        } catch (InternalServerErrorException isee) {
            System.out.println("Internal Server Error, generally safe to retry with exponential back-off. Error: " + isee.getMessage());
        } catch (RequestLimitExceededException rlee) {
            System.out.println("Throughput exceeds the current throughput limit for your account, increase account level throughput before " +
                    "retrying. Error: " + rlee.getMessage());
        } catch (ProvisionedThroughputExceededException ptee) {
            System.out.println("Request rate is too high. If you're using a custom retry strategy make sure to retry with exponential back-off. " +
                    "Otherwise consider reducing frequency of requests or increasing provisioned capacity for your table or secondary index. Error: " +
                    ptee.getMessage());
        } catch (ResourceNotFoundException rnfe) {
            System.out.println("One of the tables was not found, verify table exists before retrying. Error: " + rnfe.getMessage());
        } catch (Exception e) {
            System.out.println("An exception occurred, investigate and configure retry strategy. Error: " + e.getMessage());
        }
    }

    private static void handleExecuteStatementErrors(Exception exception) {
        try {
            throw exception;
        } catch (ConditionalCheckFailedException ccfe) {
            System.out.println("Condition check specified in the operation failed, review and update the condition " +
                    "check before retrying. Error: " + ccfe.getMessage());
        } catch (TransactionConflictException tce) {
            System.out.println("Operation was rejected because there is an ongoing transaction for the item, generally " +
                    "safe to retry with exponential back-off. Error: " + tce.getMessage());
        } catch (ItemCollectionSizeLimitExceededException icslee) {
            System.out.println("An item collection is too large, you\'re using Local Secondary Index and exceeded " +
                    "size limit of items per partition key. Consider using Global Secondary Index instead. Error: " + icslee.getMessage());
        } catch (Exception e) {
            handleCommonErrors(e);
        }
    }

    @Override
    public String execute(String query) {
        ExecuteStatementResponse resultSet = null;
        Set<String> dbGenericResult = new ConcurrentSkipListSet<>();

        try {
            ExecuteStatementRequest executeStatementRequest = createExecuteStatementRequest(query.replace("json", ""));
            resultSet = dynamoDbClient.executeStatement(executeStatementRequest);
        } catch (Exception e) {
            handleExecuteStatementErrors(e);
        }

        resultSet.items().parallelStream().forEach(item ->
            dbGenericResult.add(JsonHelper.toJson(item).toString()));

        return String.format("{\"resultSet\":[%s]}",
                dbGenericResult.parallelStream().collect(Collectors.joining(",")));
    }

    @Override
    public void start() {
        if (!appConfiguration.getLocalDDB())
            dynamoDbClient = connectionDDBFactory.buildDDBSession();
        else
            dynamoDbClient = connectionDDBFactory.buildDDBLocalSession();
    }

    @Override
    public void stop() {
        dynamoDbClient.close();
    }
}
