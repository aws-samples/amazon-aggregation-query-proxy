# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: MIT-0

"""Locust (2.x) load test for the Aggregation Query Proxy, DynamoDB mode.

Queries go through POST /query-aggregation (the GET forms put query literals into access
logs and cannot carry every character). Each response is validated, not just status-checked,
and the proxy's own per-query ``stats`` (rows retrieved, server-side retrieve/aggregate time)
are aggregated and printed at test stop, so a run reports both client-side latency and what
the server actually did.

Configuration (environment variables):

    AQP_SECRET          required - secret of the API client
    AQP_USER            API client name            (default: reporting-app)
    AQP_TABLE           table to query             (default: performance_test_table)
    AQP_ACCOUNTS        account count the table was seeded with    (default: 10)
    AQP_CUSTOMERS       customer count the table was seeded with   (default: 10)
    AQP_IN_PARTITIONS   partitions per multi-partition query       (default: 10)
    AQP_WAIT_MIN/MAX    per-user think time, seconds               (default: 0 / 1)

The table must be seeded with seed_table.py (same ACCOUNTS/CUSTOMERS), which writes
deterministic data, so an empty result set for a seeded partition is a real failure.

Example (local):
    locust -f performance_test_aqp.py --headless -u 10 -r 2 -t 1m \
        --host http://localhost:8080 --csv run1

Select query shapes with tags: --tags point   (single-partition aggregates)
                               --tags multi   (multi-partition IN aggregates)
"""

import json
import os
from random import randrange, sample

from locust import HttpUser, between, events, tag, task

TABLE = os.environ.get("AQP_TABLE", "performance_test_table")
USER = os.environ.get("AQP_USER", "reporting-app")
SECRET = os.environ.get("AQP_SECRET")
ACCOUNTS = int(os.environ.get("AQP_ACCOUNTS", "10"))
CUSTOMERS = int(os.environ.get("AQP_CUSTOMERS", "10"))
IN_PARTITIONS = int(os.environ.get("AQP_IN_PARTITIONS", "10"))
WAIT_MIN = float(os.environ.get("AQP_WAIT_MIN", "0"))
WAIT_MAX = float(os.environ.get("AQP_WAIT_MAX", "1"))

QUOTED_TABLE = '"%s"' % TABLE

# Server-side numbers sampled from every successful response; summarised at test stop.
server_stats = {"requests": 0, "rows": 0, "retrieve_ms": 0, "aggregate_ms": 0,
                "max_retrieve_ms": 0, "max_aggregate_ms": 0, "max_rows": 0}


def random_pk():
    account = "ACCOUNT%d" % randrange(ACCOUNTS)
    customer = "CUSTOMER%d" % randrange(CUSTOMERS)
    return "ACCOUNT#%s#CUSTOMER#%s" % (account, customer)


def random_pks(n):
    """n *distinct* partition keys: DynamoDB rejects duplicate values in an IN list
    ("Overlapping conditions with range keys are not supported in where clause")."""
    picks = sample(range(ACCOUNTS * CUSTOMERS), min(n, ACCOUNTS * CUSTOMERS))
    return ["ACCOUNT#ACCOUNT%d#CUSTOMER#CUSTOMER%d" % (p // CUSTOMERS, p % CUSTOMERS)
            for p in picks]


class AqpUser(HttpUser):
    wait_time = between(WAIT_MIN, WAIT_MAX)

    def on_start(self):
        if not SECRET:
            raise RuntimeError("Set AQP_SECRET to the API client's secret before running.")
        # Locust's HttpSession sets trust_env=False, so requests silently ignores
        # REQUESTS_CA_BUNDLE; re-apply it so a private-CA or self-signed ALB cert can be
        # trusted (every request fails with status 0 otherwise).
        ca_bundle = os.environ.get("REQUESTS_CA_BUNDLE")
        if ca_bundle:
            self.client.verify = ca_bundle

    def run_query(self, name, query, expect_rows):
        with self.client.post(
                "/query-aggregation",
                json={"query": query},
                auth=(USER, SECRET),
                name=name,
                catch_response=True) as response:
            if response.status_code != 200:
                response.failure("%d: %s" % (response.status_code, response.text[:200]))
                return
            try:
                body = response.json()
                rows = body["response"][0]["resultSet"]
                stats = body["stats"]
            except (ValueError, KeyError, IndexError, TypeError) as e:
                response.failure("malformed response: %s" % e)
                return
            if expect_rows and len(rows) == 0:
                # Deterministically seeded partitions are never empty; this is a wrong answer.
                response.failure("empty resultSet for a seeded partition")
                return
            server_stats["requests"] += 1
            server_stats["rows"] += stats.get("rowsRetrieved", 0)
            server_stats["retrieve_ms"] += stats.get("elapsedTimeToRetrieveDataInMs", 0)
            server_stats["aggregate_ms"] += stats.get("elapsedTimeToAggregateDataInMs", 0)
            server_stats["max_rows"] = max(server_stats["max_rows"], stats.get("rowsRetrieved", 0))
            server_stats["max_retrieve_ms"] = max(
                server_stats["max_retrieve_ms"], stats.get("elapsedTimeToRetrieveDataInMs", 0))
            server_stats["max_aggregate_ms"] = max(
                server_stats["max_aggregate_ms"], stats.get("elapsedTimeToAggregateDataInMs", 0))
            response.success()

    @tag("point")
    @task(4)
    def sum_amount_by_zipcode(self):
        self.run_query(
            "point: sum(amount) group by zipcode",
            "select zipcode, pk, sum(amount) as total from %s where pk='%s' group by zipcode, pk"
            % (QUOTED_TABLE, random_pk()),
            expect_rows=True)

    @tag("point")
    @task(4)
    def count_orders_by_status(self):
        self.run_query(
            "point: count(sk) group by status",
            "select status, pk, count(sk) as total from %s where pk='%s' group by status, pk"
            % (QUOTED_TABLE, random_pk()),
            expect_rows=True)

    @tag("multi")
    @task(1)
    def sum_across_partitions(self):
        pks = ", ".join("'%s'" % pk for pk in random_pks(IN_PARTITIONS))
        self.run_query(
            "multi: sum(amount) over %d partitions group by zipcode" % IN_PARTITIONS,
            "select zipcode, sum(amount) as total from %s where pk in (%s) group by zipcode"
            % (QUOTED_TABLE, pks),
            expect_rows=True)


@events.quitting.add_listener
def print_server_side_summary(environment, **kwargs):
    n = server_stats["requests"]
    summary = {
        "successful_requests": n,
        "avg_rows_retrieved": round(server_stats["rows"] / n, 1) if n else 0,
        "max_rows_retrieved": server_stats["max_rows"],
        "avg_server_retrieve_ms": round(server_stats["retrieve_ms"] / n, 1) if n else 0,
        "max_server_retrieve_ms": server_stats["max_retrieve_ms"],
        "avg_server_aggregate_ms": round(server_stats["aggregate_ms"] / n, 1) if n else 0,
        "max_server_aggregate_ms": server_stats["max_aggregate_ms"],
    }
    print("SERVER_SIDE_STATS " + json.dumps(summary))
