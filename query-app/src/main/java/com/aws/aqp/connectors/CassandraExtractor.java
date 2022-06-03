// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.connectors;

import com.aws.aqp.application.AppConfiguration;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


public class CassandraExtractor extends Extractor {

    private final ConnectionKeyspacesFactory connectionKeyspacesFactory;
    private CqlSession cqlSession;
    private Set<String> dbGenericResult = new ConcurrentSkipListSet<>();
    private CountDownLatch countDownLatch;
    private static long TIMEOUT = 360;

    public CassandraExtractor(AppConfiguration appConfiguration) {
        connectionKeyspacesFactory = new ConnectionKeyspacesFactory(appConfiguration);

    }

    void processPagesAsync(AsyncResultSet rs, Throwable error) {
        if (error != null) {
            throw new RuntimeException(error);
        } else {
            rs.currentPage().forEach(row ->
                    dbGenericResult.add((row.getString(0))));
            if (rs.hasMorePages()) {
                rs.fetchNextPage().whenComplete((thisRs, thisError) -> {
                    processPagesAsync(thisRs, thisError);
                });
            } else {
                //Completed to read pages
                countDownLatch.countDown();
            }
        }
    }

    @Override
    public String execute(String query) throws InterruptedException {
        dbGenericResult.clear();
        countDownLatch = new CountDownLatch(1);

        CompletionStage<AsyncResultSet> futureRs =
                cqlSession.executeAsync(query);
        futureRs.whenComplete(this::processPagesAsync);

        countDownLatch.await(TIMEOUT, TimeUnit.SECONDS);

        return String.format("{\"resultSet\":[%s]}",
                dbGenericResult.parallelStream().collect(Collectors.joining(",")));
    }

    @Override
    public void start() {
        cqlSession = connectionKeyspacesFactory.buildSession();

    }

    @Override
    public void stop() {
        cqlSession.close();

    }
}
