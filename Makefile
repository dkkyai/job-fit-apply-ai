# Top-level orchestration for the Dockerized stack.
#
# Docker Compose is the source of truth. As services migrate off PM2 into compose
# (bridge/frontend next), `make up` covers more of the system. Tailnet exposure is
# via Tailscale Serve on the host — see docs/tailscale-serve.md.
#
# NOTE (during migration): services still under PM2 (jd-worker, and until they move,
# jd-bridge/job-backlog-web) are managed separately; `make doctor` reports their state.

.DEFAULT_GOAL := help
SHELL := /bin/bash

.PHONY: help up down restart status serve doctor logs

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

logs: ## Tail container logs
	docker compose logs -f --tail=100
