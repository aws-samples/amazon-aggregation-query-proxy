// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.api;

import com.aws.aqp.core.errors.InvalidQueryException;
import com.aws.aqp.core.errors.QueryTimeoutException;
import com.aws.aqp.core.errors.ResultTooLargeException;
import com.datastax.oss.driver.api.core.DriverException;
import com.datastax.oss.driver.api.core.DriverTimeoutException;
import com.datastax.oss.driver.api.core.servererrors.QueryValidationException;
import com.datastax.oss.driver.api.core.servererrors.ReadTimeoutException;
import com.datastax.oss.driver.api.core.servererrors.UnauthorizedException;
import org.partiql.lang.SqlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.util.Map;
import java.util.Set;

/**
 * Turns query failures into a status the caller can act on.
 * <p>
 * Previously the DynamoDB extractor caught every exception, printed it to stdout, and then
 * dereferenced a null response — so throttling, a missing table, bad syntax and expired
 * credentials all reached the caller as an opaque {@code 500 NullPointerException}, with the
 * real cause visible only on the server's stdout.
 * <p>
 * These are deliberately per-type rather than one {@code ExceptionMapper<Throwable>}: a
 * {@code Throwable} mapper competes with Dropwizard's own {@code LoggingExceptionMapper} and
 * would intercept {@code WebApplicationException}, turning the auth filter's 401 into a 500.
 */
public final class AqpExceptionMappers {

    private static final Logger LOGGER = LoggerFactory.getLogger(AqpExceptionMappers.class);

    private AqpExceptionMappers() {
    }

    public static class InvalidQuery implements ExceptionMapper<InvalidQueryException> {
        @Override
        public Response toResponse(InvalidQueryException e) {
            return error(Response.Status.BAD_REQUEST.getStatusCode(), e.getMessage());
        }
    }

    public static class ResultTooLarge implements ExceptionMapper<ResultTooLargeException> {
        @Override
        public Response toResponse(ResultTooLargeException e) {
            LOGGER.warn("Rejected an oversized result set: {}", e.getMessage());
            return error(Response.Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode(), e.getMessage());
        }
    }

    public static class QueryTimeout implements ExceptionMapper<QueryTimeoutException> {
        @Override
        public Response toResponse(QueryTimeoutException e) {
            LOGGER.warn("Query timed out: {}", e.getMessage());
            return error(Response.Status.GATEWAY_TIMEOUT.getStatusCode(), e.getMessage());
        }
    }

    /**
     * PartiQL failed to compile or evaluate the aggregation step — for example SUM over a string
     * attribute. That is a property of the query and its data, not a server fault.
     */
    public static class Aggregation implements ExceptionMapper<SqlException> {
        @Override
        public Response toResponse(SqlException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            int newline = message.indexOf('\n');
            String firstLine = (newline < 0 ? message : message.substring(0, newline)).trim();
            LOGGER.info("Aggregation step rejected the query: {}", firstLine);
            return error(Response.Status.BAD_REQUEST.getStatusCode(),
                    "The aggregation step could not evaluate the query: " + firstLine);
        }
    }

    /**
     * Amazon Keyspaces / CQL driver errors. Jersey picks the nearest supertype, so this covers
     * every driver exception. Previously none were mapped and all surfaced as 500s, including
     * the caller's own mistakes such as filtering on a non-key column without ALLOW FILTERING.
     */
    public static class Cql implements ExceptionMapper<DriverException> {
        @Override
        public Response toResponse(DriverException e) {
            if (e instanceof UnauthorizedException) {
                // The proxy's own role lacks permission. Not the caller's to fix, and the
                // message names the role, so it stays in the server log.
                LOGGER.error("Amazon Keyspaces denied the proxy's role: {}", e.getMessage());
                return error(Response.Status.BAD_GATEWAY.getStatusCode(),
                        "The data store could not serve the query.");
            }
            if (e instanceof QueryValidationException) {
                // The caller's query: syntax errors, invalid queries, unknown tables.
                return error(Response.Status.BAD_REQUEST.getStatusCode(), e.getMessage());
            }
            if (e instanceof DriverTimeoutException || e instanceof ReadTimeoutException) {
                LOGGER.warn("Amazon Keyspaces timed out", e);
                return error(Response.Status.GATEWAY_TIMEOUT.getStatusCode(),
                        "Amazon Keyspaces did not respond in time.");
            }
            LOGGER.warn("Amazon Keyspaces could not serve the query", e);
            return error(Response.Status.BAD_GATEWAY.getStatusCode(),
                    "The data store could not serve the query.");
        }
    }

    /**
     * Also covers every DynamoDbException subclass, since Jersey picks the nearest supertype.
     * <p>
     * Classified by error code, not HTTP status. DynamoDB answers 400 for the proxy's own faults
     * too — expired or invalid credentials, missing IAM permissions — and the message for those
     * names the account and role ARN. Treating every 4xx as the caller's error blamed the caller
     * and handed them those details. Only errors in the caller's statement now return the
     * service message; everything else gets a generic body and is logged in full.
     */
    public static class DataStore implements ExceptionMapper<DynamoDbException> {

        /** Error codes caused by the caller's statement, whose message helps them fix it. */
        private static final Set<String> CALLER_ERRORS = Set.of("ValidationException");
        private static final Set<String> THROTTLING = Set.of(
                "ProvisionedThroughputExceededException", "RequestLimitExceeded", "ThrottlingException");

        @Override
        public Response toResponse(DynamoDbException e) {
            String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();

            if (e instanceof ProvisionedThroughputExceededException
                    || e instanceof RequestLimitExceededException
                    || THROTTLING.contains(code)) {
                LOGGER.warn("Throughput exceeded; retry with exponential back-off", e);
                return error(429, "Request rate exceeded the table or account throughput limit. "
                        + "Retry with exponential back-off.");
            }
            if (e instanceof ResourceNotFoundException) {
                return error(Response.Status.NOT_FOUND.getStatusCode(),
                        "Table not found. Verify the table name.");
            }
            if (CALLER_ERRORS.contains(code)) {
                // errorMessage() is the service's explanation alone, without the SDK's
                // "(Service: ..., Request ID: ...)" suffix that getMessage() appends.
                return error(Response.Status.BAD_REQUEST.getStatusCode(), e.awsErrorDetails().errorMessage());
            }
            LOGGER.error("DynamoDB could not serve the query (error code {})", code, e);
            return error(Response.Status.BAD_GATEWAY.getStatusCode(),
                    "The data store could not serve the query.");
        }
    }

    /**
     * Failures inside the AWS SDK rather than at the service: call and attempt timeouts,
     * credential loading, networking. These are not DynamoDbExceptions, had no mapper, and so
     * surfaced as opaque 500s. Details can include credential-provider and role information, so
     * they are logged, not returned.
     */
    public static class SdkClient implements ExceptionMapper<SdkClientException> {
        @Override
        public Response toResponse(SdkClientException e) {
            if (e instanceof ApiCallTimeoutException || e instanceof ApiCallAttemptTimeoutException) {
                LOGGER.warn("DynamoDB call timed out", e);
                return error(Response.Status.GATEWAY_TIMEOUT.getStatusCode(),
                        "DynamoDB did not respond in time.");
            }
            LOGGER.error("AWS SDK client failure", e);
            return error(Response.Status.BAD_GATEWAY.getStatusCode(),
                    "The data store could not serve the query.");
        }
    }

    private static Response error(int status, String message) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", message == null ? "Unknown error." : message))
                .build();
    }
}
