FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home app
WORKDIR /app
COPY --from=build --chown=app:app /build/target/banco-xyz-batch-1.0.0.jar /app/app.jar
RUN mkdir -p /app/build/reportes && chown -R app:app /app/build
USER app
EXPOSE 8082
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent http://localhost:${PORT:-8082}/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.profiles.active=cloud"]
