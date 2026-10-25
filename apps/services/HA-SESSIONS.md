# Private HA sessions (staged; no deployment)

The owner chose **owner-supplied public HTTPS origins** and deferred all public cards. This stage
adds private HA custody to the generic supported-app factory. It does not add public card pages,
HA states, background jobs or object-store writes. `publish` remains false.

An operator must configure both `WASMO_SERVICE_APPS` and `WASMO_SESSION_KEYS` before `haSession`
is advertised. Missing keys, a platform without the public HTTPS service, or an HTTP app origin
leave custody disabled. Unconfigured endpoints remain unavailable. Existing settings still work.
Hosted wasmo.com, GitHub Pages and the HA panel retain browser/host authentication.

## Chosen implementation and contracts

Browser OAuth still validates its one-use pending nonce and exchanges the authorization code with
HA. In cloud mode it transfers the refresh token to the same-origin owner API; the server validates
it with HA before acknowledging storage. Refresh tokens never come back through the API. A second
device signed in as the **Wasmo owner** can restore metadata and obtain a short-lived access token
for its browser-to-HA WebSocket. Wasmo authentication remains required on every device.

This is preferable to synchronizing refresh tokens through the settings API: only the private
session code can decrypt them. A full HA proxy would require live WebSocket support and is outside
this stage; access tokens and live state are still private browser memory. An owner-approved app's
JavaScript can use the owner API and access tokens, so reviewing each supported archive remains
part of the trust boundary. Install names are not proof of GitHub provenance.

All routes are relative to `/api/wasmo/v1/`, owner-only, no CORS, JSON and `Cache-Control: no-store`.
Every mutation, including access-token requests, requires `X-Wasmo-Api: 1`.

| Request | Behavior |
| --- | --- |
| `GET ha/session` | `signedIn`, opaque `sessionId`, and HA origin when connected; no tokens |
| `PUT ha/session` | Explicit sign-in: `{hassUrl, clientId, refreshToken}`; validate and replace |
| `POST ha/session/import` | Same input, only if never initialized; 409 after any prior sign-in/sign-out |
| `POST ha/token` | `{sessionId}`; return only short-lived access token, expiry, origin and session ID |
| `DELETE ha/session` | `{sessionId}`; revoke at HA, then clear custody; failures retain custody and reject |

`clientId` must equal the canonical installed app URL from deployment configuration, not an inbound
Host/forwarded header. Origins must be HTTPS roots without credentials, query or fragment. Each
outbound call reuses only fixed `/auth/token` or `/auth/revoke` paths on that origin. Revocation falls
back to the older fixed `/auth/token` form only on 404, for older HA servers.

Schema 2 adds a singleton session row beside the existing KV table. A transaction/row lock covers
refresh, replacement, migration and sign-out across app recreation/processes. Concurrent refreshes
reuse a valid cached access token. Invalid refresh grants clear custody. A generation changes on
replacement/removal: stale devices cannot refresh or remove a newer session. A permanent import
tombstone prevents old browser credentials from restoring a signed-out session. Retrying a removal
after a lost response succeeds when no session remains; it cannot remove a different active session. Replacing a
session forgets its previous grant; owners can revoke older grants from their HA profile.

## Public HTTPS boundary

The existing `Platform.httpService` follows redirects and is retained for compatibility. The new
optional `Platform.publicHttpsService` uses an independent client: HTTPS only, no URL userinfo,
no redirects/retries/proxies/cookies, a 10-second call deadline, and 64 KiB request/response bounds.
Its DNS hook rejects every response containing private, loopback, link-local, shared, multicast,
documentation or conservative special-purpose addresses, including cloud metadata targets and
IPv6 transition ranges. OkHttp connects to those checked addresses without a second DNS lookup;
IP literals, which bypass DNS, are checked before the call. Normal TLS hostname verification stays
enabled. This is intentionally conservative and may reject unusual globally reachable special
addresses; private/LAN, `.local` HA and split DNS resolving privately are unsupported.

The production client does not use system HTTP proxies: proxy-side DNS would bypass the checks.
Operators needing an egress proxy must implement equivalent destination enforcement before use.
TLS-terminating public HA gateways can themselves reach private HA; this policy restricts Wasmo's
connections, not infrastructure behind an owner's public HTTPS server.

## Key custody, backup and rotation

`WASMO_SESSION_KEYS` is a host-only secret: comma-separated `keyId:base64` entries. Each decoded key
must be 32 random bytes; IDs use 1–32 ASCII letters, digits, underscores or hyphens. The first key
encrypts and all listed keys decrypt. Invalid configuration fails startup without echoing values.
Never put real keys in a commit, browser asset, URL, generic KV record or public object. Keep them
separate from database backups, with restricted operator access and a separately secured recovery
copy. Generating or applying production keys requires the separately authorized rollout.

AES-256-GCM uses a fresh random 96-bit nonce per write and authenticates a versioned purpose plus
the SQL database identity, binding ciphertext to its install. Restore the original database identity
and key together. A copied row cannot be decrypted in another install. This protects database-only
disclosure; a compromised server operator/process with keys can decrypt sessions.

For rotation, add a new key **first**, retaining old keys. Token requests rewrite ciphertext with the
active key, including cache hits. Do not remove old keys until every retained install and required
backup has been migrated or its HA grant revoked and custody removed. Inactive installs do not
rotate automatically. Lost keys make saved sessions unavailable; revoke their grants in HA and
reconnect rather than inventing replacement keys. Do not rotate the separate Wasmo password pepper.

The existing live-tar backup is not sufficient for bundled PostgreSQL; see
[SCH-53](https://linear.app/yschimke/issue/SCH-53/wasmo-backup-copies-live-postgresql-files-and-restore-verification).
Database-consistent backup and isolated restore verification remain a prerequisite before cutover.
Schema 2 causes older schema-1 images to refuse settings requests; image rollback alone is not a
data rollback. Keep a validated pre-migration backup and matching key recovery plan.

## Research and verification limits

- [HA authentication API](https://contributors.home-assistant.io/core/internals/auth-api/) documents
  the authorization-code/refresh flow, client-ID binding and current/older revocation endpoints.
- [JDK Cipher](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/crypto/Cipher.html)
  specifies GCM nonce uniqueness and authenticated additional data.
- [OkHttp client source](https://github.com/square/okhttp/blob/parent-5.3.0/okhttp/src/commonJvmAndroid/kotlin/okhttp3/OkHttpClient.kt)
  and this fork's HTTP adapter establish the redirect/DNS/client boundary; unit tests exercise
  address ranges, rebinding/mixed DNS rejection, literals, deadlines and bounded responses.
- [PostgreSQL backup documentation](https://www.postgresql.org/docs/current/backup-file.html)
  distinguishes live file copies from consistent backups.

Tests use local PostgreSQL and fake HA responses, never a real HA credential. They cover encrypted
persistence/app recreation, cache refresh, concurrent requests, stale generations, revoking sign-out,
invalid-token removal, client-ID/CSRF validation and public-feature disablement. Cryptography tests
cover tampering, install context and key rotation. Live owner-cookie routing, a real external HA,
production key injection and recovery are not claimed by these tests. No deployment or cutover.
