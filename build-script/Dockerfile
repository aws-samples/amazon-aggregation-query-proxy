# Build from the repository root:
#   docker build -f build-script/Dockerfile -t simple-aggregation-query-app .
#
# Base images are pinned by digest so a rebuild produces the same image. Update the tag and the
# digest together.

# ---- Build stage: compiles and runs unit tests; integration tests skip without DynamoDB Local.
FROM maven:3-amazoncorretto-25-al2023@sha256:cbe7e5baedf0b1fb0ad32d6e9d2610079d3c9c572faea31018084de7dd0b3553 AS build
WORKDIR /src
COPY pom.xml ./
COPY query-app/pom.xml query-app/
# Resolve dependencies in their own layer so source edits don't re-download them.
RUN mvn -B -q -pl query-app dependency:go-offline
COPY query-app/ query-app/
RUN mvn -B -q package && cp query-app/target/query-app-*.jar /src/app.jar

# ---- Runtime stage: headless JRE only, no build tools or sources.
FROM amazoncorretto:26.0.1-al2023-headless@sha256:3bc31df9c6bf4fadda659784c6c3cee683b4f6c016f6177c58ce0dcb143bd7e6
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
# --enable-native-access: the optional DuckDB engine loads a native library; without the flag
# newer JDKs warn today and will refuse tomorrow.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED"

# The admin connector listens on loopback only; probe it from inside the container. Uses bash's
# /dev/tcp so the image needs no curl.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081 && printf "GET /healthcheck HTTP/1.0\r\n\r\n" >&3 && head -n1 <&3 | grep -q " 200 "'

ENTRYPOINT ["java", "-jar", "app.jar", "server", "keyspaces-aggregation-query-proxy.yaml"]
