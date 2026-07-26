# plugin-permissions

Permission client for the Grounds network. Loads a player's permission snapshot from
`service-permissions` and answers `hasPermission` checks against it.

Modules: `common` (snapshot model + resolution), `velocity`, `minestom`.

The plugin uses the authenticated REST runtime API from `service-permissions`. Minestom and
Velocity share the HTTP transport, token handling, cache policy, and manifest retry behavior from
the `common` module.

## How a snapshot is kept current

A snapshot is fetched at login. The service stamps it with `refreshAfter` (+5 min) and `expiresAt`
(+10 min), and a check fails closed once the snapshot expires — so the plugin re-fetches snapshots
for online players on a sweep, once `refreshAfter` has passed. That is also how a grant reaches a
player who is already online.

A snapshot request has a two-second timeout and is not retried immediately. A transient failure may
use only a cached snapshot whose `expiresAt` is still in the future. Without one, login fails closed
rather than allowing a player with unknown permissions. Authentication, authorization, and missing
player responses always fail closed.

Permission manifests are registered asynchronously so service availability does not block server
startup. Connection failures, `429`, and `5xx` responses are retried with bounded exponential
backoff and jitter. Other client responses are terminal.

## Configuration

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `PERMISSIONS_SERVICE_URL` | with `PERMISSIONS_TOKEN_FILE` | — | Absolute HTTP(S) base URL of `service-permissions`. If both REST settings are absent, the integration remains disabled; partial configuration fails startup. |
| `PERMISSIONS_TOKEN_FILE` | with `PERMISSIONS_SERVICE_URL` | — | Path to the projected workload token. The file is read for every request so token rotation is picked up without restarting. Token contents and the path are never included in status output or logs. |
| `GROUNDS_PERMISSION_SERVER_TYPE` | no | runtime type on Minestom; unset on Velocity | Resolves server-type-scoped grants (e.g. `lobby`). An unset Velocity value resolves checks at global scope. |
| `GROUNDS_PERMISSION_SERVER_ID` | no | unset | Resolves server-scoped grants. |
| `GROUNDS_PERMISSION_ENVIRONMENT` | no | unset | Adds the deployment environment to local scope resolution. |
| `PERMISSIONS_REFRESH_INTERVAL_SECONDS` | no | `60` | How often the refresh sweep runs. |
