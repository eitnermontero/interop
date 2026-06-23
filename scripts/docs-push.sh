#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

REGISTRY="cr.sintesis.com.bo"
IMAGE="${REGISTRY}/middleware-core/mwc-developers-portal"
TAG="${1:-latest}"

echo "==> Building image ${IMAGE}:${TAG}..."
docker compose build mwc-developers-portal

if [ "${TAG}" != "latest" ]; then
  docker tag "${IMAGE}:latest" "${IMAGE}:${TAG}"
fi

echo "==> Logging in to ${REGISTRY}..."
#docker login "${REGISTRY}"

echo "==> Pushing ${IMAGE}:${TAG}..."
docker push "${IMAGE}:${TAG}"

if [ "${TAG}" != "latest" ]; then
  echo "==> Pushing ${IMAGE}:latest..."
  docker push "${IMAGE}:latest"
fi

echo ""
echo "=== Done ==="
echo "    ${IMAGE}:${TAG}"
