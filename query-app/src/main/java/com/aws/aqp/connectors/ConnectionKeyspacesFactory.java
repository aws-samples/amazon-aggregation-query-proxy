// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.connectors;

import com.aws.aqp.application.AppConfiguration;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.type.codec.TypeCodecs;

import java.io.File;

public class ConnectionKeyspacesFactory {

    public static final String CONFIG_FILE_NAME = "KeyspacesConnector.conf";

    private final AppConfiguration appConfiguration;

    public ConnectionKeyspacesFactory(AppConfiguration appConfiguration) {
        this.appConfiguration = appConfiguration;
    }

    public File configFile() {
        return new File(appConfiguration.getPathToKeyspacesConfigFile(), CONFIG_FILE_NAME);
    }

    public CqlSession buildSession() {
        return CqlSession.builder()
                .withConfigLoader(DriverConfigLoader.fromFile(configFile()))
                .addTypeCodecs(TypeCodecs.ZONED_TIMESTAMP_UTC)
                .build();
    }

}
