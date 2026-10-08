# Multi-stage Docker build for Java E-Commerce Platform
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /app

# Copy all repository contents
COPY . .

# Ensure lib and out exist, auto-fetch dependencies if missing, and compile
RUN mkdir -p lib out && \
    if [ ! -f "lib/sqlite-jdbc-3.45.2.0.jar" ]; then \
      apt-get update && apt-get install -y curl && \
      curl -sSL -o lib/sqlite-jdbc-3.45.2.0.jar https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.45.2.0/sqlite-jdbc-3.45.2.0.jar && \
      curl -sSL -o lib/slf4j-api-1.7.36.jar https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar && \
      curl -sSL -o lib/slf4j-simple-1.7.36.jar https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/1.7.36/slf4j-simple-1.7.36.jar ; \
    fi && \
    javac -cp "lib/*" -d out src/*.java && \
    cp src/index.html src/privacy.html src/terms.html src/favicon.svg out/

# Production Runtime Stage (lean JRE image)
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Copy compiled classes and libraries from builder
COPY --from=builder /app/out ./out
COPY --from=builder /app/lib ./lib

# Create directory for persistent SQLite data
RUN mkdir -p /app/data

# Default environment configuration for Render
ENV PORT=10000
ENV DB_PATH=/app/data/shop.db

EXPOSE 10000

# Execute WebServer
CMD ["java", "-cp", "out:lib/*", "WebServer"]
