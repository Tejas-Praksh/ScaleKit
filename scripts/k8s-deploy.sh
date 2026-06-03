#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# k8s-deploy.sh — Deploy ScaleKit to Kubernetes with zero-downtime rolling update
#
# Usage:  ./scripts/k8s-deploy.sh [IMAGE_TAG]
# Example: ./scripts/k8s-deploy.sh 1.2.0
#          ./scripts/k8s-deploy.sh          # defaults to 'latest'
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

NAMESPACE="scalekit"
IMAGE_TAG=${1:-latest}
DEPLOYMENT="scalekit-app"

echo "═══════════════════════════════════════════"
echo "  Deploying ScaleKit to Kubernetes"
echo "  Namespace : ${NAMESPACE}"
echo "  Image Tag : ${IMAGE_TAG}"
echo "═══════════════════════════════════════════"

# Apply all manifests (idempotent)
echo "[1/4] Applying namespace and RBAC..."
kubectl apply -f k8s/namespace.yml
kubectl apply -f k8s/serviceaccount.yml

echo "[2/4] Applying configuration..."
kubectl apply -f k8s/configmap.yml
echo "  ⚠ Remember to apply secrets separately (never auto-apply from git):"
echo "    kubectl apply -f k8s/secrets.yml"

echo "[3/4] Deploying workloads..."
kubectl apply -f k8s/redis-deployment.yml
kubectl apply -f k8s/deployment.yml
kubectl apply -f k8s/service.yml
kubectl apply -f k8s/hpa.yml
kubectl apply -f k8s/pdb.yml
kubectl apply -f k8s/ingress.yml

# Update image tag and trigger rolling update
echo "[4/4] Updating image to ${IMAGE_TAG} and waiting for rollout..."
kubectl set image \
  deployment/${DEPLOYMENT} \
  scalekit="scalekit-app:${IMAGE_TAG}" \
  -n "${NAMESPACE}"

kubectl rollout status \
  deployment/${DEPLOYMENT} \
  -n "${NAMESPACE}" \
  --timeout=300s

echo ""
echo "✓ Deployment complete!"
echo ""
kubectl get pods -n "${NAMESPACE}"
echo ""
echo "─── Useful commands ─────────────────────────"
echo "  Watch pods:  kubectl get pods -n ${NAMESPACE} -w"
echo "  Check HPA:   kubectl get hpa -n ${NAMESPACE}"
echo "  View logs:   kubectl logs -n ${NAMESPACE} -l app=scalekit -f"
echo "  Rollback:    kubectl rollout undo deployment/${DEPLOYMENT} -n ${NAMESPACE}"
