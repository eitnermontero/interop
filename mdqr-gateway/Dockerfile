FROM eclipse-temurin:25-jdk-alpine AS build

ARG NEXUS_USERNAME
ARG NEXUS_PASSWORD

WORKDIR /workspace

COPY gradle/ gradle/
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY mdqr-gateway/ mdqr-gateway/
COPY mdqr-audit-commons/ mdqr-audit-commons/
COPY mdqr-ms-base/build.gradle mdqr-ms-base/build.gradle
COPY mdqr-ms-auth/build.gradle mdqr-ms-auth/build.gradle
COPY mdqr-frontend/build.gradle mdqr-frontend/build.gradle

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :mdqr-gateway:bootJar -x test -x processAot --no-daemon \
    -PnexusUsername="${NEXUS_USERNAME}" -PnexusPassword="${NEXUS_PASSWORD}"

FROM eclipse-temurin:25-jre-alpine AS runtime

WORKDIR /app

COPY --from=build /workspace/mdqr-gateway/build/libs/mdqr-gateway-*.jar app.jar
COPY mdqr-gateway/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

RUN apk add --no-cache su-exec && \
    addgroup -S appgroup && adduser -S appuser -G appgroup && \
    chmod +x /usr/local/bin/docker-entrypoint.sh

EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
