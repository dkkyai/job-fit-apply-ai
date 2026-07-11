# markserv — renders the pipeline's output directory (report.md + artifacts) as HTML.
# Stateless; the output dir is bind-mounted read-only at /data by docker-compose.
FROM node:20-alpine

# Pin to the version that was running under launchd (ai.dkky.markserv).
RUN npm install -g markserv@1.17.4

EXPOSE 8081

# Bind 0.0.0.0 inside the container; compose publishes 8081 to the host, and
# tailscaled (on the host) serves it on the Tailscale interface — so the existing
# http://<tailscale-name>:8081/.../report.md URLs keep working unchanged.
CMD ["markserv", "/data", "--port", "8081", "--address", "0.0.0.0", "--browser", "false", "--silent"]
