# syntax=docker/dockerfile:1
#
# Self-contained multi-stage build: compiles the Compose/Wasm SPA and the Quarkus
# server as a GraalVM native image, then ships the native binary + web assets on a
# minimal glibc base. Used by `docker compose up --build` and by CI.
#
# NOTE: the native build is memory-hungry (~6 GB) and slow (several minutes).

### Build stage — Quarkus Mandrel builder (GraalVM-for-Quarkus, JDK 21)
# Ships native-image + the full C toolchain (gcc, glibc-devel, zlib-devel) pre-installed and
# version-consistent, so no microdnf is needed (graalvm-community's base packages conflict with
# the current appstream repo). Run as root so Gradle can write build outputs under /app.
FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21 AS build
USER root
WORKDIR /app

# The Kotlin/Wasm build downloads a Node.js runtime whose binary needs libatomic at runtime.
RUN microdnf install -y libatomic && microdnf clean all

# Build scripts first for layer caching (androidApp is configured but never built here —
# it needs the Android SDK, which is absent; only :server and :composeApp tasks run).
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY shared/build.gradle.kts shared/
COPY server/build.gradle.kts server/
COPY composeApp/build.gradle.kts composeApp/
COPY androidApp/build.gradle.kts androidApp/
RUN chmod +x gradlew && ./gradlew --version

COPY shared shared
COPY server server
COPY composeApp composeApp

# Commit hash for BuildInfo.kt (git isn't available in the image); passed by CI.
ARG BUILD_COMMIT=unknown
ENV BUILD_COMMIT=$BUILD_COMMIT

# Build the web SPA and the native server binary (native only, no JAR).
# Config cache is disabled here: a fresh container gains nothing from it, and it keeps
# the build independent of the androidJdkImage cc limitation.
RUN ./gradlew --no-configuration-cache \
      :composeApp:wasmJsBrowserDistribution \
      :server:quarkusBuild -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false

### Runtime stage — minimal glibc base
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.4
LABEL org.opencontainers.image.source=https://github.com/CommanderTvis/hue-manager
WORKDIR /app

# ubi9-minimal already ships curl-minimal (provides /usr/bin/curl for the healthcheck);
# installing full curl conflicts with it, so don't.
RUN echo 'huemanager:x:1001:1001::/app:/sbin/nologin' >> /etc/passwd \
    && mkdir -p /app/data && chown -R 1001:1001 /app
USER 1001

# Native binary + web assets (served by SpaResource from ./web relative to WORKDIR).
COPY --from=build --chown=1001:1001 /app/server/build/*-runner ./server
COPY --from=build --chown=1001:1001 /app/composeApp/build/dist/wasmJs/productionExecutable ./web

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
    CMD curl -fsS http://localhost:8080/api/status || exit 1

ENTRYPOINT ["./server", "-Dquarkus.http.host=0.0.0.0"]
