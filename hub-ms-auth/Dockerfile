FROM eclipse-temurin:25-jdk-alpine AS build

ARG NEXUS_USERNAME
ARG NEXUS_PASSWORD

WORKDIR /workspace

COPY gradle/ gradle/
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY hub-ms-auth/ hub-ms-auth/
COPY hub-audit-commons/ hub-audit-commons/
COPY hub-gateway/build.gradle hub-gateway/build.gradle
COPY hub-ms-base/build.gradle hub-ms-base/build.gradle
COPY hub-frontend/build.gradle hub-frontend/build.gradle

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :hub-ms-auth:bootJar -x test -x processAot --no-daemon \
    -PnexusUsername="${NEXUS_USERNAME}" -PnexusPassword="${NEXUS_PASSWORD}"

FROM eclipse-temurin:25-jre-alpine AS runtime

WORKDIR /app

COPY --from=build /workspace/hub-ms-auth/build/libs/hub-ms-auth-*.jar app.jar
COPY hub-ms-auth/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

RUN apk add --no-cache su-exec && \
    addgroup -S appgroup && adduser -S appuser -G appgroup && \
    chmod +x /usr/local/bin/docker-entrypoint.sh

EXPOSE 8083

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
