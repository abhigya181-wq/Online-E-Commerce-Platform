#!/usr/bin/env bash
# Render Start Script for Java E-Commerce Platform
set -o errexit

PORT_VAL="${PORT:-10000}"
echo "============================================="
echo "🚀 Starting Java WebServer on Port $PORT_VAL"
echo "============================================="

# Note: Linux uses colon ':' as classpath separator
exec java -cp "out:lib/*" WebServer
