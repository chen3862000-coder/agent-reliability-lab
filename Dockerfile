# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:27-jdk AS build
WORKDIR /workspace
RUN apt-get update && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*

# Keep dependency resolution cached independently from source changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY arl-core/pom.xml arl-core/pom.xml
COPY arl-server/pom.xml arl-server/pom.xml
COPY arl-evaluation/pom.xml arl-evaluation/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress -pl arl-server -am dependency:go-offline

COPY arl-core/src arl-core/src
COPY arl-server/src arl-server/src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress -pl arl-server -am \
      -DskipTests -Dspotless.check.skip=true -Dcheckstyle.skip=true -Denforcer.skip=true package

FROM eclipse-temurin:27-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system arl \
    && useradd --system --gid arl --no-create-home --home-dir /app --shell /usr/sbin/nologin arl
COPY --from=build /workspace/arl-server/target/arl-server-0.1.0-SNAPSHOT.jar /app/arl-server.jar
RUN mkdir -p /data && chown -R arl:arl /app /data
USER arl
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=8 --start-period=30s CMD curl --fail --silent http://127.0.0.1:8080/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/arl-server.jar"]
