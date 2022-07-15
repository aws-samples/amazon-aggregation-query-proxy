// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.aws.aqp.connectors;

import com.aws.aqp.application.AppConfiguration;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


public class CassandraExtractor extends Extractor {

    private final ConnectionKeyspacesFactory connectionKeyspacesFactory;
    private CqlSession cqlSession;
    private ConcurrentMap<String, String> dbGenericResult = new ConcurrentHashMap<>(10000, 0.7f, 4);
    private CountDownLatch countDownLatch;
    private static final long TIMEOUT = 360;
    private static final Logger LOGGER = LoggerFactory.getLogger(CassandraExtractor.class);

    public CassandraExtractor(AppConfiguration appConfiguration) {
        connectionKeyspacesFactory = new ConnectionKeyspacesFactory(appConfiguration);

    }

    void processPagesAsync(AsyncResultSet rs, Throwable error) {
        if (error != null) {
            LOGGER.error(error.getMessage());
            throw new RuntimeException(error);
        } else {
            rs.currentPage().forEach(row ->
                    dbGenericResult.put(UUID.randomUUID().toString(), (row.getString(0))));
            if (rs.hasMorePages()) {
                rs.fetchNextPage().whenComplete((thisRs, thisError) -> processPagesAsync(thisRs, thisError));
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
                dbGenericResult.values().parallelStream().collect(Collectors.joining(",")));
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
