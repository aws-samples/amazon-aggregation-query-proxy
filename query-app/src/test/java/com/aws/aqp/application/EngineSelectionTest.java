// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.application;

import com.aws.aqp.core.AggregationEngine;
import com.aws.aqp.core.DuckDbEngine;
import com.aws.aqp.core.EngineType;
import com.aws.aqp.core.PartiQLEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineSelectionTest {

    private static AppConfiguration config(String engine) {
        AppConfiguration config = new AppConfiguration();
        config.setAggregationEngine(engine);
        return config;
    }

    @ParameterizedTest
    @ValueSource(strings = {"PARTIQL", "partiql", " PartiQL "})
    void parsesTheEngineNameCaseInsensitively(String name) {
        assertEquals(EngineType.PARTIQL, config(name).getAggregationEngine());
    }

    @Test
    void defaultsToPartiQL() {
        assertEquals(EngineType.PARTIQL, new AppConfiguration().getAggregationEngine());
    }

    /** The message must name the valid values; an operator sees it once, at startup. */
    @ParameterizedTest
    @ValueSource(strings = {"sqlite", "", "  "})
    void rejectsUnknownEngineNamesNamingTheValidOnes(String name) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> config(name).getAggregationEngine());
        assertTrue(e.getMessage().contains("PARTIQL"), e::getMessage);
    }

    @Test
    void buildsThePartiQLEngine() {
        assertTrue(App.buildEngine(config("PARTIQL")) instanceof PartiQLEngine);
    }

    @Test
    void buildsTheDuckDbEngine() {
        assertTrue(App.buildEngine(config("DUCKDB")) instanceof DuckDbEngine);
    }

    /** Exercises the native library load end to end: F4's startup guarantee. */
    @Test
    void theDuckDbEnginePassesTheStartupProbe() {
        assertDoesNotThrow(() -> App.probeEngine(App.buildEngine(config("DUCKDB")), EngineType.DUCKDB));
    }

    @Test
    void theDefaultEnginePassesTheStartupProbe() {
        assertDoesNotThrow(() -> App.probeEngine(new PartiQLEngine(), EngineType.PARTIQL));
    }

    /** A broken engine must be a clear startup failure naming the engine, not a 500 later. */
    @Test
    void aFailingEngineFailsStartupWithTheEngineName() {
        AggregationEngine broken = (sql, document) -> {
            throw new RuntimeException("native library failed to load");
        };
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> App.probeEngine(broken, EngineType.PARTIQL));
        assertTrue(e.getMessage().contains("PARTIQL"), e::getMessage);
        assertTrue(e.getMessage().contains("startup probe"), e::getMessage);
    }
}
