# Build from the repository root:
#   docker build -f build-script/Dockerfile -t simple-aggregation-query-app .
#
# Base images are pinned by digest so a rebuild produces the same image. Update the tag and the
# digest together.

# ---- Build stage: compiles and runs unit tests; integration tests skip without DynamoDB Local.
FROM maven:3-amazoncorretto-24-al2023@sha256:9bd1d20d8b70542833657acced078c7ac146a280c048abd5f57dc9a44e1e8b20 AS build
WORKDIR /src
COPY pom.xml ./
COPY query-app/pom.xml query-app/
# Resolve dependencies in their own layer so source edits don't re-download them.
RUN mvn -B -q -pl query-app dependency:go-offline
COPY query-app/ query-app/
RUN mvn -B -q package && cp query-app/target/query-app-*.jar /src/app.jar

# ---- Runtime stage: headless JRE only, no build tools or sources.
FROM amazoncorretto:21.0.12-al2023-headless@sha256:26f4ff28f9214a2b32a99e824d9375f5669bfa47d3a1f0b07b3afb60de9c0281
WORKDIR /usr/app

COPY --from=build /src/app.jar ./app.jar
# Neither file contains secrets: Keyspaces authenticates with SigV4 via the AWS credential chain,
# and client secrets are read from environment variables at startup.
COPY query-app/conf/KeyspacesConnector.conf ./
COPY query-app/conf/keyspaces-aggregation-query-proxy.yaml ./

# Unprivileged. A numeric UID needs no useradd in the image and is recognised as non-root by
# orchestrators that enforce runAsNonRoot. Files above are root-owned and read-only to it.
USER 10001:10001

EXPOSE 8080

# Without these the JVM sizes its heap from the host's memory rather than the container limit
# and is OOM-killed under load. Aggregation happens on-heap, so leave it most of the limit.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# The admin connector listens on loopback only; probe it from inside the container. Uses bash's
# /dev/tcp so the image needs no curl.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081 && printf "GET /healthcheck HTTP/1.0\r\n\r\n" >&3 && head -n1 <&3 | grep -q " 200 "'

ENTRYPOINT ["java", "-jar", "app.jar", "server", "keyspaces-aggregation-query-proxy.yaml"]
