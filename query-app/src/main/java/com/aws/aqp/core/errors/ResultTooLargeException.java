// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core.errors;

/**
 * The result set exceeded the configured row or byte budget. Aggregation happens in this
 * node's heap, so the budget is what stops an unbounded query from causing an OOM.
 * Mapped to HTTP 413.
 */
public class ResultTooLargeException extends RuntimeException {

    public ResultTooLargeException(String message) {
        super(message);
    }
}
