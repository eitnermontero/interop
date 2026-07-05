FROM eclipse-temurin:25-jdk-alpine AS build

ARG NEXUS_USERNAME
ARG NEXUS_PASSWORD

WORKDIR /workspace

COPY gradle/ gradle/
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY hub-ms-base/ hub-ms-base/
COPY hub-gateway/build.gradle hub-gateway/build.gradle
COPY hub-ms-auth/build.gradle hub-ms-auth/build.gradle
COPY hub-audit-commons/build.gradle hub-audit-commons/build.gradle
COPY hub-frontend/build.gradle hub-frontend/build.gradle

RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :hub-ms-base:bootJar -x test -x processAot --no-daemon \
    -PnexusUsername="${NEXUS_USERNAME}" -PnexusPassword="${NEXUS_PASSWORD}"

FROM eclipse-temurin:25-jre-alpine AS runtime

WORKDIR /app

COPY --from=build /workspace/hub-ms-base/build/libs/hub-ms-base-*.jar app.jar
COPY hub-ms-base/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

RUN apk add --no-cache su-exec && \
    addgroup -S appgroup && adduser -S appuser -G appgroup && \
    sed -i 's/\r$//' /usr/local/bin/docker-entrypoint.sh && \
    chmod +x /usr/local/bin/docker-entrypoint.sh

EXPOSE 8081

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
