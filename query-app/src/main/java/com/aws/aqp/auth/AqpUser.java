// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.auth;

import java.security.Principal;
import java.util.Set;
import java.util.UUID;

public class AqpUser implements Principal {
    private static final UUID userId = UUID.randomUUID();
    private final String name;
    private final Set<String> roles;

    public AqpUser(String name) {
        this.name = name;
        this.roles = null;
    }

    public AqpUser(String name, Set<String> roles) {
        this.name = name;
        this.roles = roles;
    }

    @Override
    public String getName() {
        return null;
    }

    public UUID getId() {
        return userId;
    }

    public Set<String> getRoles() {
        return roles;
    }
}
