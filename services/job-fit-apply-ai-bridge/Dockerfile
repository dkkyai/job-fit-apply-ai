# ── Build stage: compile the fat jar with the project's Gradle wrapper (8.5) ──
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY . .
RUN ./gradlew --no-daemon shadowJar

# ── Runtime stage: JRE + fat jar ──────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# curl only for the container healthcheck
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/build/libs/jd-bridge-ktor-0.1.0.jar app.jar
EXPOSE 8765
# Config comes from the environment (compose). JD_BRIDGE_HOST=0.0.0.0 so the app
# binds all container interfaces; JD_BRIDGE_STORE_DIR points at the mounted volume.
ENTRYPOINT ["java", "-jar", "app.jar"]
