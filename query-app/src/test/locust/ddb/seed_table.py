#!/usr/bin/env python3
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: MIT-0

"""Deterministic test-data seeder for the performance table.

Replaces the old random Locust write generator: benchmark results are only comparable when
every run aggregates exactly the same data, so items are a pure function of their indices.
Re-running is idempotent (same keys overwrite the same items).

Layout, matching performance_test_aqp.py:
    pk = ACCOUNT#ACCOUNT<a>#CUSTOMER#CUSTOMER<c>     a < --accounts, c < --customers
    sk = ORDER#<i zero-padded>                        i < --rows-per-partition
    amount  = (i % 97) + 0.25                         so per-partition SUM is predictable
    zipcode = 11221 + (i % 50)                        so group-by-zipcode yields <= 50 groups
    status  = round-robin over 7 statuses

Partition count = accounts x customers; total items = partitions x rows-per-partition.
A "point" query reads one partition: size your tier with --rows-per-partition.

Examples:
    # Local smoke (DynamoDB Local): 9 partitions x 50 rows = 450 items
    python3 seed_table.py --endpoint-url http://localhost:8000 \
        --accounts 3 --customers 3 --rows-per-partition 50

    # 10k-rows-per-query tier: 100 partitions x 10000 rows = 1M items
    python3 seed_table.py --accounts 10 --customers 10 --rows-per-partition 10000

    # Print the expected per-partition aggregates for validation
    python3 seed_table.py --rows-per-partition 10000 --print-expected
"""

import argparse
import sys
import time
from decimal import Decimal

STATUSES = ["CREATED", "PAYED", "CANCELED", "PROCESSED", "SHIPPED", "DELIVERED", "RETURNED"]
BATCH = 25


def item(a, c, i):
    return {
        "pk": {"S": f"ACCOUNT#ACCOUNT{a}#CUSTOMER#CUSTOMER{c}"},
        "sk": {"S": f"ORDER#{i:08d}"},
        "amount": {"N": str(Decimal(i % 97) + Decimal("0.25"))},
        "zipcode": {"N": str(11221 + (i % 50))},
        "status": {"S": STATUSES[i % len(STATUSES)]},
        "username": {"S": f"user{(a * 31 + c * 17 + i) % 1000}"},
    }


def expected(rows):
    """What any correct engine must return for one seeded partition."""
    total = sum(Decimal(i % 97) + Decimal("0.25") for i in range(rows))
    return {
        "rows_per_partition": rows,
        "sum_amount": str(total),
        "count_sk": rows,
        "zipcode_groups": min(rows, 50),
        "status_groups": min(rows, len(STATUSES)),
    }


def write_all(client, table, accounts, customers, rows):
    total = accounts * customers * rows
    written = 0
    started = time.time()
    batch = []

    def flush():
        pending = {table: batch[:]}
        while pending[table]:
            response = client.batch_write_item(RequestItems=pending)
            pending = response.get("UnprocessedItems") or {table: []}
            if pending.get(table):
                time.sleep(0.2)  # throttled: brief back-off, then retry the leftovers
        batch.clear()

    for a in range(accounts):
        for c in range(customers):
            for i in range(rows):
                batch.append({"PutRequest": {"Item": item(a, c, i)}})
                if len(batch) == BATCH:
                    flush()
                    written += BATCH
                    if written % 10000 == 0:
                        rate = written / max(time.time() - started, 0.001)
                        print(f"{written}/{total} items ({rate:.0f}/s)", flush=True)
    if batch:
        written += len(batch)
        flush()
    print(f"done: {written} items into {table} in {time.time() - started:.0f}s")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--table", default="performance_test_table")
    parser.add_argument("--region", default=None, help="defaults to the AWS SDK's resolution")
    parser.add_argument("--endpoint-url", default=None, help="e.g. http://localhost:8000 for DynamoDB Local")
    parser.add_argument("--accounts", type=int, default=10)
    parser.add_argument("--customers", type=int, default=10)
    parser.add_argument("--rows-per-partition", type=int, default=100)
    parser.add_argument("--print-expected", action="store_true",
                        help="print the expected per-partition aggregates and exit (no writes)")
    args = parser.parse_args()

    if args.print_expected:
        import json
        print(json.dumps(expected(args.rows_per_partition), indent=2))
        return

    import boto3  # deferred so --help/--print-expected need no AWS SDK
    client = boto3.client("dynamodb", region_name=args.region, endpoint_url=args.endpoint_url)
    total = args.accounts * args.customers * args.rows_per_partition
    print(f"seeding {args.accounts * args.customers} partitions x {args.rows_per_partition} rows "
          f"= {total} items into {args.table}")
    if total > 5_000_000:
        sys.exit("refusing to write more than 5M items in one run; split the seeding")
    write_all(client, args.table, args.accounts, args.customers, args.rows_per_partition)


if __name__ == "__main__":
    main()
