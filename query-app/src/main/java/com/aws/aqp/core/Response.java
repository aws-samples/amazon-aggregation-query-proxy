// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Response {

    private Stats stats;
    private JsonNode response;
    ObjectMapper objectMapper = new ObjectMapper();

    public Response(Stats stats, JsonNode response) {
        this.stats = stats;
        this.response = response;
    }

    public Stats getStats() {
        return stats;
    }

    public void setStats(Stats stats) {
        this.stats = stats;
    }

    public JsonNode getResponse() throws JsonProcessingException {
        return response;
    }

    public void setResponse(JsonNode response) {
        this.response = response;
    }
}
