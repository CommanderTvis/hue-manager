# Build stage
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy gradle wrapper and configuration files
COPY gradle gradle
COPY gradlew .
COPY gradlew.bat .
COPY gradle.properties .

RUN chmod +x ./gradlew

# Copy build files for dependency resolution
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle/libs.versions.toml gradle/

COPY shared/build.gradle.kts shared/
COPY server/build.gradle.kts server/
COPY composeApp/build.gradle.kts composeApp/
COPY androidApp/build.gradle.kts androidApp/

RUN mkdir -p shared/src/commonMain/kotlin \
    && mkdir -p server/src/main/kotlin \
    && mkdir -p composeApp/src/commonMain/kotlin \
    && mkdir -p androidApp/src/main/kotlin

RUN ./gradlew --version --no-daemon && \
    ./gradlew dependencies --no-daemon || true

# Copy source modules
COPY shared shared
COPY server server
COPY composeApp composeApp

# Build the fat JAR and Web assets
RUN ./gradlew :server:buildFatJar :composeApp:wasmJsBrowserDistribution --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
LABEL org.opencontainers.image.source=https://github.com/CommanderTvis/hue-manager
WORKDIR /app

RUN addgroup -S huemanager && adduser -S huemanager -G huemanager
USER huemanager

# Copy the built JAR
COPY --from=build /app/server/build/libs/*-all.jar app.jar

# Copy the web assets
COPY --from=build /app/composeApp/build/dist/wasmJs/productionExecutable /app/web

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/status || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
