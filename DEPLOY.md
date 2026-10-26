# The `deploy` branch

`deploy` is this fork's deployment branch: upstream
[`wasmcomputercompany/wasmo`](https://github.com/wasmcomputercompany/wasmo) `main`, plus a small set
of generic patches, with CI that publishes a prebuilt Homelab image. It hardcodes nothing about any
particular deployment — the public URL, secrets and DNS name are all supplied at run time.

## Patches carried on top of upstream

- **`WASMO_BASE_URL`** — the Homelab distribution reads its public base URL from this environment
  variable (default unchanged: `http://wasmo.localhost:8080/`). A deployment behind a real domain
  sets it (e.g. `https://wasmo.example.com/`); the OS's per-computer / per-app hostname patterns,
  cookie domain and redirect URLs derive from it. The internal listen port is separate and
  unchanged, so this is correct behind a TLS-terminating reverse proxy.
- **`.wasm` content type** — static `.wasm` resources are served as `application/wasm`, which
  browsers require for `WebAssembly.instantiateStreaming`. (Also proposed upstream.)
- **Custom-domain routing** — URL decoding derives the deployment's root host from `WASMO_BASE_URL`
  (server) and `routing_context.rootUrl` (browser) instead of a hardcoded `wasmo.com`/`.dev`/
  `.localhost` list, so `<computer>.<host>` and `<app>-<computer>.<host>` resolve on any domain.
  Without it a subdomain deployment renders computer pages blank and 500s on installed-app hosts.
- **`WASMO_ADMIN_PASSWORD`** (optional) — sets a password on the bootstrapped, otherwise
  passwordless `admin` account at startup, if it has none yet. Set it on any homelab reachable off
  localhost, or anyone can sign in as `admin`. Idempotent: it never overwrites an existing password.

- **Simulated third-party apps (opt-in)** — `WASMO_SERVICE_APPS=terrazzo,notes,photos,tasks`
  registers shared owner settings services for an operator-approved list of installed apps. The default is empty; other apps and deployments are unchanged.
  This is a temporary bridge until Wasmo can execute third-party app components. See
  [the staged implementation](apps/services/README.md). It does not package browser assets.

- **Remove installed apps** — each launcher entry has a Remove button showing its install name.
  Confirmation disables that installation, its app URL, and delivery of queued app jobs. Stored files and SQL data are retained;
  reinstalling under the same name reactivates the existing installation. Removal is restricted to
  the computer's owner and requires a same-origin JSON POST.

Keep this list small and the edits minimal: every patch is a potential merge conflict when the
branch re-syncs.

## Staying in sync

[`.github/workflows/sync-upstream.yml`](.github/workflows/sync-upstream.yml) merges upstream `main`
into `deploy` daily (and on demand). A clean merge is pushed; a conflicting merge is aborted and an
issue is opened for a human to resolve — it never pushes a broken tree.

## Published image

[`.github/workflows/publish-homelab.yml`](.github/workflows/publish-homelab.yml) builds the Homelab
image from `deploy` and pushes it to this repo's GitHub Container Registry, on every push to
`deploy`, after a successful upstream sync, and on demand:

- `ghcr.io/<owner>/wasmo-homelab-foundation:latest` — JDK + PostgreSQL base (buildx, multi-arch)
- `ghcr.io/<owner>/wasmo-homelab:latest` — the OS image Jib builds on the foundation

Both are built for `linux/amd64,linux/arm64` by default.

## Running it

```bash
docker run \
  --env WASMO_BASE_URL=https://wasmo.example.com/ \
  --env WASMO_PASSWORD_PEPPER=<hex> \
  --env WASMO_ADMIN_PASSWORD=<admin-password> \
  --publish 54400:54400 \
  --mount type=bind,src=$HOME/.wasmo/homelab,dst=/wasmo \
  ghcr.io/<owner>/wasmo-homelab:latest
```

Put a TLS-terminating reverse proxy in front on 443, with a certificate for both the base host and a
wildcard one label below it (Wasmo serves `<computer>.<host>` and `<app>-<computer>.<host>`), and
point it at `127.0.0.1:54400`.

## Trying shared app services (no deployment performed by this change)

Build this branch and opt in with `WASMO_SERVICE_APPS=terrazzo,notes,photos,tasks` (use only install
names you actually approve). The default is empty. The JVM loader uses each install name, not its
manifest ID or GitHub repository; review the archive source before allowing an app. An unlisted
install stays static, and an existing built-in factory cannot be overridden.

Each allowlisted app gets the same `/api/wasmo/v1/` settings API on its own app origin and its own
per-install SQL database. For example, install `terrazzo.wasmo` with the name `terrazzo`. No secrets
key is needed for this settings-only stage. `publish` remains false. The optional [HA session stage](apps/services/HA-SESSIONS.md) requires
separate review and a host-only `WASMO_SESSION_KEYS` key ring before `haSession` is enabled. Hosted wasmo.com
remains a static deployment.
