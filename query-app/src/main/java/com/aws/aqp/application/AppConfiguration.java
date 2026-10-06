// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.application;

import com.aws.aqp.connectors.DatabaseType;
import com.aws.aqp.core.EngineType;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.core.Configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class AppConfiguration extends Configuration {

    @JsonProperty
    private String serviceName = "KEYSPACES";

    @JsonProperty
    private String pathToKeyspacesConfigFile = "../conf";

    @JsonProperty
    private String awsRegion = "us-east-1";

    @JsonProperty Boolean localDDB = false;

    /**
     * Removed. Was one secret shared by two hard-coded users, defaulting to "secret". Still
     * declared only so an old config fails with a message explaining the replacement, rather
     * than with Jackson's generic "Unrecognized field".
     */
    @JsonProperty
    private String clientSecret;

    /**
     * API clients, keyed by user name. Supply secrets through environment variables, for example
     * {@code secret: ${AQP_REPORTING_SECRET}}, so they stay out of the config file and the
     * container image. There is no default: the application will not start without at least one
     * user.
     */
    @Valid
    @JsonProperty
    private Map<String, ClientCredentials> users = new LinkedHashMap<>();

    /**
     * Engine evaluating the aggregation half of each query (SELECT list, GROUP BY, HAVING,
     * ORDER BY) over the rows fetched from the data store.
     */
    @JsonProperty
    private String aggregationEngine = "PARTIQL";

    /**
     * Caps on the push-down result set. Aggregation happens in this node's heap, so without a
     * cap an unbounded query is an OOM. Enforced while pages are read, before the whole result
     * set is buffered.
     */
    @Min(1)
    @JsonProperty
    private long maxRows = 100_000L;

    @Min(1)
    @JsonProperty
    private long maxResultBytes = 64L * 1024 * 1024;

    /**
     * How long the data-store read of one query may take, across all pages, before the caller
     * gets a 504. The aggregation phase runs after this and is additional (typically
     * milliseconds). On ECS/Fargate keep it well under the task's stopTimeout (max 120 seconds
     * there), or in-flight queries are cut mid-answer on every deployment.
     */
    @Min(1)
    @JsonProperty
    private long queryTimeoutSeconds = 360;

    /**
     * Optional table for the DynamoDB startup health check. When set, the probe is
     * DescribeTable on this table, so the task role needs no account-wide ListTables.
     */
    @JsonProperty
    private String dynamoHealthCheckTable;

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    /**
     * Parses {@code serviceName} into a {@link DatabaseType}, case-insensitively.
     * <p>
     * This used to be a case-sensitive {@code equals} against the enum name in
     * {@link App}, so the shipped default of "Keyspaces" matched neither branch, no extractor
     * was built, and startup failed later with an unexplained NullPointerException.
     */
    public DatabaseType getDatabaseType() {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException(String.format(
                    "serviceName must be set to one of %s", Arrays.toString(DatabaseType.values())));
        }
        try {
            return DatabaseType.valueOf(serviceName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(String.format(
                    "Unknown serviceName '%s'; expected one of %s",
                    serviceName, Arrays.toString(DatabaseType.values())), e);
        }
    }

    /** Parses {@code aggregationEngine} case-insensitively, like {@link #getDatabaseType()}. */
    public EngineType getAggregationEngine() {
        if (aggregationEngine == null || aggregationEngine.isBlank()) {
            throw new IllegalArgumentException(String.format(
                    "aggregationEngine must be one of %s", Arrays.toString(EngineType.values())));
        }
        try {
            return EngineType.valueOf(aggregationEngine.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(String.format(
                    "Unknown aggregationEngine '%s'; expected one of %s",
                    aggregationEngine, Arrays.toString(EngineType.values())), e);
        }
    }

    public void setAggregationEngine(String aggregationEngine) {
        this.aggregationEngine = aggregationEngine;
    }

    public String getPathToKeyspacesConfigFile() {
        return pathToKeyspacesConfigFile;
    }

    public void setPathToKeyspacesConfigFile(String pathToKeyspacesConfigFile) {
        this.pathToKeyspacesConfigFile = pathToKeyspacesConfigFile;
    }

    /**
     * Normalized to lower case: AWS region identifiers are lower case, and the previous default
     * of "US-EAST-1" was passed through verbatim to {@code Region.of}, producing an endpoint
     * host of {@code dynamodb.US-EAST-1.amazonaws.com}.
     */
    public String getAwsRegion() {
        return awsRegion == null ? null : awsRegion.trim().toLowerCase(Locale.ROOT);
    }

    public void setAwsRegion(String awsRegion) {
        this.awsRegion = awsRegion;
    }

    public Boolean getLocalDDB() {
        return localDDB;
    }

    public void setLocalDDB(Boolean localDDB) {
        this.localDDB = localDDB;
    }

    public long getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(long queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    /** Blank means unset: an empty-string env default must behave like absence. */
    public String getDynamoHealthCheckTable() {
        return dynamoHealthCheckTable == null || dynamoHealthCheckTable.isBlank()
                ? null : dynamoHealthCheckTable.trim();
    }

    public void setDynamoHealthCheckTable(String dynamoHealthCheckTable) {
        this.dynamoHealthCheckTable = dynamoHealthCheckTable;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public Map<String, ClientCredentials> getUsers() {
        return users;
    }

    public void setUsers(Map<String, ClientCredentials> users) {
        this.users = users;
    }

    public long getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(long maxRows) {
        this.maxRows = maxRows;
    }

    public long getMaxResultBytes() {
        return maxResultBytes;
    }

    public void setMaxResultBytes(long maxResultBytes) {
        this.maxResultBytes = maxResultBytes;
    }
}
