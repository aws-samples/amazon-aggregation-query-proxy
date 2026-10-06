// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

/**
 * Selectable {@link AggregationEngine} implementations. A value is only added here once its
 * engine ships, so the configuration error for a wrong value never names an engine that would
 * itself fail to start.
 */
public enum EngineType {
    /** The default: {@link PartiQLEngine}. */
    PARTIQL,
    /** {@link DuckDbEngine}: embedded columnar SQL over JDBC with a native library. */
    DUCKDB
}
