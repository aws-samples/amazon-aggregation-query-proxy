// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.connectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.dropwizard.lifecycle.Managed;

public abstract class Extractor implements Managed {

    public Extractor() {
    }

    public abstract String execute(String query) throws JsonProcessingException, InterruptedException;

}
