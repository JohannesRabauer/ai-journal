# Build context: repository root (needs the shared api module). See docker-compose.yml.
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /src

COPY pom.xml ./
COPY api/pom.xml api/
COPY backend/pom.xml backend/
COPY api/src api/src
COPY backend/src backend/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl backend -am package -DskipTests

FROM eclipse-temurin:25.0.3_9-jre-alpine
WORKDIR /app
COPY --from=build /src/backend/target/quarkus-app/ /app/

# Serves the REST API and the web pages (static assets are in the jar). Published on 127.0.0.1 by compose.
ENV JOURNAL_HTTP_HOST=0.0.0.0
EXPOSE 8080
USER 1001
CMD ["java", "-jar", "quarkus-run.jar"]
