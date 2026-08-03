# JFAA persistent data-root migration

`JFAA_DATA_ROOT` defines the host root for JFAA's four Docker bind mounts:

```text
${JFAA_DATA_ROOT}/bridge
${JFAA_DATA_ROOT}/jsearch-state
${JFAA_DATA_ROOT}/notifier-state
${JFAA_DATA_ROOT}/poller-secrets
```

The Compose fallback for a fresh deployment is `${HOME}/.local/share/jfaa`.

> **Upgrade required:** releases containing this setting do not automatically move an existing
> `~/.openclaw/jd-*` installation. Migrate the data and set `JFAA_DATA_ROOT` before recreating
> JFAA services; otherwise Compose creates fresh directories at the new fallback locations.

## Recommended roots

| Host | `JFAA_DATA_ROOT` |
|---|---|
| macOS | `/Users/<user>/Library/Application Support/JFAA` |
| Ubuntu service host | `/var/lib/jfaa` |
| Ubuntu user-managed development | `/home/<user>/.local/share/jfaa` |
| GitHub Actions E2E | Not used; the E2E overlay binds `./.e2e/` instead |

## Safe migration

The following example migrates a macOS deployment. Substitute the paths for another host.

1. **Choose and configure the new root** in the gitignored repository-root `.env`:

   ```env
   JFAA_DATA_ROOT="/Users/<user>/Library/Application Support/JFAA"
   ```

2. **Stop every writer before the final copy.** Stop Processor first so it cannot submit work while
   Bridge is unavailable, then stop the stateful consumers and Bridge:

   ```bash
   docker compose stop processor poller jsearch notifier bridge
   ```

3. **Create target directories and copy metadata/permissions.** The Poller directory holds Gmail
   OAuth material and must remain private:

   ```bash
   DATA_ROOT="/Users/<user>/Library/Application Support/JFAA"
   mkdir -p "$DATA_ROOT"
   rsync -a "$HOME/.openclaw/jd-bridge/"         "$DATA_ROOT/bridge/"
   rsync -a "$HOME/.openclaw/jd-jsearch-state/"  "$DATA_ROOT/jsearch-state/"
   rsync -a "$HOME/.openclaw/jd-notifier-state/" "$DATA_ROOT/notifier-state/"
   rsync -a "$HOME/.openclaw/jd-poller-secrets/" "$DATA_ROOT/poller-secrets/"
   chmod 700 "$DATA_ROOT/poller-secrets"
   ```

4. **Validate the resolved mounts before starting services:**

   ```bash
   docker compose config
   ```

   Confirm the source paths for Bridge, JSearch, Notifier, and Poller are under `$DATA_ROOT`.

5. **Recreate the affected services and restore the Processor:**

   ```bash
   docker compose up -d --force-recreate bridge poller jsearch notifier
   docker compose up -d --force-recreate processor
   docker compose ps
   ./scripts/doctor.sh
   ```

6. **Keep the old directories as a rollback copy** until the services are healthy and the next
   Gmail refresh / JSearch run / notification cycle completes. Do not delete the source directories
   during the cutover.

## Rollback

1. Stop the same writers.
2. Remove or revert `JFAA_DATA_ROOT` in `.env` and restore the previous Compose configuration.
3. Recreate `bridge poller jsearch notifier processor`.

No data copy is needed for rollback while the old directories are retained.

## E2E isolation

`docker-compose.e2e.yml` does not use `JFAA_DATA_ROOT`. Its normal service set excludes Poller and
JSearch. If the intentionally disabled `e2e-disabled` profile is enabled, their mounts still point
only to `./.e2e/`, JSearch has no API key, and notifier credentials remain test-only.
