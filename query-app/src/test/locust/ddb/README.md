# Load and scale testing the AQP with Locust (DynamoDB mode)

Two pieces, kept deliberately separate:

* `seed_table.py` — writes **deterministic** data (items are a pure function of their indices),
  because benchmark runs are only comparable when every run aggregates exactly the same rows.
  `--print-expected` tells you what any correct engine must answer for one partition.
* `performance_test_aqp.py` — a Locust 2.x test that drives `POST /query-aggregation`,
  **validates** every response (status, shape, non-empty results for seeded partitions) and
  aggregates the proxy's per-query server-side `stats` into a summary printed at test stop.

## Setup

Python 3.11+:

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
```

Create the table (on-demand — no idle provisioned cost; switch to provisioned for a
throughput-controlled benchmark):

```bash
aws dynamodb create-table --table-name performance_test_table \
  --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S \
  --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST
```

## Seed a dataset tier

A "point" query aggregates one partition, so `--rows-per-partition` *is* the per-query row
count. Suggested tiers:

| Tier | Command | Rows per point query | Total items |
|---|---|---|---|
| small | `--accounts 10 --customers 10 --rows-per-partition 1000` | 1 000 | 100 000 |
| medium | `--accounts 10 --customers 10 --rows-per-partition 10000` | 10 000 | 1 000 000 |
| large | `--accounts 5 --customers 4 --rows-per-partition 100000` | 100 000 | 2 000 000 |

```bash
python3 seed_table.py --accounts 10 --customers 10 --rows-per-partition 1000
python3 seed_table.py --rows-per-partition 1000 --print-expected   # expected answers
```

Local smoke against DynamoDB Local: add `--endpoint-url http://localhost:8000`.

## Run

```bash
export AQP_SECRET=$(aws secretsmanager get-secret-value --secret-id aqp/reporting-app \
  --query SecretString --output text)
export AQP_ACCOUNTS=10 AQP_CUSTOMERS=10        # must match what you seeded

locust -f performance_test_aqp.py --headless -u 25 -r 5 -t 5m \
  --host https://<your internal ALB DNS name> --csv tier-small-partiql
```

Useful knobs:

* `--tags point` (single-partition aggregates) or `--tags multi` (`pk IN (...)` across
  `AQP_IN_PARTITIONS` partitions, default 10).
* `--csv <prefix>` writes the client-side percentiles; the final log line
  `SERVER_SIDE_STATS {...}` carries rows retrieved and server-side retrieve/aggregate time.
* Engine comparison: redeploy the proxy with `AQP_AGGREGATION_ENGINE=DUCKDB` and repeat the
  identical run; same dataset, same commands, two result sets.

For large runs, run Locust inside the VPC (the official `locustio/locust` container on
Fargate, master + workers via `--master` / `--worker`) so laptop networking does not pollute
the numbers; see [`docs/ecs-fargate.md`](../../../../../docs/ecs-fargate.md) for the service
itself.

## Reference results

Measured on the deployment from [`docs/ecs-fargate.md`](../../../../../docs/ecs-fargate.md):
2 Fargate tasks (ARM64) behind an internal ALB, DynamoDB on-demand, 25 users ramped at
5/s for 5 minutes, Locust itself on Fargate in the same VPC. Numbers are client-side
percentiles; "server retrieve / aggregate" come from the proxy's per-query `stats`.

PartiQL engine, by tier (1 vCPU / 2 GB per task):

| Tier | Rows per point query | Throughput | Point p50 / p95 | Server retrieve / aggregate (avg) |
|---|---|---|---|---|
| small | 1 000 | 46 req/s | 25 / 32 ms | 34 / 3 ms |
| medium | 10 000 | 6.5 req/s | 0.3–0.75 s / ~5 s | 2.0 / 1.1 s |
| large | 100 000 | ~1 req/s | saturated | 7.6 / 4.7 s |

The step from small to medium saturates 2 x 1 vCPU: latency becomes queueing-dominated
(medium p95 is ~10x its p50). Size vCPU to your tier before reading anything into percentiles.

Engine comparison, large tier at 2 vCPU / 4 GB per task — identical data and commands:

| Engine | Throughput | Point p50 | Point p95 | Server aggregate (avg) |
|---|---|---|---|---|
| PARTIQL | 4.1 req/s | ~5 s | ~11–12 s | 1 402 ms |
| DUCKDB | 7.6 req/s | ~2.4 s | ~5 s | 584 ms |

DuckDB roughly doubles throughput at this row count; cheaper aggregation also frees CPU for
the DynamoDB read loop, so retrieve time halves too. Multi-partition queries at the large
tier (10 x 100k rows) exceed `maxRows` and return 413 in both engines — that is the result-set
guardrail working, not an engine difference.

Two deployment settings matter for clean runs, both covered in the runbook: the ALB idle
timeout must exceed `AQP_QUERY_TIMEOUT_SECONDS` (the 60 s default turns slow queries into
504s), and `DUCKDB` needs the `tmp-init` sidecar (Fargate mounts the `/tmp` volume root-owned,
and the non-root task cannot extract the DuckDB native library without it).

## Interpreting failures

Every non-200 is a failure with its body in the error report. Two expected-by-design cases:
`413` means the query exceeded `maxRows`/`maxResultBytes` — a correctly rejected oversized
query, tier accordingly; `429` means DynamoDB throttling — raise table capacity or lower the
user count.
