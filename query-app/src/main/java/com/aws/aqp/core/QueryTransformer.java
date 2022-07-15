// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.core;

import org.apache.commons.lang3.tuple.ImmutablePair;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QueryTransformer {

    private final static Pattern REGEX_IN = Pattern.compile(" WHERE(.*) ((.*) IN \\((.*?)\\))(.*?)", Pattern.CASE_INSENSITIVE);
    private final static Pattern REGEX_WHERE = Pattern.compile("(?i)WHERE.*", Pattern.CASE_INSENSITIVE);
    private final static Pattern REGEX_GROUP_BY = Pattern.compile("(?i)GROUP BY (.*)", Pattern.CASE_INSENSITIVE);
    private final static Pattern REGEX_PARENTHESIS = Pattern.compile("\\((.*?)\\)");
    private final static Pattern REGEX_SELECT_CC = Pattern.compile("SELECT (.*)", Pattern.CASE_INSENSITIVE);
    private final static Pattern REGEX_FROM_CC = Pattern.compile("(.*) FROM", Pattern.CASE_INSENSITIVE);
    private final static Pattern REGEX_FROM_GROUP_CC = Pattern.compile("FROM (.*) GROUP BY", Pattern.CASE_INSENSITIVE);
    private final static Pattern REGEX_TABLE_NAME_CC = Pattern.compile("FROM ([^\\s]+)", Pattern.CASE_INSENSITIVE);
    private final static Pattern REGEX_LIMIT = Pattern.compile("LIMIT (.*)");
    private static Matcher matcher;

    public QueryTransformer() {
    }

    public static String getQueryColumnInFunction(String token) {
        matcher = REGEX_PARENTHESIS.matcher(token);
        while (matcher.find()) {
            return matcher.group(1);
        }

        matcher = REGEX_SELECT_CC.matcher(token);
        while (matcher.find()) {
            return matcher.group(1);
        }

        matcher = REGEX_FROM_CC.matcher(token);
        while (matcher.find()) {
            return matcher.group(1);
        }

        matcher = REGEX_LIMIT.matcher(token);
        while (matcher.find()) {
            return "";
        }

        return token;
    }

    public static List<String> getTextTokens(String query) {
        String finalQuery = query;
        Matcher matcher = REGEX_IN.matcher(query);
        String termIn = "";
        for (int grp = 0; grp < matcher.groupCount(); grp++) {
            while (matcher.find()) {
                termIn = matcher.group(grp);
            }
            if (!termIn.isBlank()) {
                finalQuery = query.replace(termIn, "");
            }
        }
        String[] arr = finalQuery.split("WHERE", Pattern.CASE_INSENSITIVE);
        List<String> list = new ArrayList<>();
        StringTokenizer stringTokenizer = new StringTokenizer(arr[0], ",");
        while (stringTokenizer.hasMoreTokens()) {
            String strT = stringTokenizer.nextToken();
            if (!strT.isEmpty()) list.add(strT);
        }
        return list;
    }

    public ImmutablePair<String, String> getPreAggregationQuery(String aggregationQuery) {

        Set<String> finalColumns = new HashSet<>();
        List<String> tokens = getTextTokens(aggregationQuery);
        for (String token : tokens) {
            String processedToken = getQueryColumnInFunction(token.replaceAll("\\s+", " ").trim());
            if (!processedToken.isEmpty()) finalColumns.add(processedToken);
        }

        String queryColumns = String.join(",", finalColumns);

        matcher = REGEX_TABLE_NAME_CC.matcher(aggregationQuery);
        String tableName = "";
        while (matcher.find()) {
            tableName = matcher.group(1);
        }

        matcher = REGEX_FROM_GROUP_CC.matcher(aggregationQuery);
        String fromGroup;

        if (matcher.find())
            fromGroup = matcher.group(1);
        else {
            fromGroup = aggregationQuery.split("(?i)FROM", Pattern.CASE_INSENSITIVE)[1].trim();
        }

        matcher = REGEX_LIMIT.matcher(aggregationQuery);
        String limitGroup = "";

        if (matcher.find())
            limitGroup = String.format(" LIMIT %s", matcher.group(1));

        String preAggregationQuery = String.format("SELECT json %s FROM %s%s", queryColumns, fromGroup, limitGroup);
        return new ImmutablePair<>(preAggregationQuery, tableName);
    }

    public ImmutablePair<String, String> getPlainQuery(String query) {
        // Returns immutable pair <plain query, table name>
        var preAggregationQueryPair = getPreAggregationQuery(query);
        String preAggregationQuery = preAggregationQueryPair.left;
        String tableName = preAggregationQueryPair.right;
        String inputQuery = query.replace(tableName, "resultSet");
        return new ImmutablePair<>(preAggregationQuery, inputQuery);
    }

    public String getAggregationQuery(String query, String preAggregatedResult) {
        if (REGEX_WHERE.matcher(query).find()
                && !REGEX_GROUP_BY.matcher(query).find()) {
            query = query.replaceAll("(?i)WHERE .*", "");
        }

        if (REGEX_WHERE.matcher(query).find()
                && REGEX_GROUP_BY.matcher(query).find()) {

            Matcher groupByMatcher = REGEX_GROUP_BY.matcher(query);
            var substrGroupBy = "";
            while (groupByMatcher.find()) {
                substrGroupBy = groupByMatcher.group(0);
            }

            query = query.replaceAll("(?i)WHERE .*", "").concat(substrGroupBy);
            System.out.println(query);
        }

        String aggregatedQuery = String.format("SELECT (%s) FROM inputDocument", query);
        IonEngine ionEngine = new IonEngine();

        return ionEngine.query(aggregatedQuery, preAggregatedResult);
    }

}
