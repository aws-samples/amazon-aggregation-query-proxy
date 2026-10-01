// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.health;

import com.codahale.metrics.health.HealthCheck;
import com.datastax.oss.driver.api.core.CqlSession;

/**
 * Checks Amazon Keyspaces reachability over the application's own session.
 * <p>
 * It used to build a second full CqlSession — its own connection pool and control connection —
 * and never close it. Checking over the session the extractor uses is both cheaper and a more
 * truthful answer to "can this process serve queries?".
 */
public class ConnectionHealthCheck extends HealthCheck {
    private final CqlSession cqlSession;

    public ConnectionHealthCheck(CqlSession cqlSession) {
        this.cqlSession = cqlSession;
    }

    @Override
    protected Result check() throws Exception {
        var result = cqlSession.execute("select key from system.local").one();
        if (result != null) {
            return Result.healthy();
        } else
            return Result.unhealthy("Cannot connect to Amazon Keyspaces ");
    }
}
