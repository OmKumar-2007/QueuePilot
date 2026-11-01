#!/usr/bin/env bash
set -e

# Change directory to the script's folder
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
cd "$DIR"

echo "=========================================================="
echo "           Starting QueuePilot Application                "
echo "=========================================================="

# 1. Check if port 8080 is already occupied
if lsof -Pi :8080 -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "⚠️ Port 8080 is already in use. Stopping existing process on port 8080..."
    kill -9 $(lsof -t -i:8080) 2>/dev/null || true
    sleep 1
fi

# 2. Check if the JAR exists, or build it
if [ ! -f "target/queuepilot-1.0.0.jar" ]; then
    echo "📦 Building project JAR package..."
    if command -v mvn >/dev/null 2>&1; then
        mvn clean package -DskipTests
    elif [ -f "./mvnw" ]; then
        ./mvnw clean package -DskipTests
    else
        echo "❌ Neither 'mvn' nor './mvnw' found. Please ensure Java & Maven are installed."
        exit 1
    fi
fi

# 3. Launch application
echo "🚀 Launching QueuePilot on http://localhost:8080 ..."
exec java -jar target/queuepilot-1.0.0.jar
