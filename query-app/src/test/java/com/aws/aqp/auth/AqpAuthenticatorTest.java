// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.auth;

import com.aws.aqp.application.ClientCredentials;
import io.dropwizard.auth.basic.BasicCredentials;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AqpAuthenticatorTest {

    private static ClientCredentials client(String secret, String... roles) {
        ClientCredentials credentials = new ClientCredentials();
        credentials.setSecret(secret);
        credentials.setRoles(Set.of(roles));
        return credentials;
    }

    private final AqpAuthenticator authenticator = new AqpAuthenticator(Map.of(
            "reporting", client("reporting-secret-0123456789", "SMALL_QUERY"),
            "batch", client("batch-secret-abcdefghijklmn", "LARGE_QUERY")));

    @Test
    void authenticatesEachClientWithItsOwnSecretAndRoles() {
        Optional<AqpUser> user = authenticator.authenticate(
                new BasicCredentials("reporting", "reporting-secret-0123456789"));
        assertTrue(user.isPresent());
        assertEquals("reporting", user.get().getName());
        assertEquals(Set.of("SMALL_QUERY"), user.get().getRoles());
    }

    /** One shared secret used to authenticate every user name. */
    @Test
    void rejectsAnotherClientsSecret() {
        assertTrue(authenticator.authenticate(
                new BasicCredentials("reporting", "batch-secret-abcdefghijklmn")).isEmpty());
    }

    @Test
    void rejectsNearMisses() {
        assertTrue(authenticator.authenticate(new BasicCredentials("reporting", "reporting-secret-012345678")).isEmpty());
        assertTrue(authenticator.authenticate(new BasicCredentials("reporting", "reporting-secret-01234567890")).isEmpty());
        assertTrue(authenticator.authenticate(new BasicCredentials("reporting", "")).isEmpty());
    }

    @Test
    void rejectsUnknownUsers() {
        assertTrue(authenticator.authenticate(
                new BasicCredentials("nobody", "reporting-secret-0123456789")).isEmpty());
    }

    @Test
    void refusesToStartWithNoUsers() {
        assertThrows(IllegalArgumentException.class, () -> new AqpAuthenticator(Map.of()));
    }

    @Test
    void neverPrintsTheSecret() {
        assertTrue(!client("reporting-secret-0123456789").toString().contains("reporting-secret"));
    }
}
