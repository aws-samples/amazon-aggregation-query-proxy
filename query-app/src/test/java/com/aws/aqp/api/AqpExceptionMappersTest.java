// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.api;

import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException;
import com.datastax.oss.driver.api.core.servererrors.UnauthorizedException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AqpExceptionMappersTest {

    private static final String ACCOUNT = "123456789012";
    private static final String ROLE_ARN = "arn:aws:sts::" + ACCOUNT + ":assumed-role/aqp-task-role/i-0abc";

    private static DynamoDbException dynamoDb(int status, String code, String message) {
        return (DynamoDbException) DynamoDbException.builder()
                .statusCode(status)
                .message(message + " (Service: DynamoDb, Status Code: " + status + ", Request ID: RID)")
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage(message).build())
                .build();
    }

    private static String body(Response response) {
        return String.valueOf(((Map<?, ?>) response.getEntity()).get("error"));
    }

    /**
     * Server-side faults — the proxy's own credentials or permissions — are not the caller's to
     * fix, and the service message for them names the account and the role.
     */
    @ParameterizedTest
    @ValueSource(strings = {"AccessDeniedException", "UnrecognizedClientException",
            "ExpiredTokenException", "InvalidSignatureException", "MissingAuthenticationTokenException"})
    void reportsCredentialAndPermissionFailuresAsServerErrorsWithoutDetails(String code) {
        Response response = new AqpExceptionMappers.DataStore().toResponse(dynamoDb(400, code,
                "User: " + ROLE_ARN + " is not authorized to perform: dynamodb:PartiQLSelect"));

        assertEquals(502, response.getStatus());
        assertFalse(body(response).contains(ACCOUNT), () -> "leaked the account id: " + body(response));
        assertFalse(body(response).contains("arn:"), () -> "leaked an ARN: " + body(response));
    }

    @Test
    void reportsThrottlingAs429() {
        Response response = new AqpExceptionMappers.DataStore()
                .toResponse(dynamoDb(400, "ThrottlingException", "Rate of requests exceeds the allowed throughput."));
        assertEquals(429, response.getStatus());
    }

    /** The caller's own query error: 400 with the service's explanation, minus the SDK's suffix. */
    @Test
    void reportsInvalidStatementsAs400WithTheServiceMessage() {
        Response response = new AqpExceptionMappers.DataStore()
                .toResponse(dynamoDb(400, "ValidationException", "Statement wasn't well formed, can't be processed"));
        assertEquals(400, response.getStatus());
        assertEquals("Statement wasn't well formed, can't be processed", body(response));
    }

    @Test
    void reportsServiceFaultsAs502() {
        Response response = new AqpExceptionMappers.DataStore()
                .toResponse(dynamoDb(500, "InternalServerError", "Internal server error"));
        assertEquals(502, response.getStatus());
    }

    /** Keyspaces permission failures are likewise the proxy's role, not the caller's query. */
    @Test
    void reportsKeyspacesPermissionFailuresAsServerErrorsWithoutDetails() {
        Response response = new AqpExceptionMappers.Cql().toResponse(new UnauthorizedException(null,
                "User " + ROLE_ARN + " has no SELECT permission on <table ks.t> or any of its parents"));
        assertEquals(502, response.getStatus());
        assertFalse(body(response).contains(ACCOUNT), () -> "leaked the account id: " + body(response));
    }

    @Test
    void reportsInvalidCqlAs400() {
        Response response = new AqpExceptionMappers.Cql().toResponse(new InvalidQueryException(null,
                "Cannot execute this query as it might involve data filtering"));
        assertEquals(400, response.getStatus());
        assertTrue(body(response).contains("data filtering"), () -> body(response));
    }

    /** SDK client-side failures are not DynamoDbExceptions and previously had no mapper at all. */
    @Test
    void reportsSdkTimeoutsAs504() {
        assertEquals(504, new AqpExceptionMappers.SdkClient()
                .toResponse(ApiCallTimeoutException.create(30_000)).getStatus());
        assertEquals(504, new AqpExceptionMappers.SdkClient()
                .toResponse(ApiCallAttemptTimeoutException.create(5_000)).getStatus());
    }

    @Test
    void reportsOtherSdkClientFailuresAs502WithoutDetails() {
        Response response = new AqpExceptionMappers.SdkClient().toResponse(SdkClientException.create(
                "Unable to load credentials from any of the providers in the chain: " + ROLE_ARN));
        assertEquals(502, response.getStatus());
        assertFalse(body(response).contains("arn:"), () -> body(response));
    }
}
