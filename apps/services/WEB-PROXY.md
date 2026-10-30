# Declared web proxies

Some APIs answer cross-origin requests only from their own sites, so a browser app on a Wasmo origin
can't call them. A declared proxy lets an app reach such an origin through its own install. It is not
an open proxy: the app's release names each origin and route in its manifest, the operator approves
each origin for the install, and nothing else is forwarded.

Like every service it is reachable only by installs on `WASMO_SERVICE_APPS`, owner-only and without
CORS, and only on hosts that provide the public HTTPS client.

## Declaring one

In `wasmo-manifest.toml`:

```toml
[[proxy]]
name = 'podcasts'                     # [a-z][a-z0-9-]{0,31}, unique in the manifest
origin = 'https://api.example.com'    # https, a host and optional port, no path
request_headers = ['Authorization']   # forwarded besides Accept and Content-Type

[[proxy.route]]
method = 'POST'                       # GET, HEAD, POST, PUT, PATCH or DELETE
path = '/up_next/list'                # exact

[[proxy.route]]
method = 'GET'
path = '/feeds/**'                    # /feeds and everything below it
```

The manifest checker rejects anything else at packaging and install. That includes a header the
host sets or that would speak for the owner's browser, such as `Cookie`, `Host`, `Origin`,
`Referer` or `X-Forwarded-*`. A route path follows the launcher path rules, with a trailing `/**`
allowed and no `.` or `..` segments.

## Approving one

The operator lists approved origins per install:

```
WASMO_APP_PROXIES=cadence=https://api.pocketcasts.com,cadence=https://feeds.example.com
```

An origin a release declares is unusable until it is listed here. A new release can change the
routes behind an approved origin, but a new origin waits for the operator. `GET capabilities` reports
`proxies: {"<name>": <approved>}` for each declared proxy, so an app can say what is missing.

## Contract

`<METHOD> /api/wasmo/v1/proxy/<name>/<path>?<query>` sends `<METHOD> <origin>/<path>?<query>` when
the method and path match a declared route. As for every service, a request other than GET needs
`X-Wasmo-Api: 1`.

- Request headers: only `Accept`, `Content-Type` and the declared `request_headers`. The owner's
  Wasmo cookies never leave. GET and HEAD send no body, and a body may be at most 64 KiB.
- Paths with `//`, `\`, `.` or `..` segments, or encoded `/`, `\` or `.`, answer 400
  `invalid-proxy-path`, so a path can't escape a declared prefix once the origin decodes it.
- Response: the origin's status and body (at most 2 MiB), its `Content-Type`, `ETag` and
  `Last-Modified`, and nothing else, so no `Set-Cookie` or `Location`. Redirects aren't followed.
  Every response is `no-store`, `nosniff` and `Content-Security-Policy: sandbox; default-src
  'none'`, because it is another site's content served from the app's origin.
- Errors: 404 `proxy-not-declared`, 403 `proxy-not-approved`, 403 `proxy-route-not-declared`, 413
  `too-large`, 502 `proxy-unavailable` (the origin failed or timed out) and 502
  `proxy-destination-refused` (for example, the origin resolved to a non-public address).

Requests go through the same public HTTPS client as the other services: HTTPS only, public addresses
only, no redirects, 10 s per call.

## What it doesn't do (yet)

- **Approval in the Wasmo UI.** The operator approves in configuration. Install happens in a
  background job after the manifest downloads, so there is no install screen to show declarations on
  yet.
- **Server-held secrets.** Credentials stay in the app: a declared `Authorization` header carries a
  token the browser already holds. A custodial flow like [Pocket Casts sign-in](POCKET-CASTS.md),
  where the server keeps a sealed refresh token, is still a dedicated service.
- **Arbitrary hosts.** There is no fetch-any-URL mode, not even for GET. Feeds on hosts the app
  can't name in advance need a separate, owner-approved design.
