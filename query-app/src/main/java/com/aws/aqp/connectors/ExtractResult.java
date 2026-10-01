// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.connectors;

/**
 * The push-down rows plus what it cost to read them.
 * <p>
 * The counts are taken while the rows are read, so reporting them does not require re-encoding
 * the whole payload afterwards, which is what the payload-size statistic used to do.
 */
public final class ExtractResult {

    private final String document;
    private final long rowCount;
    private final long byteCount;
    private final Double consumedReadCapacityUnits;

    /**
     * @param document                  rows as {@code {"resultSet":[...]}}
     * @param consumedReadCapacityUnits read capacity reported by the store, or {@code null} if the
     *                                  store does not report it (Amazon Keyspaces)
     */
    public ExtractResult(String document, long rowCount, long byteCount, Double consumedReadCapacityUnits) {
        this.document = document;
        this.rowCount = rowCount;
        this.byteCount = byteCount;
        this.consumedReadCapacityUnits = consumedReadCapacityUnits;
    }

    public String document() {
        return document;
    }

    public long rowCount() {
        return rowCount;
    }

    public long byteCount() {
        return byteCount;
    }

    public Double consumedReadCapacityUnits() {
        return consumedReadCapacityUnits;
    }
}
