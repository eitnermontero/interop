#!/bin/sh
set -e

mkdir -p /app/logs
chown -R appuser:appgroup /app/logs

exec su-exec appuser java \
    --enable-native-access=ALL-UNNAMED \
    -XX:+UseG1GC \
    "-XX:MaxRAMPercentage=75.0" \
    -Djava.security.egd=file:/dev/./urandom \
    -jar /app/app.jar
