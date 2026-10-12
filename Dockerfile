# One container: Spring Boot serves the API and the built React app on $PORT.

# 1. Build the frontend
FROM node:20-alpine AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# 2. Build the backend with the frontend bundled as static files
FROM maven:3.9-eclipse-temurin-17 AS api
WORKDIR /api
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=web /web/dist ./src/main/resources/static
RUN mvn -q -B package -DskipTests

# 3. Run
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 1001 fleet && mkdir -p /app/data && chown fleet /app/data
COPY --from=api /api/target/fleetboard-search-1.0.0.jar app.jar
USER fleet
# Sized for small free instances (512 MB).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k" \
    FLEET_STORE_FILE=/app/data/fleetboard-store.json
EXPOSE 8090
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
