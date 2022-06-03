// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.auth;

import com.aws.aqp.application.AppConfiguration;
import io.dropwizard.auth.AuthenticationException;
import io.dropwizard.auth.Authenticator;
import io.dropwizard.auth.basic.BasicCredentials;
import io.dropwizard.util.Maps;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class AqpAuthenticator implements Authenticator<BasicCredentials, AqpUser> {

    private AppConfiguration appConfiguration;

    public AqpAuthenticator(AppConfiguration appConfiguration) {
        this.appConfiguration = appConfiguration;

    }

    private static final Map<String, Set<String>> VALID_USERS = Collections.unmodifiableMap(Maps.of(
            "small-query-app", Collections.singleton("SMALL_QUERY"),
            "large-query-app", Collections.singleton("LARGE_QUERY")
    ));

    @Override
    public Optional<AqpUser> authenticate(BasicCredentials credentials) throws AuthenticationException {
        if (VALID_USERS.containsKey(credentials.getUsername()) && appConfiguration.getClientSecret().equals(credentials.getPassword())) {
            return Optional.of(new AqpUser(credentials.getUsername(), VALID_USERS.get(credentials.getUsername())));
        }
        return Optional.empty();
    }
}
