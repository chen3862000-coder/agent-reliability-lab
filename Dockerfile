FROM eclipse-temurin:27-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./mvnw --batch-mode --no-transfer-progress -pl arl-server -am -DskipTests package

FROM eclipse-temurin:27-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/arl-server/target/arl-server-0.1.0-SNAPSHOT.jar /app/arl-server.jar
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=8 --start-period=30s CMD curl --fail --silent http://127.0.0.1:8080/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/arl-server.jar"]
