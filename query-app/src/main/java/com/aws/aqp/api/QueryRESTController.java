// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.api;

import com.aws.aqp.auth.AqpUser;
import com.aws.aqp.connectors.Extractor;
import com.aws.aqp.core.Aggregator;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.dropwizard.auth.Auth;
import io.dropwizard.jersey.caching.CacheControl;

import javax.annotation.security.PermitAll;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.concurrent.TimeUnit;

@Path("/query-aggregation")
@Produces(MediaType.APPLICATION_JSON)
public class QueryRESTController {
    private Extractor extractor;
    private final static int MAX_AGED_CACHE = 1;

    public QueryRESTController(Extractor extractor) {
        this.extractor = extractor;
    }

    @PermitAll
    @GET
    @CacheControl(maxAge = MAX_AGED_CACHE, maxAgeUnit = TimeUnit.MINUTES)
    @Path("/{query}")
    public Response getAggregatedResult(@Auth AqpUser user, @PathParam("query") String query) throws JsonProcessingException, InterruptedException {

        Aggregator aggregator = new Aggregator(extractor);
        String result = aggregator.aggregate(query);

        return Response.ok(result).build();
    }
}
