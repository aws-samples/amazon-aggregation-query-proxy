// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.auth;

import com.aws.aqp.application.ClientCredentials;
import io.dropwizard.auth.Authenticator;
import io.dropwizard.auth.basic.BasicCredentials;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Authenticates Basic credentials against the configured clients.
 * <p>
 * Replaces a version with two hard-coded user names sharing one secret (default "secret"),
 * compared with {@code String.equals}. {@code equals} returns at the first differing character,
 * so response time leaked how much of a guess was right.
 * <p>
 * Now each client has its own secret, and comparison is constant time: both sides are reduced to
 * SHA-256 digests (equal length regardless of input) and compared with
 * {@link MessageDigest#isEqual}. An unknown user name is compared against a dummy digest, so the
 * response time does not reveal which names exist.
 */
public class AqpAuthenticator implements Authenticator<BasicCredentials, AqpUser> {

    private static final class Client {
        final byte[] secretDigest;
        final Set<String> roles;

        Client(byte[] secretDigest, Set<String> roles) {
            this.secretDigest = secretDigest;
            this.roles = roles;
        }
    }

    private final Map<String, Client> clients = new HashMap<>();
    private final byte[] dummyDigest = sha256("no such user; this value never matches");

    public AqpAuthenticator(Map<String, ClientCredentials> users) {
        if (users == null || users.isEmpty()) {
            throw new IllegalArgumentException("At least one entry under 'users' must be configured.");
        }
        users.forEach((name, credentials) ->
                clients.put(name, new Client(sha256(credentials.getSecret()), Set.copyOf(credentials.getRoles()))));
    }

    @Override
    public Optional<AqpUser> authenticate(BasicCredentials credentials) {
        Client client = clients.get(credentials.getUsername());
        byte[] expected = client == null ? dummyDigest : client.secretDigest;
        boolean matches = MessageDigest.isEqual(expected, sha256(credentials.getPassword()));
        if (client == null || !matches) {
            return Optional.empty();
        }
        return Optional.of(new AqpUser(credentials.getUsername(), client.roles));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every Java platform.
            throw new IllegalStateException(e);
        }
    }
}
