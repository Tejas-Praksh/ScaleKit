#!/bin/bash
echo "Stopping ScaleKit..."
pkill -f "spring-boot:run" 2>/dev/null
pkill -f "vite" 2>/dev/null
docker-compose down
echo "✅ All stopped."
