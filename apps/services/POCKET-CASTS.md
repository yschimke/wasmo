# Private Pocket Casts sign-in

Pocket Casts has no supported third-party API. Its web player calls a private API at
`https://api.pocketcasts.com` that answers cross-origin requests only from `https://pocketcasts.com`,
so a browser app on a Wasmo origin, such as [Cadence](https://github.com/yschimke/cadence), can't call
it. This service signs in on the owner's behalf and makes a fixed set of read calls for them. It is
not a general proxy: one origin, four paths, and nothing that writes to the account.

It is advertised as `pocketCasts: true` under the same conditions as `haSession`'s keys: the
operator has configured `WASMO_SESSION_KEYS` and the host provides the public HTTPS client. Like every
service it is reachable only by installs on `WASMO_SERVICE_APPS`.

## Contract

All routes are relative to `/api/wasmo/v1/`, owner-only, no CORS, JSON and `Cache-Control: no-store`.
Every mutation requires `X-Wasmo-Api: 1` and `Content-Type: application/json`.

| Request | Behavior |
| --- | --- |
| `GET pocketcasts/session` | `signedIn`, and `email` when signed in; never tokens |
| `PUT pocketcasts/session` `{email, password}` | Signs in at Pocket Casts (`scope: webplayer`) and keeps the sealed session. A refusal answers 401 `pocketcasts-sign-in-failed` and stores nothing. The password is never stored |
| `POST pocketcasts/call` `{path, body?}` | Calls one allowed path with the bearer token and answers Pocket Casts' JSON as is |
| `DELETE pocketcasts/session` | Forgets the session |

Allowed calls, with the methods Pocket Casts expects: `POST /up_next/list`, `POST /user/in_progress`,
`GET /user/playlists`, `POST /user/episode`. A POST's `body` must be a JSON object; a GET sends none.
Any other path answers 400 `pocketcasts-path-not-allowed` without a request.

`call` refreshes the access token (an hour long) when it has less than a minute left, and once more
if Pocket Casts rejects it. A refusal of the refresh token forgets the session and answers 401
`sign-in-required`; the client asks the owner to sign in again. An outage answers 502
`pocketcasts-unavailable` and keeps the session. A refreshed token is committed even when the call
that follows fails, in case Pocket Casts rotated the old one.

## Custody

The session row (`wasmo_services_pocketcasts`, schema 3) holds `{email, refreshToken, accessToken,
expires}` sealed with `WASMO_SESSION_KEYS`, using the same AES-256-GCM scheme, key rotation and
per-install database binding as [HA sessions](HA-SESSIONS.md) under its own purpose string, so an HA
ciphertext can't be opened as a Pocket Casts one or the other way round. Each request holds the row
lock (3 s timeout, then 503 `busy`) across its refresh, so concurrent calls can't race a rotation.

Pocket Casts has no documented revocation endpoint: signing out and removing the app forget the
session here, and the grant stays valid at Pocket Casts until it expires or the owner changes their
password.

## Trade-offs the operator accepts by enabling it

- The owner's password passes through the server once per sign-in.
- Someone with both the database and the keys can use the owner's Pocket Casts account.
- Calls come from the server's address, not the owner's browser, against an unsupported private API
  that can change or be refused without notice, and that Pocket Casts' terms may not permit.
- Responses from the public HTTPS client may be up to 2 MiB (requests stay at 64 KiB), since Up Next
  and in-progress lists outgrow 64 KiB.
