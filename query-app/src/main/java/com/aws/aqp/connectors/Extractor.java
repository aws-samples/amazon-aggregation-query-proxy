// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.connectors;

import com.aws.aqp.core.sql.QueryPlan;

/**
 * Runs the push-down half of a query. Implementations are shared by all requests and must hold
 * no per-query state. They do not own their client; the application does.
 */
public abstract class Extractor {

    public Extractor() {
    }

    /**
     * Runs the plan's push-down half and returns the rows, as {@code {"resultSet":[...]}}, with
     * what it cost to read them. Each implementation renders the push-down statement in its own
     * dialect.
     */
    public abstract ExtractResult execute(QueryPlan plan) throws InterruptedException;

}
