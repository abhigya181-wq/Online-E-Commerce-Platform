#!/usr/bin/env bash
# Render Build Script for Java E-Commerce Platform
set -o errexit

echo "============================================="
echo "⚙️  Building Java E-Commerce Platform on Render"
echo "============================================="

# Ensure directories exist
mkdir -p lib out

# Auto-download SQLite & SLF4J dependencies if not committed in repository
if [ ! -f "lib/sqlite-jdbc-3.45.2.0.jar" ]; then
    echo "==> Downloading SQLite JDBC driver..."
    curl -sSL -o lib/sqlite-jdbc-3.45.2.0.jar https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.45.2.0/sqlite-jdbc-3.45.2.0.jar
fi

if [ ! -f "lib/slf4j-api-1.7.36.jar" ]; then
    echo "==> Downloading SLF4J API..."
    curl -sSL -o lib/slf4j-api-1.7.36.jar https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar
fi

if [ ! -f "lib/slf4j-simple-1.7.36.jar" ]; then
    echo "==> Downloading SLF4J Simple..."
    curl -sSL -o lib/slf4j-simple-1.7.36.jar https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/1.7.36/slf4j-simple-1.7.36.jar
fi

# Compile all source files into out/
echo "==> Compiling Java sources..."
javac -cp "lib/*" -d out src/*.java

echo "============================================="
echo "✅ Build completed successfully!"
echo "============================================="
