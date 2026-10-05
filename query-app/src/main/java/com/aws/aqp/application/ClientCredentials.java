// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashSet;
import java.util.Set;

/** One API client's credentials and roles. */
public class ClientCredentials {

    /** Minimum secret length. Basic auth secrets are bearer credentials; short ones are guessable. */
    public static final int MIN_SECRET_LENGTH = 16;

    @NotNull
    @Size(min = MIN_SECRET_LENGTH, message = "must be at least " + MIN_SECRET_LENGTH + " characters")
    @JsonProperty
    private String secret;

    @NotNull
    @JsonProperty
    private Set<String> roles = new LinkedHashSet<>();

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public void setRoles(Set<String> roles) {
        this.roles = roles;
    }

    /** Never includes the secret, so a logged or printed config cannot leak it. */
    @Override
    public String toString() {
        return "ClientCredentials{roles=" + roles + ", secret=<redacted>}";
    }
}
