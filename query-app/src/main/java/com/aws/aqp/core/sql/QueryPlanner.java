// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core.sql;

import com.aws.aqp.core.errors.InvalidQueryException;
import com.aws.aqp.core.sql.SqlLexer.Token;
import com.aws.aqp.core.sql.SqlLexer.Type;
import org.partiql.lang.SqlException;
import org.partiql.lang.domains.PartiqlAst;
import org.partiql.lang.syntax.PartiQLParserBuilder;
import org.partiql.pig.runtime.SymbolPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a caller's aggregation query into a {@link QueryPlan}.
 * <p>
 * Replaces the regex/{@code String.replace} rewriting, which could not tell a keyword, comma or
 * table name from the same characters inside a literal, a quoted identifier or a nested call.
 * Two steps, each using a real grammar:
 * <ol>
 *   <li>{@link SqlLexer} splits the statement into top-level clauses. FROM and WHERE are kept
 *       as verbatim text for the data store, so its dialect-specific syntax survives.</li>
 *   <li>The aggregation clauses are parsed with PartiQL's own parser — the same engine that will
 *       evaluate them — and every column they reference is read off the AST. Those columns
 *       become the push-down projection.</li>
 * </ol>
 * Stateless and safe for concurrent use.
 */
public final class QueryPlanner {

    private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final String AGGREGATION_SOURCE = "resultSet";

    /** Top-level clauses, declared in the only order in which they may appear. */
    private enum Clause {
        SELECT("SELECT", false),
        FROM("FROM", false),
        WHERE("WHERE", false),
        GROUP_BY("GROUP BY", true),
        HAVING("HAVING", true),
        ORDER_BY("ORDER BY", true),
        PER_PARTITION_LIMIT("PER PARTITION LIMIT", false),
        // Not handed to PartiQL: it applies LIMIT before aggregating when there is no GROUP BY
        // (SUM(x) ... LIMIT 2 summed two rows). The plan applies both to the output instead.
        LIMIT("LIMIT", false),
        OFFSET("OFFSET", false),
        ALLOW_FILTERING("ALLOW FILTERING", false);

        final String keyword;
        /** Evaluated by PartiQL over the push-down rows rather than sent to the data store. */
        final boolean aggregation;

        Clause(String keyword, boolean aggregation) {
            this.keyword = keyword;
            this.aggregation = aggregation;
        }
    }

    /** Where one clause's keyword begins and where its body begins. */
    private static final class Boundary {
        final Clause clause;
        final int keywordStart;
        final int bodyStart;

        Boundary(Clause clause, int keywordStart, int bodyStart) {
            this.clause = clause;
            this.keywordStart = keywordStart;
            this.bodyStart = bodyStart;
        }
    }

    public QueryPlan plan(String query) {
        if (query == null || query.isBlank()) {
            throw new InvalidQueryException("Query must not be empty.");
        }
        List<Token> tokens = withoutTrailingSemicolon(SqlLexer.tokenize(query));
        Map<Clause, String> clauses = splitClauses(query, tokens);

        String source = clauses.get(Clause.FROM);
        List<String> sourceParts = requireSingleTable(source);
        String aggregationBody = aggregationBody(clauses);
        Analysis analysis = analyse(aggregationBody);
        Long limit = integerLiteral(Clause.LIMIT, clauses.get(Clause.LIMIT));
        Long offset = integerLiteral(Clause.OFFSET, clauses.get(Clause.OFFSET));
        boolean rowsAreTheAnswer = !analysis.aggregating && offset == null;

        return new QueryPlan(
                analysis.columns,
                analysis.allColumns,
                source,
                sourceParts,
                clauses.get(Clause.WHERE),
                clauses.get(Clause.PER_PARTITION_LIMIT),
                clauses.containsKey(Clause.ALLOW_FILTERING),
                rowsAreTheAnswer && limit != null && limit > 0 ? limit : null,
                aggregationBody,
                limit,
                offset == null ? 0 : offset);
    }

    private static final Pattern NON_NEGATIVE_INTEGER = Pattern.compile("[0-9]{1,18}");

    private static Long integerLiteral(Clause clause, String text) {
        if (text == null) {
            return null;
        }
        if (!NON_NEGATIVE_INTEGER.matcher(text).matches()) {
            throw new InvalidQueryException(clause.keyword + " must be a non-negative integer literal; got: " + text);
        }
        return Long.parseLong(text);
    }


    private static List<Token> withoutTrailingSemicolon(List<Token> tokens) {
        int last = tokens.size() - 1;
        List<Token> result = last >= 0 && tokens.get(last).type == Type.SEMICOLON
                ? tokens.subList(0, last)
                : tokens;
        for (Token token : result) {
            if (token.type == Type.SEMICOLON) {
                throw new InvalidQueryException(String.format(
                        "Only a single statement is accepted; unexpected ';' at position %d.", token.start));
            }
        }
        if (result.isEmpty()) {
            throw new InvalidQueryException("Query must not be empty.");
        }
        return result;
    }

    private static Map<Clause, String> splitClauses(String query, List<Token> tokens) {
        if (!tokens.get(0).isWord("SELECT")) {
            throw new InvalidQueryException("Only SELECT statements are accepted; this proxy is read-only.");
        }

        List<Boundary> boundaries = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.depth != 0 || token.type != Type.WORD) {
                continue;
            }
            Boundary boundary = null;
            if (token.isWord("SELECT")) {
                if (i != 0) {
                    throw new InvalidQueryException(
                            "Only a single SELECT is supported; set operations and subqueries are not.");
                }
                boundary = new Boundary(Clause.SELECT, token.start, token.end);
            } else if (token.isWord("UNION") || token.isWord("INTERSECT") || token.isWord("EXCEPT")) {
                throw new InvalidQueryException("Set operations (" + token.text.toUpperCase(Locale.ROOT)
                        + ") are not supported.");
            } else if (token.isWord("JOIN")) {
                throw new InvalidQueryException("Joins are not supported; query a single table.");
            } else if (token.isWord("FROM")) {
                boundary = new Boundary(Clause.FROM, token.start, token.end);
            } else if (token.isWord("WHERE")) {
                boundary = new Boundary(Clause.WHERE, token.start, token.end);
            } else if (token.isWord("HAVING")) {
                boundary = new Boundary(Clause.HAVING, token.start, token.end);
            } else if (token.isWord("OFFSET")) {
                boundary = new Boundary(Clause.OFFSET, token.start, token.end);
            } else if (token.isWord("LIMIT")) {
                boundary = new Boundary(Clause.LIMIT, token.start, token.end);
            } else if (token.isWord("GROUP") && wordAt(tokens, i + 1, "BY")) {
                boundary = new Boundary(Clause.GROUP_BY, token.start, tokens.get(++i).end);
            } else if (token.isWord("ORDER") && wordAt(tokens, i + 1, "BY")) {
                boundary = new Boundary(Clause.ORDER_BY, token.start, tokens.get(++i).end);
            } else if (token.isWord("PER") && wordAt(tokens, i + 1, "PARTITION") && wordAt(tokens, i + 2, "LIMIT")) {
                boundary = new Boundary(Clause.PER_PARTITION_LIMIT, token.start, tokens.get(i + 2).end);
                i += 2;
            } else if (token.isWord("ALLOW") && wordAt(tokens, i + 1, "FILTERING")) {
                boundary = new Boundary(Clause.ALLOW_FILTERING, token.start, tokens.get(++i).end);
            }
            if (boundary != null) {
                Boundary previous = boundaries.isEmpty() ? null : boundaries.get(boundaries.size() - 1);
                if (previous != null && boundary.clause.ordinal() <= previous.clause.ordinal()) {
                    throw new InvalidQueryException(String.format(
                            "%s at position %d is repeated or out of order.",
                            boundary.clause.keyword, boundary.keywordStart));
                }
                boundaries.add(boundary);
            }
        }

        int queryEnd = tokens.get(tokens.size() - 1).end;
        Map<Clause, String> clauses = new EnumMap<>(Clause.class);
        for (int b = 0; b < boundaries.size(); b++) {
            Boundary boundary = boundaries.get(b);
            int bodyEnd = b + 1 < boundaries.size() ? boundaries.get(b + 1).keywordStart : queryEnd;
            String body = query.substring(boundary.bodyStart, Math.max(boundary.bodyStart, bodyEnd)).trim();
            boolean expectsBody = boundary.clause != Clause.ALLOW_FILTERING;
            if (expectsBody && body.isEmpty()) {
                throw new InvalidQueryException(boundary.clause.keyword + " clause is empty.");
            }
            if (!expectsBody && !body.isEmpty()) {
                throw new InvalidQueryException("Unexpected text after ALLOW FILTERING: " + body);
            }
            clauses.put(boundary.clause, body);
        }
        if (!clauses.containsKey(Clause.FROM)) {
            throw new InvalidQueryException("Query must have a FROM clause.");
        }
        return clauses;
    }

    private static boolean wordAt(List<Token> tokens, int index, String word) {
        return index < tokens.size() && tokens.get(index).depth == 0 && tokens.get(index).isWord(word);
    }

    /**
     * Accepts exactly one table name, optionally qualified ({@code keyspace.table},
     * {@code "table"."index"}). Anything more is an alias, a join or a subquery, none of which
     * the push-down can express, and each of which would otherwise fail in a confusing way.
     */
    private static List<String> requireSingleTable(String from) {
        List<Token> tokens = SqlLexer.tokenize(from);
        List<String> parts = new ArrayList<>();
        boolean expectName = true;
        for (Token token : tokens) {
            boolean ok = expectName
                    ? token.type == Type.WORD || token.type == Type.QUOTED_IDENTIFIER
                    : token.type == Type.DOT;
            if (!ok) {
                throw new InvalidQueryException(String.format(
                        "FROM must name exactly one table, for example keyspace.table or \"table\".\"index\"; "
                                + "joins, table aliases and subqueries are not supported. Got: %s", from));
            }
            if (expectName) {
                parts.add(token.text);
            }
            expectName = !expectName;
        }
        if (expectName) {
            throw new InvalidQueryException("FROM clause ends with '.': " + from);
        }
        return parts;
    }

    private static String aggregationBody(Map<Clause, String> clauses) {
        StringBuilder sb = new StringBuilder("SELECT ").append(clauses.get(Clause.SELECT));
        sb.append(" FROM ").append(AGGREGATION_SOURCE);
        for (Clause clause : Clause.values()) {
            if (clause.aggregation && clauses.containsKey(clause)) {
                sb.append(' ').append(clause.keyword).append(' ').append(clauses.get(clause));
            }
        }
        return sb.toString();
    }

    /** What the aggregation half of a query needs from the store. */
    private static final class Analysis {
        final List<String> columns;
        /** SELECT *: every attribute is part of the answer. */
        final boolean allColumns;
        /** Output rows are not simply input rows (aggregate, GROUP BY, ORDER BY, DISTINCT, ...). */
        final boolean aggregating;

        Analysis(List<String> columns, boolean allColumns, boolean aggregating) {
            this.columns = columns;
            this.allColumns = allColumns;
            this.aggregating = aggregating;
        }
    }

    /** Collects every identifier expression, and counts nested SELECTs and aggregate calls. */
    private static final class IdentifierCollector extends PartiqlAst.Visitor {
        final List<PartiqlAst.Expr.Id> ids = new ArrayList<>();
        int nestedSelects;
        int aggregateCalls;

        @Override
        protected void visitExprCallAgg(PartiqlAst.Expr.CallAgg node) {
            aggregateCalls++;
        }

        @Override
        protected void visitExprId(PartiqlAst.Expr.Id node) {
            ids.add(node);
        }

        @Override
        protected void visitExprSelect(PartiqlAst.Expr.Select node) {
            nestedSelects++;
        }
    }

    private static Analysis analyse(String aggregationBody) {
        PartiqlAst.Expr.Select select = parseSelect(aggregationBody);

        Set<String> selectAliases = new HashSet<>();
        if (select.getProject() instanceof PartiqlAst.Projection.ProjectList) {
            for (PartiqlAst.ProjectItem item :
                    ((PartiqlAst.Projection.ProjectList) select.getProject()).getProjectItems()) {
                if (item instanceof PartiqlAst.ProjectItem.ProjectExpr) {
                    addSymbol(selectAliases, ((PartiqlAst.ProjectItem.ProjectExpr) item).getAsAlias());
                }
            }
        }
        Set<String> groupAliases = new HashSet<>();
        if (select.getGroup() != null) {
            for (PartiqlAst.GroupKey key : select.getGroup().getKeyList().getKeys()) {
                addSymbol(groupAliases, key.getAsAlias());
            }
            addSymbol(groupAliases, select.getGroup().getGroupAsAlias());
        }

        // Walked separately because the same name means different things in different clauses:
        // in GROUP BY it is always a column; in the SELECT list it may be a GROUP BY alias; in
        // HAVING and ORDER BY it may be a GROUP BY alias or (unsupported) a SELECT alias.
        IdentifierCollector groupIds = new IdentifierCollector();
        IdentifierCollector selectIds = new IdentifierCollector();
        IdentifierCollector lateIds = new IdentifierCollector();
        IdentifierCollector limitIds = new IdentifierCollector();

        if (select.getGroup() != null) {
            groupIds.walkGroupBy(select.getGroup());
        }
        selectIds.walkProjection(select.getProject());
        if (select.getHaving() != null) {
            lateIds.walkExpr(select.getHaving());
        }
        if (select.getOrder() != null) {
            lateIds.walkOrderBy(select.getOrder());
        }
        if (select.getLimit() != null) {
            limitIds.walkExpr(select.getLimit());
        }
        if (select.getOffset() != null) {
            limitIds.walkExpr(select.getOffset());
        }
        if (groupIds.nestedSelects + selectIds.nestedSelects + lateIds.nestedSelects + limitIds.nestedSelects > 0) {
            throw new InvalidQueryException("Subqueries are not supported.");
        }

        Set<String> columns = new LinkedHashSet<>();
        for (PartiqlAst.Expr.Id id : selectIds.ids) {
            if (!(isCaseInsensitive(id) && groupAliases.contains(folded(id)))) {
                columns.add(render(id));
            }
        }
        for (PartiqlAst.Expr.Id id : groupIds.ids) {
            columns.add(render(id));
        }
        for (PartiqlAst.Expr.Id id : lateIds.ids) {
            if (isCaseInsensitive(id) && groupAliases.contains(folded(id))) {
                continue;
            }
            if (isCaseInsensitive(id) && selectAliases.contains(folded(id)) && !columns.contains(render(id))) {
                throw new InvalidQueryException(String.format(
                        "HAVING and ORDER BY cannot refer to the SELECT alias '%s'; the aggregation "
                                + "engine (PartiQL) does not support it. Repeat the expression instead, "
                                + "for example ORDER BY SUM(amount) rather than ORDER BY total.",
                        id.getName().getText()));
            }
            columns.add(render(id));
        }

        boolean aggregating = selectIds.aggregateCalls + lateIds.aggregateCalls > 0
                || select.getGroup() != null
                || select.getHaving() != null
                || select.getOrder() != null
                || select.getOffset() != null
                || select.getSetq() instanceof PartiqlAst.SetQuantifier.Distinct;
        boolean allColumns = select.getProject() instanceof PartiqlAst.Projection.ProjectStar;
        return new Analysis(new ArrayList<>(columns), allColumns, aggregating);
    }

    private static PartiqlAst.Expr.Select parseSelect(String aggregationBody) {
        PartiqlAst.Statement statement;
        try {
            statement = PartiQLParserBuilder.standard().build().parseAstStatement(aggregationBody);
        } catch (SqlException e) {
            throw new InvalidQueryException("Could not parse the query: " + firstLine(e.getMessage()));
        }
        if (statement instanceof PartiqlAst.Statement.Query) {
            PartiqlAst.Expr expr = ((PartiqlAst.Statement.Query) statement).getExpr();
            if (expr instanceof PartiqlAst.Expr.Select) {
                return (PartiqlAst.Expr.Select) expr;
            }
        }
        throw new InvalidQueryException("Only SELECT statements are accepted; this proxy is read-only.");
    }

    private static boolean isCaseInsensitive(PartiqlAst.Expr.Id id) {
        return !(id.getCase() instanceof PartiqlAst.CaseSensitivity.CaseSensitive);
    }

    private static String folded(PartiqlAst.Expr.Id id) {
        return id.getName().getText().toLowerCase(Locale.ROOT);
    }

    private static void addSymbol(Set<String> target, SymbolPrimitive symbol) {
        if (symbol != null) {
            target.add(symbol.getText().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Renders a column for the push-down projection. Unquoted names are passed as the caller
     * spelled them; quoted names, and names that are not plain identifiers, are re-quoted.
     */
    private static String render(PartiqlAst.Expr.Id id) {
        String name = id.getName().getText();
        if (isCaseInsensitive(id) && SIMPLE_IDENTIFIER.matcher(name).matches()) {
            return name;
        }
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "syntax error";
        }
        int newline = message.indexOf('\n');
        return (newline < 0 ? message : message.substring(0, newline)).trim();
    }
}
