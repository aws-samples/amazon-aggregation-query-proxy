FROM amazoncorretto:11

COPY /query-app-1.0-SNAPSHOT.jar /usr/app/query-app-1.0-SNAPSHOT.jar
COPY /KeyspacesConnector.conf /usr/app/KeyspacesConnector.conf
COPY /keyspaces-aggregation-query-proxy.yaml /usr/app/keyspaces-aggregation-query-proxy.yaml
WORKDIR /usr/app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "query-app-1.0-SNAPSHOT.jar", "server", "keyspaces-aggregation-query-proxy.yaml"]