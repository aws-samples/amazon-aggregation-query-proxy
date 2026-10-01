// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;

public class Stats {

    private long elapsedTimeToRetrieveDataInMs;
    private long elapsedTimeToAggregateDataInMs;
    private long payloadSizeBytes;
    private long rowsRetrieved;
    /** DynamoDB only; omitted from the response when the store does not report it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double consumedReadCapacityUnits;

    public Stats(long elapsedTimeToRetrieveDataInMs,
                 long elapsedTimeToAggregateInMs,
                 long payloadSizeBytes,
                 long rowsRetrieved,
                 Double consumedReadCapacityUnits) {
        this.elapsedTimeToAggregateDataInMs = elapsedTimeToAggregateInMs;
        this.elapsedTimeToRetrieveDataInMs = elapsedTimeToRetrieveDataInMs;
        this.payloadSizeBytes = payloadSizeBytes;
        this.rowsRetrieved = rowsRetrieved;
        this.consumedReadCapacityUnits = consumedReadCapacityUnits;
    }

    public long getElapsedTimeToRetrieveDataInMs() {
        return elapsedTimeToRetrieveDataInMs;
    }

    public void setElapsedTimeToRetrieveDataInMs(long elapsedTimeToRetrieveDataInMs) {
        this.elapsedTimeToRetrieveDataInMs = elapsedTimeToRetrieveDataInMs;
    }

    public long getElapsedTimeToAggregateDataInMs() {
        return elapsedTimeToAggregateDataInMs;
    }

    public void setElapsedTimeToAggregateDataInMs(long elapsedTimeToAggregateDataInMs) {
        this.elapsedTimeToAggregateDataInMs = elapsedTimeToAggregateDataInMs;
    }

    public long getPayloadSizeBytes() {
        return payloadSizeBytes;
    }

    public void setPayloadSizeBytes(long payloadSizeBytes) {
        this.payloadSizeBytes = payloadSizeBytes;
    }

    public long getRowsRetrieved() {
        return rowsRetrieved;
    }

    public void setRowsRetrieved(long rowsRetrieved) {
        this.rowsRetrieved = rowsRetrieved;
    }

    public Double getConsumedReadCapacityUnits() {
        return consumedReadCapacityUnits;
    }

    public void setConsumedReadCapacityUnits(Double consumedReadCapacityUnits) {
        this.consumedReadCapacityUnits = consumedReadCapacityUnits;
    }

    @Override
    public String toString() {
        return ReflectionToStringBuilder.toString(this);
    }
}
