# Top-level orchestration for the Dockerized stack.
#
# Docker Compose is the source of truth — every service (db, bridge, frontend,
# markserv, poller, jsearch, notifier, processor) is a compose service. Host-side
# prerequisites the processor needs: the CDP Chrome (launch-chrome-cdp.sh + its
# launchd watchdog) and the local LLM servers (oMLX :11436, Ollama :11434).
# Tailnet exposure is via Tailscale Serve on the host — see docs/tailscale-serve.md.

.DEFAULT_GOAL := help
SHELL := /bin/bash

.PHONY: help up down restart status serve doctor logs e2e e2e-up e2e-run e2e-down e2e-logs e2e-smoke processor-test

# ── E2E suite (services/job-fit-apply-ai-e2e) ────────────────────────────────
# Isolated compose project: own container names, host ports (bridge 18765,
# markserv 18081, postgres 15432) and ./.e2e state — safe to run while the
# production stack is up. REAL_LLM=1 skips the fake LLM and lets the processor
# hit the real local models on host :11436 (Tier B exact-value tests skip).
COMPOSE_E2E     := COMPOSE_PROJECT_NAME=jobfit-e2e docker compose -f docker-compose.yml -f docker-compose.e2e.yml
E2E_SERVICES    := db bridge markserv processor notifier
REAL_LLM        ?= 0

help: ## Show available targets
	@grep -E '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) \
	  | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-9s\033[0m %s\n", $$1, $$2}'

up: ## Start containers + configure Tailscale Serve
	docker compose up -d
	./scripts/setup-tailscale-serve.sh

down: ## Stop & remove containers (named volumes / data are kept)
	docker compose down

restart: ## Recreate containers from current compose config
	docker compose up -d --force-recreate

status: ## Show container status + Tailscale Serve config
	docker compose ps
	@echo
	@TS=$${TAILSCALE_BIN:-tailscale}; command -v $$TS >/dev/null 2>&1 || TS=/usr/local/bin/tailscale; $$TS serve status || true

serve: ## (Re)configure Tailscale Serve only
	./scripts/setup-tailscale-serve.sh

doctor: ## Check prerequisites & health (read-only)
	./scripts/doctor.sh

e2e: ## Full e2e cycle: up + run + down (REAL_LLM=1 for real local models)
	@$(MAKE) e2e-up && $(MAKE) e2e-run REAL_LLM=$(REAL_LLM); s=$$?; $(MAKE) e2e-down; exit $$s

e2e-up: ## Build + start the isolated e2e slice with fresh state, wait for health
	./scripts/e2e-ci-prepare.sh --fresh
	$(COMPOSE_E2E) up -d --build $(E2E_SERVICES)
	@echo "[e2e] waiting for containers to report healthy…"
	@for c in jobfit-e2e-db jobfit-e2e-bridge jobfit-e2e-markserv jobfit-e2e-processor jobfit-e2e-notifier; do \
	  deadline=$$((SECONDS + 240)); \
	  until [ "$$(docker inspect -f '{{.State.Health.Status}}' $$c 2>/dev/null)" = "healthy" ]; do \
	    [ $$SECONDS -ge $$deadline ] && { echo "[e2e] $$c not healthy after 240s → docker logs $$c"; exit 1; }; \
	    sleep 3; \
	  done; echo "[e2e] $$c healthy"; \
	done

e2e-run: ## Run the suite against the already-running e2e slice (ad-hoc loop)
	cd services/job-fit-apply-ai-e2e && \
	  E2E_REAL_LLM=$(REAL_LLM) \
	  E2E_TIMEOUT_SECONDS=$${E2E_TIMEOUT_SECONDS:-$(if $(filter 1,$(REAL_LLM)),1800,300)} \
	  ./gradlew test

e2e-down: ## Stop the e2e slice and remove its volumes
	$(COMPOSE_E2E) down -v --remove-orphans

e2e-logs: ## Tail the e2e slice's container logs
	$(COMPOSE_E2E) logs -f --tail=100

e2e-smoke: ## Legacy full-fat smoke against the REAL stack + real local models
	./scripts/e2e-smoke.sh

processor-test: ## In-container pipeline smoke on a sample JD (LLMs + PDF, no bridge)
	docker compose run --rm --no-deps processor --test

logs: ## Tail container logs
	docker compose logs -f --tail=100
