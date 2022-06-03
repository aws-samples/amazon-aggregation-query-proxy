// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

public class Response {

    private Stats stats;
    private String response;

    public Response(Stats stats, String response) {
        this.stats = stats;
        this.response = response;
    }

    public Stats getStats() {
        return stats;
    }

    public void setStats(Stats stats) {
        this.stats = stats;
    }

    public String getResponse() {
        return response.replace("_1", "resultSet");
    }

    public void setResponse(String response) {
        this.response = response;
    }
}
