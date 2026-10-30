# Shared Wasmo app services: staged implementation

The settings stage adds the owner settings API from the [handover](https://github.com/yschimke/homeassistant-remotecompose/blob/e6f075be/docs/architecture/wasmo-cloud-backend.md).
Each separately released static archive remains the source of browser assets. Nothing is deployed.
The shared factory is registered for each allowlisted install name; there is no Terrazzo branch in
server wiring or service code. Settings use the app's own per-install database, so neither another
app nor another owner's install can read them. The operator's allowlist controls which archives
receive services: install names do **not** prove a GitHub owner or repository identity. Review the
archive source before adding it to the supported list; never use a wildcard. Existing built-in
factories cannot be replaced by this list. HA credentials are not shared across apps.

Set `WASMO_SERVICE_APPS=terrazzo,notes,photos,tasks` to enable services for an explicit list of install names. Install each archive using its matching name.
Without that environment variable the fork retains its existing behaviour. This is JVM simulation,
not execution of an archive's `app.wasm`.

## Contract

All endpoints are under `/api/wasmo/v1/` and require the Wasmo owner session. The platform guards
access before calling app code; the app must never add public objects over the API namespace.

- `GET capabilities`: API 1, `kv: true`, `haSession` and `pocketCasts` enabled only with private session configuration, `proxies` with each declared proxy's approval, `publish: false`.
- `GET kv`: all saved JSON values, keyed by setting name.
- `PUT kv/{key}`: any JSON value, including JSON null; requires `Content-Type: application/json`.
- `DELETE kv/{key}`: idempotently removes a setting.
- `POST kv/import`: a JSON object of initial values. Imports all values atomically only if the
  install has no settings; otherwise 409. Advertised as `kvImport: true`. This addition to the
  handover prevents partial migration and concurrent first-device overwrites. The aggregate body
  may be 1 MiB, while each serialized value retains its 256 KiB limit.

Every non-GET request requires `X-Wasmo-Api: 1`. No CORS headers are returned. Responses use
`Cache-Control: no-store`. Keys match `[a-z0-9][a-z0-9._-]{0,63}`. JSON nesting is capped at 64 levels. Bodies are at most 256 KiB;
aggregate UTF-8 key/value storage is at most 1 MiB. A database table lock keeps quota checks atomic
across concurrent writers. Private data lives in the per-install SQL database, never `www/` or
`www-public/`. Do not store credentials in generic settings.

The JVM loader currently creates an app on every request and does not close app instances. Database
handles are therefore scoped and closed per HTTP invocation and install hook, avoiding retained
connection pools. The schema is checked both after installation and before each request. Migrations are transactional
and serialized by a PostgreSQL advisory lock. A newer schema returns 503 without downgrading.

## Verified platform contracts (25 October 2026)

Upstream main still resolves to [`e688b5e`](https://github.com/wasmcomputercompany/wasmo/commit/e688b5e76a5e58170d48a4aa64736e39e6178736).
The fork deploy base is [`64ba8f83`](https://github.com/yschimke/wasmo/commit/64ba8f83).

- [`JvmAppLoader`](https://github.com/yschimke/wasmo/blob/64ba8f83/os/server/wasm/jvm/src/jvmMain/kotlin/com/wasmo/wasm/JvmAppLoader.kt)
  loads registered built-ins by install name; an installed third-party component is not executed.
- [`RealInstalledAppHttpService`](https://github.com/yschimke/wasmo/blob/64ba8f83/os/server/installedapps/real/src/jvmMain/kotlin/com/wasmo/installedapps/RealInstalledAppHttpService.kt)
  serves public objects/resources first, then owner objects/resources and owner-only app HTTP.
  Caller is still not passed to app code.
- [`SqlService`](https://github.com/yschimke/wasmo/blob/64ba8f83/platform/api/src/commonMain/kotlin/wasmo/sql/Sql.kt)
  provides the per-install PostgreSQL connection API used here.
- [`DEPLOY.md`](../../DEPLOY.md) documents runtime URL, MIME, routing and admin-password patches.

Compared options: a real third-party Wasm backend is unavailable today; a separate proxy service
would lose per-install platform isolation and add another authentication boundary. The handover's
opt-in built-in JVM factory uses the implemented isolation and leaves the deploy branch generic.

## Remaining stages and security decisions

The [web detection/local-storage seam](https://github.com/yschimke/homeassistant-remotecompose/pull/1225)
is merged. The [cloud-settings integration](https://github.com/yschimke/homeassistant-remotecompose/pull/1228)
is a separate draft: it seeds settings before composition, coalesces writes, retains failed writes
and imports known browser values only after atomic acknowledged persistence. Its production Wasm
browser tests cover another independent device, failure recovery and the unchanged static path.

Review and future rollout order: merged web seam → validate this shared server stage → review the
cloud web integration. A server release, supported-install opt-in or archive cutover requires separate
user authorization; none has been performed by this session. The full OS wiring compile now passes.
An earlier [registry resolution failure](https://linear.app/yschimke/issue/SCH-50/wasmo-wiring-build-fails-resolving-peculiarasn1-x509-2111)
no longer reproduces; no dependency change, registry override or skipped check was needed. A live
OS install and real owner-cookie flow remain unverified.

The generic KV API is last-writer-wins and clients seed once per page. Atomic import prevents
migration races; it does not merge concurrent usage ledgers or coordinate report idempotency.
Those remain app-level design questions before claiming combined counts across active devices.

HA credential custody is implemented in the separately enabled [private session stage](HA-SESSIONS.md). Public publishing is deferred by the owner and remains disabled. Before any future public stage:

1. Choose the HA destination policy. Wasmo's shared OkHttp client follows redirects and has no
   destination allowlist; checking only the supplied URL does not constrain the final destination.
   A deployment origin allowlist and a redirect-disabled app transport avoid credential forwarding
   and unintended access to internal services. LAN access must be explicit rather than globally denied.
2. Use authenticated encryption with per-install context and define key backup/rotation behaviour.
   No environment key should ever be exposed through capabilities or written to objects.
3. Define public fields. Publishing an entity's entire attributes map may expose private URLs,
   coordinates or other details. The owner must approve the exact fields as well as entity IDs.
4. Serialize publication writes/deletes against running jobs: queue cancellation cannot stop a job
   already executing. Unpublish and sign-out must prevent a late job from restoring public objects.
5. Public object responses currently cannot carry app-selected cache-control headers. Unpublish
   cannot retract already downloaded snapshots. Decide acceptable staleness and explain it in the UI.
6. Isolate public rendering from owner API privileges when the owner views a public card.
   A remotely updated script running directly in the app origin can use that owner's private API.
   See the [public renderer boundary](https://github.com/yschimke/homeassistant-remotecompose/blob/agent/wasmo-cloud-storage/docs/architecture/wasmo-cloud-backend.md#public-renderer-security-boundary).
7. Stage the embed's static-state source before publication; it must never authenticate, open a
   Home Assistant connection or dispatch an action for public visitors.

## Validation

`./gradlew :apps:services:wasmo-app:jvmTest :os:server:wiring:compileKotlinJvm`

Tests use the repository's `FakePlatform` and `FakeSqlService`. They require local PostgreSQL with
`postgres` / `password`, optionally at `POSTGRESQL_HOSTNAME` (see the local development runbook).
They exercise lazy schema migration, persistence, CSRF, malformed input, byte and aggregate limits,
newer-schema refusal and disabled credential/publication capabilities.

The owner selected owner-supplied public HTTPS HA origins. See [HA-SESSIONS.md](HA-SESSIONS.md) for the optional session API, outbound safeguards, encryption and rollout limits. No public card data is exposed.

[POCKET-CASTS.md](POCKET-CASTS.md) adds a private Pocket Casts sign-in with a fixed set of read calls, for browser apps that Pocket Casts' API refuses cross-origin.

[WEB-PROXY.md](WEB-PROXY.md) lets an app declare `[[proxy]]` origins and routes in its manifest, forwarded only once the operator approves each origin with `WASMO_APP_PROXIES`.
