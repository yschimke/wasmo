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
  --publish 54400:54400 \
  --mount type=bind,src=$HOME/.wasmo/homelab,dst=/wasmo \
  ghcr.io/<owner>/wasmo-homelab:latest
```

Put a TLS-terminating reverse proxy in front on 443, with a certificate for both the base host and a
wildcard one label below it (Wasmo serves `<computer>.<host>` and `<app>-<computer>.<host>`), and
point it at `127.0.0.1:54400`.
