// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import com.aws.aqp.core.errors.InvalidQueryException;
import com.aws.aqp.core.errors.ResultTooLargeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The DuckDB engine reports every failure as SQLException; what the caller receives depends
 * entirely on {@link DuckDbEngine#translate}. Caller faults must become 400s with the engine's
 * explanation, resource exhaustion a 413, and everything else a logged server error.
 */
class DuckDbErrorTranslationTest {

    /** Verbatim from a live engine run with memory_limit=2MB. */
    private static final String REAL_OOM =
            "Out of Memory Error: failed to pin block of size 256.0 KiB (1.6 MiB/1.9 MiB used)";

    @Test
    void memoryExhaustionBecomesResultTooLarge() {
        RuntimeException translated = DuckDbEngine.translate(new SQLException(REAL_OOM));
        assertTrue(translated instanceof ResultTooLargeException, translated::toString);
        assertTrue(translated.getMessage().contains("memory budget"), translated::getMessage);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Binder Error: No function matches the given name and argument types 'sum(VARCHAR)'.",
            "Parser Error: syntax error at or near \"FORM\"",
            "Catalog Error: Table with name nope does not exist!",
            "Conversion Error: Could not convert string 'x' to INT64",
            "Invalid Input Error: arguments to list_value must all be the same type",
            "Out of Range Error: Overflow in multiplication of INT64",
    })
    void callerFaultsBecomeInvalidQueriesCarryingTheEngineExplanation(String message) {
        RuntimeException translated = DuckDbEngine.translate(new SQLException(message));
        assertTrue(translated instanceof InvalidQueryException, translated::toString);
        assertTrue(translated.getMessage().contains(message), translated::getMessage);
    }

    /** DuckDB appends a multi-line position marker; only the first line helps the caller. */
    @Test
    void onlyTheFirstLineOfAMultiLineMessageIsReturned() {
        RuntimeException translated = DuckDbEngine.translate(new SQLException(
                "Binder Error: Referenced column \"nope\" not found!\nLINE 1: SELECT nope FROM t\n              ^"));
        assertTrue(translated instanceof InvalidQueryException);
        assertFalse(translated.getMessage().contains("LINE 1"), translated::getMessage);
    }

    /** Engine-side faults (appender bugs, IO) are ours: opaque RuntimeException, cause kept. */
    @ParameterizedTest
    @ValueSource(strings = {
            "Appender error, catalog: 'null', schema: 'null', table: 'resultSet', message: invalid decimal",
            "IO Error: Could not read from file",
            "INTERNAL Error: something unexpected",
    })
    void engineFaultsStayServerErrors(String message) {
        SQLException cause = new SQLException(message);
        RuntimeException translated = DuckDbEngine.translate(cause);
        assertEquals(RuntimeException.class, translated.getClass(), translated::toString);
        assertSame(cause, translated.getCause());
    }

    @Test
    void toleratesANullMessage() {
        assertEquals(RuntimeException.class, DuckDbEngine.translate(new SQLException((String) null)).getClass());
    }

    /** End to end: a query that genuinely exhausts a tiny engine budget surfaces as 413 material. */
    @Test
    void aRealMemoryExhaustionSurfacesAsResultTooLarge() {
        DuckDbEngine tiny = DuckDbEngine.withMemoryLimitBytes(2L * 1024 * 1024);
        StringBuilder rows = new StringBuilder("[");
        for (int i = 0; i < 300; i++) {
            if (i > 0) {
                rows.append(',');
            }
            rows.append("{\"pk\":\"key-").append(i).append("\"}");
        }
        String document = "{\"resultSet\":" + rows.append("]") + "}";
        // 300^3 md5 rows through a sort cannot fit in 2 MB; the engine must give up, not crash.
        assertThrows(ResultTooLargeException.class, () -> tiny.query(
                "SELECT COUNT(*) AS n FROM (SELECT md5(a.pk || b.pk || c.pk) AS h "
                        + "FROM resultSet a, resultSet b, resultSet c ORDER BY h)", document));
    }
}
