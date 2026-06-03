#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# docker-build.sh — Build and tag the ScaleKit Docker image
#
# Usage:  ./scripts/docker-build.sh [VERSION]
# Example: ./scripts/docker-build.sh 1.2.0
#          ./scripts/docker-build.sh          # defaults to 'latest'
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

VERSION=${1:-latest}
BUILD_DATE=$(date -u +'%Y-%m-%dT%H:%M:%SZ')
IMAGE_NAME="scalekit-app"

echo "═══════════════════════════════════════════"
echo "  Building ScaleKit Docker Image"
echo "  Version   : ${VERSION}"
echo "  Build Date: ${BUILD_DATE}"
echo "═══════════════════════════════════════════"

docker build \
  --build-arg BUILD_DATE="${BUILD_DATE}" \
  --build-arg VERSION="${VERSION}" \
  --tag "${IMAGE_NAME}:${VERSION}" \
  --tag "${IMAGE_NAME}:latest" \
  .

echo ""
echo "✓ Build complete!"
echo "  Tagged: ${IMAGE_NAME}:${VERSION}"
echo "  Tagged: ${IMAGE_NAME}:latest"
echo ""
echo "─── Run locally ─────────────────────────────"
echo "  docker-compose up -d"
echo ""
echo "─── Verify health ───────────────────────────"
echo "  curl http://localhost:8080/actuator/health"
echo ""
echo "─── Push to registry ────────────────────────"
echo "  docker tag ${IMAGE_NAME}:${VERSION} registry.example.com/${IMAGE_NAME}:${VERSION}"
echo "  docker push registry.example.com/${IMAGE_NAME}:${VERSION}"
