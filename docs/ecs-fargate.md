# Running the Aggregation Query Proxy on ECS Fargate

A security-first runbook for deploying the proxy as an ECS Fargate service behind an
Application Load Balancer. Every setting here was derived from the threat model of this
service: it holds read credentials to your data store and accepts bearer-token (HTTP Basic)
authentication, so TLS at the edge, least-privilege IAM, private networking and a hardened
container are not optional extras.

```
clients ──HTTPS (ACM cert)──> internal ALB ──HTTP :8080──> Fargate task (private subnet)
                                                                   │ task role: read-only data access
                                                                   ├──VPC endpoint──> DynamoDB / Keyspaces
                                                                   └──VPC endpoint──> Secrets Manager, ECR, Logs
```

## Security model at a glance

| Control | Setting |
|---|---|
| Transport | TLS terminates at the ALB (ACM); credentials are bearer tokens and must never cross plain HTTP |
| Identity | Two roles: *task role* reads only your tables; *execution role* pulls the image, writes logs, reads the client secrets. Neither can do the other's job |
| Secrets | Secrets Manager, injected by ECS at task start; never in the image, task definition `environment`, or shell history |
| Network | Tasks in private subnets, no public IP; security groups admit only ALB→task :8080; all AWS traffic via VPC endpoints, no NAT needed |
| Container | Read-only root filesystem, writable `/tmp` volume only, non-root uid 10001, raised `nofile`, pinned image digest |
| Admin port | 8081 binds loopback inside the task — unreachable from the VPC by construction. The ALB health-checks the unauthenticated `/ping` on 8080 |
| Abuse | WAF rate-based rule at the ALB; the app itself rejects non-SELECT statements, caps result sets (413) and bounds engine memory |

## 1. Prerequisites

```bash
export AWS_ACCOUNT_ID=<your account id>
export AWS_REGION=<region>
export VPC_ID=<vpc with at least two private subnets>
export PRIVATE_SUBNETS="subnet-aaa subnet-bbb"   # two AZs minimum
```

You need: a VPC with private subnets, an ACM certificate for the DNS name clients will call,
and the data store (a DynamoDB table, or an Amazon Keyspaces keyspace) in the same region.

This runbook assumes the common case: the proxy is **internal**, called only by your own
applications, so everything — ALB included — lives in private subnets and the VPC needs no
internet gateway, NAT, or public subnets at all. If clients genuinely come from the internet,
see the internet-facing variant in section 6.

## 2. Build and push the image

ECR with **immutable tags** and **scan-on-push** (Inspector), so a tag can never be silently
repointed and every pushed image is scanned for known CVEs:

```bash
aws ecr create-repository --repository-name simple-aggregation-query-app \
  --image-tag-mutability IMMUTABLE \
  --image-scanning-configuration scanOnPush=true
```

The image must match the task's `runtimePlatform`. `build-script/build.sh` builds for the
machine it runs on; on Apple Silicon that is `linux/arm64` — either set
`"cpuArchitecture": "ARM64"` in the task definition (cheaper per vCPU) or build for amd64:

```bash
docker buildx build --platform linux/amd64 -f build-script/Dockerfile \
  -t $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/simple-aggregation-query-app:$(git rev-parse --short HEAD) --push .
```

Reference the image **by digest** in the task definition (printed on push, or
`aws ecr describe-images`). Patching story: Dependabot keeps the base images and dependencies
current in this repo; when a CVE lands, pull, rebuild, push, redeploy — running tasks do not
self-patch.

## 3. Secrets

One secret per API client, generated, never typed:

```bash
aws secretsmanager create-secret --name aqp/reporting-app \
  --secret-string "$(openssl rand -base64 24)"
```

The task definition injects it as `AQP_REPORTING_APP_SECRET`. Add one secret + one `secrets`
entry per client in `users:`. **Rotation requires a redeploy**: the app reads secrets at
startup, so rotate, then `aws ecs update-service --force-new-deployment`.

## 4. IAM — two roles

**Execution role** (`aqp-execution-role`) — what ECS itself needs. Attach the AWS managed
`AmazonECSTaskExecutionRolePolicy` (ECR pull + logs) plus:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": "secretsmanager:GetSecretValue",
    "Resource": "arn:aws:secretsmanager:<REGION>:<ACCOUNT_ID>:secret:aqp/*"
  }]
}
```

**Task role** (`aqp-task-role`) — what the application needs, and nothing account-wide.
DynamoDB (set `AQP_DDB_HEALTHCHECK_TABLE` to the same table so the startup health check uses
the scopable `DescribeTable` instead of `ListTables`):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["dynamodb:PartiQLSelect", "dynamodb:DescribeTable"],
    "Resource": [
      "arn:aws:dynamodb:<REGION>:<ACCOUNT_ID>:table/<YOUR_TABLE>",
      "arn:aws:dynamodb:<REGION>:<ACCOUNT_ID>:table/<YOUR_TABLE>/index/*"
    ]
  }]
}
```

Amazon Keyspaces instead (SigV4 is already configured in `KeyspacesConnector.conf`):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": "cassandra:Select",
    "Resource": [
      "arn:aws:cassandra:<REGION>:<ACCOUNT_ID>:/keyspace/<KEYSPACE>/table/<TABLE>",
      "arn:aws:cassandra:<REGION>:<ACCOUNT_ID>:/keyspace/system*"
    ]
  }]
}
```

Both roles trust `ecs-tasks.amazonaws.com`. Deliberately absent: any write action, any
wildcard table, `ListTables`.

## 5. Network: endpoints and security groups

VPC endpoints keep every byte off the public internet and remove the need for a NAT gateway:

```bash
# Gateway endpoints (free): DynamoDB, and S3 for ECR image layers
aws ec2 create-vpc-endpoint --vpc-id $VPC_ID --service-name com.amazonaws.$AWS_REGION.dynamodb --route-table-ids <rtb-private>
aws ec2 create-vpc-endpoint --vpc-id $VPC_ID --service-name com.amazonaws.$AWS_REGION.s3        --route-table-ids <rtb-private>
# Interface endpoints: Secrets Manager, ECR (api + dkr), CloudWatch Logs
# and, for Keyspaces mode, com.amazonaws.$AWS_REGION.cassandra
for svc in secretsmanager ecr.api ecr.dkr logs; do
  aws ec2 create-vpc-endpoint --vpc-id $VPC_ID --vpc-endpoint-type Interface \
    --service-name com.amazonaws.$AWS_REGION.$svc --subnet-ids $PRIVATE_SUBNETS \
    --security-group-ids <endpoint-sg allowing 443 from task-sg>
done
```

Security groups — admit only what must talk, in both directions:

| SG | Inbound | Outbound |
|---|---|---|
| `alb-sg` | 443 from your internal clients' SGs/CIDRs only (0.0.0.0/0 solely for the internet-facing variant) | 8080 to `task-sg` |
| `task-sg` | 8080 from `alb-sg` only | 443 to the endpoint SG / DynamoDB prefix list; 9142 to the Cassandra endpoint (Keyspaces mode) |

Nothing opens 8081 anywhere: the admin port binds loopback inside the task and is unreachable
by design.

## 6. ALB and TLS

```bash
# Target group: health-check the unauthenticated liveness endpoint
aws elbv2 create-target-group --name aqp-tg --protocol HTTP --port 8080 \
  --vpc-id $VPC_ID --target-type ip \
  --health-check-path /ping --health-check-interval-seconds 15 \
  --healthy-threshold-count 2 --unhealthy-threshold-count 3
# Internal ALB in the PRIVATE subnets; HTTPS listener only, no plain-HTTP listener.
aws elbv2 create-load-balancer --name aqp-alb --scheme internal --type application \
  --subnets $PRIVATE_SUBNETS --security-groups <alb-sg>
```

Raise the ALB **idle timeout above the query budget** (`aws elbv2 modify-load-balancer-attributes
... --attributes Key=idle_timeout.timeout_seconds,Value=120`): the default 60 s is below
`AQP_QUERY_TIMEOUT_SECONDS=90`, so the ALB would return 504 and sever a long-running
aggregation the service itself still considers within budget.

TLS is still mandatory on an internal ALB: the client secrets are bearer tokens, and "inside
the VPC" is not a trust boundary. Use an ACM certificate for the private DNS name clients will
call (public ACM with DNS validation works for internal ALBs; ACM Private CA if you have one).

**Internet-facing variant** (only if clients really are outside your network): put the ALB in
public subnets with `--scheme internet-facing`, open `alb-sg` inbound 443 to the client CIDRs,
and attach a WAF web ACL with a **rate-based rule** (e.g. 500 requests / 5 min per IP) — the
proxy bounds a query's cost, WAF bounds the request rate.

Either way, prefer `POST` clients: the `GET` forms put query text (often customer identifiers)
into ALB access logs.

## 7. Task definition

Use [`deploy/ecs/task-definition.json`](../deploy/ecs/task-definition.json) and replace the
`<...>` placeholders. The non-obvious settings, each load-bearing:

* `readonlyRootFilesystem: true` with a single writable **`/tmp` volume** — verified: the
  PartiQL engine runs this way as-is; the **DuckDB engine additionally needs `/tmp` to allow
  `exec`** (its JDBC driver extracts a native library there), which a Fargate ephemeral-storage
  volume permits. Do not mount `/tmp` `noexec` with `DUCKDB`. One more Fargate wrinkle,
  verified the hard way: the ephemeral volume is mounted **root-owned `0755`**, so the
  non-root app (uid 10001) cannot write to it and DuckDB crashes at startup with
  `AccessDeniedException: /tmp/libduckdb_java*.so`. The task definition therefore runs a tiny
  root `tmp-init` sidecar (`chmod 1777 /tmp`, `essential: false`) that the app container
  `dependsOn` with `condition: SUCCESS`. Its image must be pullable from inside the VPC — in
  an endpoints-only network that means a copy in your private ECR, not Docker Hub.
* `AQP_QUERY_TIMEOUT_SECONDS=90`: bounds the data-store read of one query, in both DynamoDB
  and Keyspaces modes. Fargate caps `stopTimeout` at 120 s, so a budget above it means
  deployments cut in-flight queries; 90 s leaves headroom for the (fast) aggregation phase.
* `ulimits nofile 65536`: Jetty plus the data-store driver pools outgrow the default.
* `healthCheck`: deliberately a **liveness** probe (`/ping`), the same thing the ALB checks.
  The deep backend check runs once at startup (a task that cannot reach the store never enters
  service). It is *not* the recurring probe on purpose: probing the backend every 30 s would
  make a transient DynamoDB/Keyspaces blip fail every task at once and ECS would kill the whole
  fleet simultaneously — turning a dependency brownout into a self-inflicted outage.
* Memory: 2048 MB fits PartiQL comfortably. With `DUCKDB`, its engine memory is **native,
  outside the JVM heap** (ceiling ≈ 4× `maxResultBytes`, min 256 MB) — size the task so
  heap (75% of task memory by default) plus the engine ceiling fit, e.g. 4096 MB, or lower
  `-XX:MaxRAMPercentage`.

```bash
aws logs create-log-group --log-group-name /ecs/aggregation-query-proxy
aws ecs register-task-definition --cli-input-json file://deploy/ecs/task-definition.json
```

## 8. Service

```bash
aws ecs create-cluster --cluster-name aqp --settings name=containerInsights,value=enabled
aws ecs create-service --cluster aqp --service-name aggregation-query-proxy \
  --task-definition aggregation-query-proxy --desired-count 2 --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[$PRIVATE_SUBNETS],securityGroups=[<task-sg>],assignPublicIp=DISABLED}" \
  --load-balancers "targetGroupArn=<aqp-tg arn>,containerName=aggregation-query-proxy,containerPort=8080" \
  --deployment-configuration "deploymentCircuitBreaker={enable=true,rollback=true},minimumHealthyPercent=100,maximumPercent=200" \
  --health-check-grace-period-seconds 90
```

Two tasks across two AZs minimum; the circuit breaker rolls a bad deployment back
automatically. Add target-tracking auto-scaling on `ALBRequestCountPerTarget` or CPU.

## 9. Verify

```bash
export AQP_REPORTING_APP_SECRET=$(aws secretsmanager get-secret-value --secret-id aqp/reporting-app --query SecretString --output text)
curl -u reporting-app:$AQP_REPORTING_APP_SECRET -H 'Content-Type: application/json' \
  -d '{"query": "SELECT COUNT(*) AS n FROM <YOUR_TABLE>"}' \
  https://<your ALB DNS name>/query-aggregation
```

Also verify the negative space: plain-HTTP refused, `/query-aggregation` without credentials
is 401, port 8081 unreachable from anywhere, and a `DELETE` statement returns 400.

## 10. Operating notes

* **Metrics**: the admin `/metrics` endpoint is unreachable in `awsvpc` mode (loopback by
  design). Container Insights covers CPU/memory/task health; every response carries per-query
  `stats`. For engine metrics, `aws ecs execute-command` into a task and curl
  `127.0.0.1:8081/metrics` — leave ECS Exec disabled except while debugging.
* **Logs** land in CloudWatch; each request's lines carry its `X-Request-Id`. Keep logging at
  INFO: DEBUG writes push-down queries, including `WHERE` literals, into the log group.
* **Secret rotation** = rotate in Secrets Manager, then force a new deployment.
* **Patching** = pull latest `main` (Dependabot keeps bases current), rebuild, push, redeploy.
