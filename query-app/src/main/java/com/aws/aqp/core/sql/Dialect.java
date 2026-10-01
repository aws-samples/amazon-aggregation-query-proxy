// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core.sql;

/** The data store's query language, which decides how a push-down statement is rendered. */
public enum Dialect {
    /** Amazon Keyspaces / Cassandra: {@code SELECT JSON}, {@code LIMIT}, {@code ALLOW FILTERING}. */
    CQL,
    /** DynamoDB PartiQL: no {@code SELECT JSON} and no {@code LIMIT}; row caps are applied while paging. */
    DYNAMODB_PARTIQL
}
