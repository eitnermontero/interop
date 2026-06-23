FROM eclipse-temurin:25-jdk-alpine AS build

ARG NEXUS_USERNAME
ARG NEXUS_PASSWORD

WORKDIR /workspace

COPY gradle/ gradle/
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY mdqr-ms-base/ mdqr-ms-base/
COPY mdqr-gateway/build.gradle mdqr-gateway/build.gradle
COPY mdqr-ms-auth/build.gradle mdqr-ms-auth/build.gradle
COPY mdqr-audit-commons/build.gradle mdqr-audit-commons/build.gradle
COPY mdqr-frontend/build.gradle mdqr-frontend/build.gradle

RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :mdqr-ms-base:bootJar -x test -x processAot --no-daemon \
    -PnexusUsername="${NEXUS_USERNAME}" -PnexusPassword="${NEXUS_PASSWORD}"

FROM eclipse-temurin:25-jre-alpine AS runtime

WORKDIR /app

COPY --from=build /workspace/mdqr-ms-base/build/libs/mdqr-ms-base-*.jar app.jar
COPY mdqr-ms-base/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

RUN apk add --no-cache su-exec && \
    addgroup -S appgroup && adduser -S appuser -G appgroup && \
    sed -i 's/\r$//' /usr/local/bin/docker-entrypoint.sh && \
    chmod +x /usr/local/bin/docker-entrypoint.sh

EXPOSE 8081

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
