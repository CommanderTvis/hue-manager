# Build stage
FROM gradle:8.5-jdk21 AS build
WORKDIR /app

# Copy gradle files first for better caching
COPY gradle gradle
COPY gradlew .
COPY gradlew.bat .
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle.properties .

# Copy version catalog
COPY gradle/libs.versions.toml gradle/

# Copy source modules
COPY shared shared
COPY server server
COPY composeApp composeApp

# Build the fat JAR and Web assets
RUN ./gradlew :server:buildFatJar :composeApp:jsBrowserProductionWebpack --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create non-root user for security
RUN addgroup -S huemanager && adduser -S huemanager -G huemanager
USER huemanager

# Copy the built JAR
COPY --from=build /app/server/build/libs/*-all.jar app.jar

# Copy the web assets
COPY --from=build /app/composeApp/build/dist/js/productionExecutable /app/web

# Expose the server port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/status || exit 1

# Run the server
ENTRYPOINT ["java", "-jar", "app.jar"]