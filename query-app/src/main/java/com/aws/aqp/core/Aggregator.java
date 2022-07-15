// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import com.aws.aqp.connectors.Extractor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;

import java.util.concurrent.TimeUnit;

public class Aggregator {

    private Extractor extractor;
    private ObjectMapper objectMapper;
    private static QueryTransformer queryTransformer = new QueryTransformer();

    public Aggregator(Extractor extractor) {
        this.extractor = extractor;
        this.objectMapper = new ObjectMapper();
    }

    public String getPlainQuery(String query) {
        return queryTransformer.getPlainQuery(query).left;
    }

     public String aggregate(String query) throws JsonProcessingException, InterruptedException {

         Stopwatch stopwatchPlainRequest = Stopwatch.createStarted();
         var plainResult = extractor.execute(getPlainQuery(query));
         stopwatchPlainRequest.stop();

         long plainResultElapsedTime = stopwatchPlainRequest.elapsed(TimeUnit.MILLISECONDS);
         Stopwatch stopwatchAggRequest = Stopwatch.createStarted();
         var aggregatedResult = queryTransformer.getAggregationQuery(queryTransformer.getPlainQuery(query).right, plainResult);
         stopwatchAggRequest.stop();

         JsonNode jsonNodeResponse = objectMapper.readTree(aggregatedResult.replace("_1", "resultSet"));

         long aggregatedResultElapsedTime = stopwatchAggRequest.elapsed(TimeUnit.MILLISECONDS);

         com.aws.aqp.core.Response response = new com.aws.aqp.core.Response(
                 new Stats(plainResultElapsedTime, aggregatedResultElapsedTime, plainResult.getBytes().length),
                 jsonNodeResponse);

         return objectMapper.writeValueAsString(response);
     }

}
