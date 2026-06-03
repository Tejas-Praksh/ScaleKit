# Kubernetes Deployment Guide

## Architecture

```
Internet
   │
   ▼
[Ingress + TLS (cert-manager + Let's Encrypt)]
   │
   ▼
[Service: ClusterIP :80 → :8080]
   │
   ├── [Pod 1: scalekit-app]
   ├── [Pod 2: scalekit-app]      ← Minimum 2 pods (PDB enforced)
   └── [Pod N: auto-scaled by HPA up to 10]
          │
   ┌──────┴──────┐
   ▼             ▼
[Redis]    [PostgreSQL]
(in-cluster)  (external / Supabase / Render DB)
```

## Files Overview

| File | Purpose |
|---|---|
| `namespace.yml` | Isolated namespace for all ScaleKit resources |
| `configmap.yml` | Non-sensitive configuration |
| `secrets.yml` | Sensitive credentials (REPLACE before applying!) |
| `serviceaccount.yml` | Minimal-permission service account |
| `deployment.yml` | App pods with all 3 health probes + rolling update |
| `service.yml` | ClusterIP service routing traffic to pods |
| `ingress.yml` | NGINX ingress with TLS termination |
| `hpa.yml` | Horizontal Pod Autoscaler (CPU + memory metrics) |
| `pdb.yml` | Pod Disruption Budget (min 1 pod always running) |
| `redis-deployment.yml` | In-cluster Redis with liveness + readiness probes |

## Deployment Steps

### 1. Create namespace
```bash
kubectl apply -f k8s/namespace.yml
```

### 2. Apply secrets (edit first!)
```bash
# Edit k8s/secrets.yml and replace all REPLACE_ME values
# NEVER commit real secrets to git!
kubectl apply -f k8s/secrets.yml
```

### 3. Apply configuration
```bash
kubectl apply -f k8s/configmap.yml
kubectl apply -f k8s/serviceaccount.yml
```

### 4. Deploy Redis
```bash
kubectl apply -f k8s/redis-deployment.yml
```

### 5. Deploy the application
```bash
kubectl apply -f k8s/deployment.yml
kubectl apply -f k8s/service.yml
```

### 6. Configure autoscaling and availability
```bash
kubectl apply -f k8s/hpa.yml
kubectl apply -f k8s/pdb.yml
```

### 7. Configure ingress (requires nginx-ingress + cert-manager installed)
```bash
kubectl apply -f k8s/ingress.yml
```

### Or: Deploy everything at once
```bash
./scripts/k8s-deploy.sh 1.0.0
```

## Useful Commands

```bash
# Watch pods in real-time
kubectl get pods -n scalekit -w

# Check HPA status
kubectl get hpa -n scalekit

# Stream logs from all app pods
kubectl logs -n scalekit -l app=scalekit -f

# Manually scale to 5 replicas
kubectl scale deployment scalekit-app -n scalekit --replicas=5

# Trigger a rolling restart (forces new pods without config change)
kubectl rollout restart deployment/scalekit-app -n scalekit

# Check rollout status
kubectl rollout status deployment/scalekit-app -n scalekit

# Rollback to previous version
kubectl rollout undo deployment/scalekit-app -n scalekit

# Describe pod events (debug startup issues)
kubectl describe pod -n scalekit -l app=scalekit
```

## Health Probe Strategy

Three probes, three distinct purposes:

### `startupProbe`
> *"Is the app done starting?"*
- Runs until it passes; no other probes fire until then.
- Checks every 10s for up to **5 minutes** (`failureThreshold: 30`).
- Prevents premature kills during slow JVM/Spring startup.

### `livenessProbe`
> *"Is the app still alive?"*
- If this fails → K8s **kills and restarts** the pod.
- Checks `/actuator/health/liveness` (JVM health only).
- Does **NOT** check database — a DB outage should not cause pod restarts.

### `readinessProbe`
> *"Is the app ready to serve traffic?"*
- If this fails → pod is **removed from the load balancer** (but stays running).
- Checks `/actuator/health/readiness` (all dependencies: DB, Redis, etc.).
- Restores traffic automatically once dependencies recover.

## Resource Sizing Rationale

```
requests.memory: 256Mi
  └── Guaranteed allocation for scheduling.
      JVM needs at least 256MB to start Spring Boot.

limits.memory: 512Mi
  └── Hard cap. Pod is OOMKilled if exceeded.
      Set to 2x request to handle traffic spikes.

requests.cpu: 250m
  └── 0.25 CPU cores guaranteed.
      Sufficient for steady-state request serving.

limits.cpu: 500m
  └── 0.5 CPU cores maximum.
      Prevents one pod from starving neighboring pods.
```

## HPA Scaling Logic

```
CPU scaling example:
  Current pods: 2, each at 80% CPU
  Average = 80%, target = 70%
  Scale factor = 80 / 70 = 1.14
  New pods = ceil(2 × 1.14) = 3 pods

Scale-down stabilization (5 min window):
  Prevents "yo-yo" scaling.
  Traffic spike → scale up fast (60s window).
  Traffic drops → wait 5 minutes to confirm before scaling down.
  Removes at most 1 pod every 2 minutes to be conservative.
```

## Security Hardening

- **Non-root user**: Pod runs as UID 1000 (`runAsNonRoot: true`)
- **No service account token**: `automountServiceAccountToken: false`
- **Secrets via K8s Secrets**: never baked into image or ConfigMap
- **TLS termination**: at Ingress level via cert-manager
- **Rate limiting**: `limit-rps: 100` at Ingress level
- **Request body limit**: `proxy-body-size: 1m`
