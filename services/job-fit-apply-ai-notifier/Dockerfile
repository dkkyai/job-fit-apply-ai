# ── Build stage: assemble the runnable dist ──
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY . .
RUN ./gradlew --no-daemon installDist

# ── Runtime stage: JRE + dist (no browser, no LLM) ──
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/build/install/job-fit-apply-ai-notifier/ /app/

ENV JD_BRIDGE_URL=http://bridge:8765 \
    NOTIFIER_CURSOR_FILE=/state/notifier-cursor.txt \
    HEARTBEAT_FILE=/tmp/notifier-heartbeat

# Long-running poll loop; healthcheck execs --health (fresh heartbeat = loop alive).
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD ["/app/bin/job-fit-apply-ai-notifier", "--health"]

ENTRYPOINT ["/app/bin/job-fit-apply-ai-notifier"]
CMD ["--poll"]
