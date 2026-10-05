// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import com.aws.aqp.core.errors.InvalidQueryException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatementGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT count(pk) as c FROM t WHERE pk='x'",
            "select count(pk) as c FROM t",
            "  \t SELECT count(pk) FROM t",
            "SeLeCt count(pk) FROM t",
            "SELECT count(pk) FROM t WHERE pk='x';",
            "SELECT count(pk) FROM t WHERE note='a;b'",
            "SELECT count(pk) FROM \"odd;table\"",
            "SELECT count(pk) FROM t WHERE note='it''s; fine'",
    })
    void acceptsReadOnlySelects(String statement) {
        assertDoesNotThrow(() -> StatementGuard.requireReadOnlySelect(statement));
    }

    /**
     * The endpoint is a GET, and both drivers happily execute writes. Before this guard,
     * GET /query-aggregation/DELETE FROM "orders" WHERE pk='...' destroyed data.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM \"orders\" WHERE pk='x'",
            "INSERT INTO t VALUE {'pk':'x'}",
            "UPDATE t SET a=1 WHERE pk='x'",
            "DROP TABLE t",
            "TRUNCATE t",
            "CREATE TABLE t (pk text PRIMARY KEY)",
    })
    void rejectsWrites(String statement) {
        assertThrows(InvalidQueryException.class, () -> StatementGuard.requireReadOnlySelect(statement));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT count(pk) FROM t; DELETE FROM t WHERE pk='x'",
            "SELECT count(pk) FROM t;DROP TABLE t",
            "SELECT count(pk) FROM t WHERE a='x''';DROP TABLE t",
            "SELECT count(pk) FROM t -- ;\nDROP TABLE t",
            "SELECT count(pk) FROM t // ;\nDROP TABLE t",
    })
    void rejectsASecondStatement(String statement) {
        assertThrows(InvalidQueryException.class, () -> StatementGuard.requireReadOnlySelect(statement));
    }

    @Test
    void rejectsEmptyInput() {
        assertThrows(InvalidQueryException.class, () -> StatementGuard.requireReadOnlySelect(null));
        assertThrows(InvalidQueryException.class, () -> StatementGuard.requireReadOnlySelect("   "));
    }
}
