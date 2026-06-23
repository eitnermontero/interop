#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

echo "==> Building and starting API Reference docs..."
docker compose up mwc-developers-portal --build -d

echo ""
echo "=== Docs running ==="
echo "    API Reference: http://localhost:3001"
