// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.auth;

import io.dropwizard.auth.Authorizer;

import jakarta.ws.rs.container.ContainerRequestContext;

public class AqpAuthorizer implements Authorizer<AqpUser> {

    @Override
    public boolean authorize(AqpUser principal, String role, ContainerRequestContext requestContext) {
        return principal.getRoles().contains(role);
    }
}
