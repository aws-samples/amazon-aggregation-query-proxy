// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.api;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request an id, puts it in the logging MDC as {@code requestId} for the whole
 * request, and returns it in the {@code X-Request-Id} response header.
 * <p>
 * Dropwizard's own {@code RequestIdFilter} only runs on the response, after the request has
 * been served, so none of the log lines written while serving it can carry the id. This one runs
 * first. A caller-supplied id is reused so a request can be traced across services, but only if
 * it is short and plain, since it is written verbatim into logs and headers.
 */
@PreMatching
@Priority(Priorities.AUTHENTICATION - 100)
public class RequestIdFilter implements ContainerRequestFilter, ContainerResponseFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    private static final Pattern ACCEPTABLE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public void filter(ContainerRequestContext request) {
        String supplied = request.getHeaderString(HEADER);
        String id = supplied != null && ACCEPTABLE_ID.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
        request.setProperty(MDC_KEY, id);
        MDC.put(MDC_KEY, id);
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        Object id = request.getProperty(MDC_KEY);
        if (id != null) {
            response.getHeaders().putSingle(HEADER, id.toString());
        }
        // Jetty reuses threads; do not let one request's id leak into the next one's logs.
        MDC.remove(MDC_KEY);
    }
}
