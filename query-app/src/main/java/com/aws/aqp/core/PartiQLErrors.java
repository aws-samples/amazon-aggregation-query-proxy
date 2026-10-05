// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import org.partiql.spi.errors.PError;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders PartiQL's structured errors as short human-readable text for 400 responses. */
public final class PartiQLErrors {

    private static final Pattern CODE = Pattern.compile("code=([A-Z_]+)");
    private static final Pattern LOCATION = Pattern.compile("line=(\\d+), offset=(\\d+)");

    private PartiQLErrors() {
    }

    /** For example {@code "UNEXPECTED_TOKEN at line 1, offset 30"}. */
    public static String describe(PError error) {
        if (error == null) {
            return "unknown error";
        }
        // PError exposes its details through toString; the code name and source location are
        // the two parts a caller can act on.
        String text = String.valueOf(error);
        Matcher code = CODE.matcher(text);
        Matcher location = LOCATION.matcher(text);
        String described = code.find() ? code.group(1) : "error";
        if (location.find()) {
            described += " at line " + location.group(1) + ", offset " + location.group(2);
        }
        return described;
    }
}
