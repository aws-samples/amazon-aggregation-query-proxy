// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.api;

import com.codahale.metrics.annotation.Timed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Unauthenticated liveness endpoint on the application port, for load-balancer target health
 * checks: on ECS/Fargate the admin port binds loopback inside the task's network namespace,
 * so an ALB can only reach this port, and every other resource here returns 401 without
 * credentials.
 * <p>
 * Deliberately does not touch the data store or the aggregation engine: it answers "is this
 * task serving HTTP", not "is the backend healthy" — the deep check runs at startup and on the
 * admin port. Being unauthenticated it is also the cheapest thing a scanner can hit, so it does
 * nothing but allocate one small response.
 */
@Path("/ping")
@Produces(MediaType.APPLICATION_JSON)
public class PingResource {

    @GET
    @Timed
    public Response ping() {
        return Response.ok(Map.of("status", "ok"))
                .header("Cache-Control", "no-store")
                .build();
    }
}
