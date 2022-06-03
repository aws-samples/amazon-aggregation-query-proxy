// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import org.apache.commons.lang3.builder.ReflectionToStringBuilder;

public class Stats {

    private long elapsedTimeToRetrieveDataInMs;
    private long elapsedTimeToAggregateDataInMs;
    private long payloadSizeBytes;

    public Stats(long elapsedTimeToRetrieveDataInMs,
                 long elapsedTimeToAggregateInMs,
                 long payloadSizeBytes) {
        this.elapsedTimeToAggregateDataInMs = elapsedTimeToAggregateInMs;
        this.elapsedTimeToRetrieveDataInMs = elapsedTimeToRetrieveDataInMs;
        this.payloadSizeBytes = payloadSizeBytes;
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

    @Override
    public String toString() {
        return ReflectionToStringBuilder.toString(this);
    }
}
