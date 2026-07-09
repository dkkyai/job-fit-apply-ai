# ── Build stage: assemble the runnable dist with the project's Gradle wrapper ──
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY . .
RUN ./gradlew --no-daemon installDist

# ── Runtime stage: JRE + the YAML→LaTeX→PDF toolchain. NO Chromium — scraping ──
# attaches to the host CDP Chrome over the network, and PDFs come from tectonic.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# python3 + pyyaml + jinja2 run yaml_to_tex.py; curl only fetches the tectonic binary.
RUN apt-get update && apt-get install -y --no-install-recommends \
      python3 python3-yaml python3-jinja2 curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# tectonic: self-contained XeTeX engine (pinned; musl builds ship for both arches).
ARG TECTONIC_VERSION=0.16.9
RUN ARCH="$(uname -m)" \
    && curl -fsSL "https://github.com/tectonic-typesetting/tectonic/releases/download/tectonic%40${TECTONIC_VERSION}/tectonic-${TECTONIC_VERSION}-${ARCH}-unknown-linux-musl.tar.gz" \
       | tar -xz -C /usr/local/bin tectonic \
    && tectonic --version

COPY --from=build /app/build/install/job-fit-apply-ai-pipeline/ /app/

# Config resolves skills/templates/toolchain relative to PROJECT_DIR (= WORKDIR /app),
# so the committed resources keep their repo-relative layout. Personal files
# (resume.yaml, candidate_profile.yaml, .env) are bind-mounted at runtime and excluded
# from the build context by .dockerignore.
COPY src/main/resources /app/src/main/resources
COPY config/candidate_profile.template.yaml /app/config/candidate_profile.template.yaml
COPY --chmod=755 docker-entrypoint.sh /app/docker-entrypoint.sh

# Warm-up compile = build gate + offline runtime: compiling the committed template résumé
# pre-fetches every TeX package the template uses into the cache, and fails the build if
# python/jinja2/fonts/tectonic are broken. Runtime compiles then need no network.
ENV XDG_CACHE_HOME=/opt/cache
RUN python3 /app/src/main/resources/resume/yaml_to_tex.py \
      /app/src/main/resources/resume/resume.template.yaml \
      -o /tmp/warmup/tailored_resume.tex --pdf \
    && test -s /tmp/warmup/tailored_resume.pdf \
    && rm -rf /tmp/warmup

# Container defaults (compose overrides as needed). The heartbeat lives in /tmp for --health.
# PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD: the scraper only ever attaches to the host Chrome over CDP
# (connectOverCDP), which needs the driver but NOT a local browser. Without this flag,
# Playwright.create() eagerly downloads Chromium+Firefox+WebKit (~500MB) on first use and hangs
# the container — this image ships no browsers by design.
ENV HEARTBEAT_FILE=/tmp/processor-heartbeat \
    JD_BRIDGE_URL=http://bridge:8765 \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

# Healthcheck execs the same binary with --health (exit 0 = processor loop alive).
HEALTHCHECK --interval=60s --timeout=15s --start-period=60s --retries=3 \
    CMD ["/app/bin/job-fit-apply-ai-pipeline", "--health"]

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["--processor"]
