# syntax=docker/dockerfile:1.4
# Build context: repository root (needs the shared api module). See docker-compose.yml.
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /src

COPY pom.xml ./
COPY api/pom.xml api/
COPY backend/pom.xml backend/
COPY frontend/pom.xml frontend/
COPY api/src api/src
COPY frontend/src frontend/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl frontend -am package -DskipTests -Pproduction

FROM eclipse-temurin:25.0.3_9-jre-alpine
WORKDIR /app
COPY --from=build /src/frontend/target/app.jar app.jar

ENV JOURNAL_HTTP_HOST=0.0.0.0
EXPOSE 8081
USER 1001
ENTRYPOINT ["java", "-jar", "app.jar"]
