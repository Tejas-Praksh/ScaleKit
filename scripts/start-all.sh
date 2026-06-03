#!/bin/bash
echo "🚀 Starting ScaleKit..."

# Check Docker
if ! docker info > /dev/null 2>&1; then
  echo "❌ Start Docker Desktop first"
  exit 1
fi

# Create logs directory if not exists
mkdir -p logs

# Start infrastructure
echo "Starting infrastructure..."
docker-compose up -d postgres redis prometheus grafana
sleep 15

# Start backend
echo "Starting backend..."
mvn spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  > logs/backend.log 2>&1 &

echo "Waiting for backend to boot..."
for i in $(seq 1 30); do
  STATUS=$(curl -s -o /dev/null \
    -w "%{http_code}" \
    http://localhost:8080/actuator/health)
  if [ "$STATUS" = "200" ]; then
    echo "✅ Backend ready!"
    break
  fi
  sleep 2
done

# Start frontend
echo "Starting frontend..."
cd scalekit-frontend
npm run dev > ../logs/frontend.log 2>&1 &
cd ..

echo ""
echo "✅ ScaleKit is running!"
echo ""
echo "🌐 Frontend:   http://localhost:3000"
echo "⚡ API:        http://localhost:8080"
echo "📚 Swagger:    http://localhost:8080/swagger-ui.html"
echo "📊 Grafana:    http://localhost:3001"
echo "🔍 Prometheus: http://localhost:9090"
