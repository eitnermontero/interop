FROM eclipse-temurin:25-jdk-alpine AS build

ARG NEXUS_USERNAME
ARG NEXUS_PASSWORD

WORKDIR /workspace

COPY gradle/ gradle/
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY hub-gateway/ hub-gateway/
COPY hub-audit-commons/ hub-audit-commons/
COPY hub-ms-base/build.gradle hub-ms-base/build.gradle
COPY hub-ms-auth/build.gradle hub-ms-auth/build.gradle
COPY hub-frontend/build.gradle hub-frontend/build.gradle

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :hub-gateway:bootJar -x test -x processAot --no-daemon \
    -PnexusUsername="${NEXUS_USERNAME}" -PnexusPassword="${NEXUS_PASSWORD}"

FROM eclipse-temurin:25-jre-alpine AS runtime

WORKDIR /app

COPY --from=build /workspace/hub-gateway/build/libs/hub-gateway-*.jar app.jar
COPY hub-gateway/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

RUN apk add --no-cache su-exec && \
    addgroup -S appgroup && adduser -S appuser -G appgroup && \
    chmod +x /usr/local/bin/docker-entrypoint.sh

EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
