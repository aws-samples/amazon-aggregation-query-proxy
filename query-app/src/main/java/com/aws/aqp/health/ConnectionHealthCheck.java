// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.health;

import com.aws.aqp.application.AppConfiguration;
import com.aws.aqp.connectors.ConnectionKeyspacesFactory;
import com.codahale.metrics.health.HealthCheck;
import com.datastax.oss.driver.api.core.CqlSession;

public class ConnectionHealthCheck extends HealthCheck {
    private final CqlSession cqlSession;

    public ConnectionHealthCheck(AppConfiguration appConfiguration) {
        ConnectionKeyspacesFactory connectionKeyspacesFactory = new ConnectionKeyspacesFactory(appConfiguration);
        this.cqlSession = connectionKeyspacesFactory.buildSession();
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

