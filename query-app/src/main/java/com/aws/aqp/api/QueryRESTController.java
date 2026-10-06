// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.api;

import com.aws.aqp.auth.AqpUser;
import com.aws.aqp.connectors.Extractor;
import com.aws.aqp.core.AggregationEngine;
import com.aws.aqp.core.Aggregator;
import com.aws.aqp.core.Response;
import com.aws.aqp.core.StatementGuard;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.dropwizard.auth.Auth;
import io.dropwizard.jersey.caching.CacheControl;

import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.concurrent.TimeUnit;

@Path("/query-aggregation")
@Produces(MediaType.APPLICATION_JSON)
public class QueryRESTController {
    private final static int MAX_AGED_CACHE = 1;
    private final Aggregator aggregator;

    public QueryRESTController(Extractor extractor, AggregationEngine engine, MetricRegistry metrics) {
        this.aggregator = new Aggregator(extractor, engine, metrics);
    }

    /**
     * Returns the response object directly so the JAX-RS Jackson provider performs the single
     * serialization. The previous version serialized to a string and then called
     * {@code StringEscapeUtils.unescapeJson} on it, which produced malformed JSON for any value
     * containing a quote, backslash or newline, and allowed a stored attribute value to inject
     * arbitrary keys into the response body.
     * <p>
     * {@code isPrivate} marks the response uncacheable by shared intermediaries. Without it the
     * {@code max-age} below let a proxy serve one authenticated principal's result to another.
     */
    @PermitAll
    @Timed
    @ExceptionMetered
    @GET
    @CacheControl(maxAge = MAX_AGED_CACHE, maxAgeUnit = TimeUnit.MINUTES, isPrivate = true)
    @Path("/{query}")
    public Response getAggregatedResult(@Auth AqpUser user, @PathParam("query") String query)
            throws JsonProcessingException, InterruptedException {
        return run(query);
    }

    /**
     * The query as a query-string parameter: {@code GET /query-aggregation?query=...}.
     * <p>
     * Unlike the path form above, this can carry a {@code /} (division, dates, ARNs, URLs in
     * literals). In the path, an encoded {@code %2F} is an ambiguous path separator, which Jetty
     * rejects with an HTML 400 before the request reaches the application. Relaxing that check
     * would weaken Jetty's protection against path-confusion attacks, so it is left on.
     */
    @PermitAll
    @Timed
    @ExceptionMetered
    @GET
    @CacheControl(maxAge = MAX_AGED_CACHE, maxAgeUnit = TimeUnit.MINUTES, isPrivate = true)
    public Response getAggregatedResultFromQueryParameter(@Auth AqpUser user, @QueryParam("query") String query)
            throws JsonProcessingException, InterruptedException {
        if (query == null || query.isBlank()) {
            throw new com.aws.aqp.core.errors.InvalidQueryException(
                    "Provide the query as ?query=..., in the path, or as a POST body.");
        }
        return run(query);
    }

    /**
     * Preferred over GET. A query in the URL path — including its literal predicate values,
     * often customer identifiers — is written to every access log, proxy log and browser history
     * it passes through, and its length is capped by the server's URI limit. A request body is
     * not. Not cacheable, being a POST.
     */
    @PermitAll
    @Timed
    @ExceptionMetered
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response postAggregatedResult(@Auth AqpUser user, @NotNull @Valid QueryRequest request)
            throws JsonProcessingException, InterruptedException {
        return run(request.getQuery());
    }

    private Response run(String query) throws JsonProcessingException, InterruptedException {
        StatementGuard.requireReadOnlySelect(query);
        return aggregator.aggregate(query);
    }
}
