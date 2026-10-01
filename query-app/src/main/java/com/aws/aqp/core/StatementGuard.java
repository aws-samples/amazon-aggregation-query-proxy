// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import com.aws.aqp.core.errors.InvalidQueryException;
import com.aws.aqp.core.sql.SqlLexer;

import java.util.List;

/**
 * Rejects any caller statement that is not a single read-only SELECT.
 * <p>
 * The proxy passes the caller's WHERE clause through to {@code ExecuteStatement} (DynamoDB
 * PartiQL) or {@code executeAsync} (CQL), both of which accept writes. Without this check a
 * plain {@code GET} could run {@code DELETE FROM ...} or {@code DROP TABLE ...}.
 * <p>
 * Two rules are enough to make that impossible, because both drivers accept exactly one
 * statement per call:
 * <ol>
 *   <li>the statement must begin with {@code SELECT}, so no write verb can be the operation
 *       being performed; and</li>
 *   <li>it must contain no statement separator outside a literal or quoted identifier (a single
 *       trailing one is tolerated), so a second statement cannot be appended.</li>
 * </ol>
 * Deliberately not a keyword denylist: those produce false positives on legitimate data
 * (a row whose value contains "delete") and false negatives on anything they fail to list.
 * This is a defence-in-depth control, not a substitute for granting the proxy read-only
 * credentials (for example {@code dynamodb:PartiQLSelect} without the write actions).
 */
public final class StatementGuard {

    private StatementGuard() {
    }

    public static void requireReadOnlySelect(String statement) {
        if (statement == null || statement.isBlank()) {
            throw new InvalidQueryException("Query must not be empty.");
        }
        List<SqlLexer.Token> tokens = SqlLexer.tokenize(statement);
        if (tokens.isEmpty() || !tokens.get(0).isWord("SELECT")) {
            throw new InvalidQueryException(
                    "Only SELECT statements are accepted; this proxy is read-only.");
        }
        for (int i = 0; i < tokens.size(); i++) {
            SqlLexer.Token token = tokens.get(i);
            if (token.type == SqlLexer.Type.SEMICOLON && i != tokens.size() - 1) {
                throw new InvalidQueryException(String.format(
                        "Only a single statement is accepted; unexpected content after ';' at position %d.",
                        token.start));
            }
        }
    }
}
