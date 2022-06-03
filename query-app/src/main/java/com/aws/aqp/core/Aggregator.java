// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import com.aws.aqp.connectors.Extractor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;

import java.util.concurrent.TimeUnit;

public class Aggregator {

    private Extractor extractor;
    private ObjectMapper objectMapper;

    public Aggregator(Extractor extractor ) {
        this.extractor = extractor;
        this.objectMapper = new ObjectMapper();
    }

     public String aggregate(String query) throws JsonProcessingException, InterruptedException {
         QueryTransformer queryTransformer = new QueryTransformer();

         Stopwatch stopwatchPlainRequest = Stopwatch.createStarted();
         var plainResult = extractor.execute(queryTransformer.getPlainQuery(query).left);
         stopwatchPlainRequest.stop();
         long plainResultElapsedTime = stopwatchPlainRequest.elapsed(TimeUnit.MILLISECONDS);
         Stopwatch stopwatchAggRequest = Stopwatch.createStarted();
         var aggregatedResult = queryTransformer.getAggregationQuery(queryTransformer.getPlainQuery(query).right, plainResult);
         stopwatchAggRequest.stop();
         long aggregatedResultElapsedTime = stopwatchAggRequest.elapsed(TimeUnit.MILLISECONDS);

         com.aws.aqp.core.Response response = new com.aws.aqp.core.Response(
                 new Stats(plainResultElapsedTime, aggregatedResultElapsedTime, plainResult.getBytes().length),
                 aggregatedResult);

         return objectMapper.writeValueAsString(response);
     }

}
