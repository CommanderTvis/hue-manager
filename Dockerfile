# Build stage
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy gradle files first for better caching
COPY gradle gradle
COPY gradlew .
COPY gradlew.bat .
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle.properties .
COPY gradle/libs.versions.toml gradle/

# Copy source modules
COPY shared shared
COPY server server
COPY composeApp composeApp

# Build the fat JAR and Web assets
RUN ./gradlew :server:buildFatJar :composeApp:jsBrowserDistribution --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
LABEL org.opencontainers.image.source=https://github.com/CommanderTvis/hue-manager
WORKDIR /app

RUN addgroup -S huemanager && adduser -S huemanager -G huemanager
USER huemanager

# Copy the built JAR
COPY --from=build /app/server/build/libs/*-all.jar app.jar

# Copy the web assets
COPY --from=build /app/composeApp/build/dist/js/productionExecutable /app/web

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/status || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
