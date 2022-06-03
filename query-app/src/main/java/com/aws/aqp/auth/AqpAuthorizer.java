// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.auth;

import io.dropwizard.auth.Authorizer;
import org.jetbrains.annotations.Nullable;

import javax.ws.rs.container.ContainerRequestContext;

public class AqpAuthorizer implements Authorizer<AqpUser> {

    @Override
    public boolean authorize(AqpUser aqpUser, String s) {
        return false;
    }

    @Override
    public boolean authorize(AqpUser principal, String role, @Nullable ContainerRequestContext requestContext) {
        return principal.getRoles() != null && principal.getRoles().contains(role);
    }
}
