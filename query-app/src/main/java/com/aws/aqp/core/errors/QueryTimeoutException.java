// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core.errors;

/**
 * The data store did not return a complete result set within the allowed time.
 * Raised instead of silently returning the rows that happened to arrive.
 * Mapped to HTTP 504.
 */
public class QueryTimeoutException extends RuntimeException {

    public QueryTimeoutException(String message) {
        super(message);
    }
}
