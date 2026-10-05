// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.auth;

import java.security.Principal;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

public class AqpUser implements Principal {
    // Per instance. This was static, so every user in the process shared one id and getId()
    // could not tell callers apart.
    private final UUID userId = UUID.randomUUID();
    private final String name;
    private final Set<String> roles;

    public AqpUser(String name) {
        this(name, Collections.emptySet());
    }

    public AqpUser(String name, Set<String> roles) {
        this.name = name;
        this.roles = roles == null ? Collections.emptySet() : Set.copyOf(roles);
    }

    /** Returned null before, which broke request logging and any audit trail keyed on it. */
    @Override
    public String getName() {
        return name;
    }

    public UUID getId() {
        return userId;
    }

    public Set<String> getRoles() {
        return roles;
    }
}
