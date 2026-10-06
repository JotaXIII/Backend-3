FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY cloud/ ./cloud/
RUN --mount=type=cache,target=/root/.m2 mvn -B -f cloud/pom.xml package -Dmaven.test.skip=true
ARG SERVICE
RUN cp cloud/${SERVICE}/target/${SERVICE}-1.0.0.jar /build/app.jar

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER app
ENTRYPOINT ["java", "-jar", "app.jar"]
