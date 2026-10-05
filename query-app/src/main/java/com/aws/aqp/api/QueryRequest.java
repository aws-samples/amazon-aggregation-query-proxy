// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /query-aggregation}: {@code {"query": "SELECT ..."}}. */
public class QueryRequest {

    /** Generous for any real aggregation query; stops a multi-megabyte body reaching the parser. */
    public static final int MAX_QUERY_LENGTH = 64 * 1024;

    @NotBlank
    @Size(max = MAX_QUERY_LENGTH)
    @JsonProperty
    private String query;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }
}
