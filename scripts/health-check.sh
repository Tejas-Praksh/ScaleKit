#!/bin/bash
echo "🏥 ScaleKit Health Check"
echo "========================"

check() {
  NAME=$1
  URL=$2
  STATUS=$(curl -s -o /dev/null \
    -w "%{http_code}" "$URL" 2>/dev/null)
  if [ "$STATUS" = "200" ]; then
    echo "✅ $NAME"
  else
    echo "❌ $NAME (HTTP $STATUS)"
  fi
}

check "Backend" \
  "http://localhost:8080/actuator/health"
check "URL Shortener" \
  "http://localhost:8080/api/v1/urls/health"
check "Rate Limiter" \
  "http://localhost:8080/api/v1/rate-limiter/config"
check "Prometheus" \
  "http://localhost:9090/-/healthy"
check "Grafana" \
  "http://localhost:3001/api/health"
