// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.Configuration;

public class AppConfiguration extends Configuration {

    @JsonProperty
    private String serviceName = "Keyspaces";

    @JsonProperty
    private String pathToKeyspacesConfigFile = "../conf";

    @JsonProperty
    private String awsRegion = "US-EAST-1";

    @JsonProperty Boolean localDDB = false;

    @JsonProperty
    private String clientSecret = "secret";

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getPathToKeyspacesConfigFile() {
        return pathToKeyspacesConfigFile;
    }

    public void setPathToKeyspacesConfigFile(String pathToKeyspacesConfigFile) {
        this.pathToKeyspacesConfigFile = pathToKeyspacesConfigFile;
    }

    public String getAwsRegion() {
        return awsRegion;
    }

    public void setAwsRegion(String awsRegion) {
        this.awsRegion = awsRegion;
    }

    public Boolean getLocalDDB() {
        return localDDB;
    }

    public void setLocalDDB(Boolean localDDB) {
        this.localDDB = localDDB;
    }

    public String getClientSecret() {
        return clientSecret;
    }
}

