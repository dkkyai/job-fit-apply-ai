# Exposing containers on the tailnet (Tailscale Serve)

Docker's default `- "8081:8081"` binds `0.0.0.0` — i.e. **every** host interface, including
the LAN. For services that should only be reachable over Tailscale, that's too broad (anyone
on the same Wi-Fi could reach them). We use **Tailscale Serve** instead:

- The **container binds loopback only** (`127.0.0.1:<port>`), so it's invisible on the LAN.
- The **host's existing `tailscaled`** (already signed in) proxies the tailnet to that loopback
  port via `tailscale serve`. Tailnet-only, no hardcoded IP.

**No Tailscale runs inside Docker.** There is no `TS_AUTHKEY`, no sidecar, no second tailnet
node — the host's Tailscale is the only identity. (You'd only run Tailscale *in* a container
if you wanted each service to be its own tailnet node, e.g. the `tailscale/tailscale` sidecar
with an auth key — not needed when everything runs on one host that's already on the tailnet.)

## The pattern (per service)

1. In `docker-compose.yml`, bind the published port to loopback:
   ```yaml
   ports:
     - "127.0.0.1:<port>:<port>"
   ```
2. On the host, front it with serve (once; persisted by tailscaled across reboots):
   ```bash
   tailscale serve --bg --http=<port> http://127.0.0.1:<port>
   ```
   `--http=<port>` keeps plaintext HTTP on that tailnet port (no cert needed) and preserves the
   existing `http://<tailscale-name>:<port>/...` URLs. Use `tailscale serve --bg <port>` instead
   if you want HTTPS on 443 (changes URLs to `https://<tailscale-name>/...`).

## Current mappings

All three are configured by `scripts/setup-tailscale-serve.sh` (run via `make up` / `make serve`).

| Service | Container bind | Serve command | URL |
|---------|----------------|---------------|-----|
| markserv | `127.0.0.1:8081` | `tailscale serve --bg --http=8081 http://127.0.0.1:8081` | `http://<tailscale-name>:8081/` |
| bridge | `127.0.0.1:8765` | `tailscale serve --bg --http=8765 http://127.0.0.1:8765` | `http://<tailscale-name>:8765/` |
| frontend | `127.0.0.1:3030` | `tailscale serve --bg --http=3030 http://127.0.0.1:3030` | `http://<tailscale-name>:3030/` |

## Handy commands

```bash
tailscale serve status            # show current proxies
tailscale serve --http=8081 off   # remove one proxy
tailscale serve reset             # remove all serve config
```

## Notes
- Serve config lives in `tailscaled` state and is restored on reboot; the containers come back
  via Docker's `restart: unless-stopped` (needs Docker Desktop "start on login").
- The `http://…:8081` scheme/port is preserved deliberately: stored `tracks.artifact_url`
  values and the pipeline's `ARTIFACT_BASE_URL` point there.
