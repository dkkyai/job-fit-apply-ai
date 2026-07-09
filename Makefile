# Top-level orchestration for the Dockerized stack.
#
# Docker Compose is the source of truth — every service (db, bridge, frontend,
# markserv, poller, jsearch, notifier, processor) is a compose service. Host-side
# prerequisites the processor needs: the CDP Chrome (launch-chrome-cdp.sh + its
# launchd watchdog) and the local LLM servers (oMLX :11436, Ollama :11434).
# Tailnet exposure is via Tailscale Serve on the host — see docs/tailscale-serve.md.

.DEFAULT_GOAL := help
SHELL := /bin/bash

.PHONY: help up down restart status serve doctor logs e2e processor-test

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
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

e2e: ## End-to-end smoke: submit a fixture JD to the bridge, wait for the PDF
	./scripts/e2e-smoke.sh

processor-test: ## In-container pipeline smoke on a sample JD (LLMs + PDF, no bridge)
	docker compose run --rm processor --test

logs: ## Tail container logs
	docker compose logs -f --tail=100
