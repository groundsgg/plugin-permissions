# plugin-permissions

Permission client for the Grounds network. Loads a player's permission snapshot from
`service-permissions` and answers `hasPermission` checks against it.

Modules: `common` (snapshot model + resolution), `velocity`, `minestom`.

## How a snapshot is kept current

A snapshot is fetched at login. The service stamps it with `refreshAfter` (+5 min) and `expiresAt`
(+10 min), and a check fails closed once the snapshot expires — so the plugin re-fetches snapshots
for online players on a sweep, once `refreshAfter` has passed. That is also how a grant reaches a
player who is already online.

A failed refresh keeps the existing snapshot and retries on the next sweep. Only the login path may
deny a player: if the service is unreachable and there is no valid cached snapshot, the player is
rejected rather than let in with no permissions.

## Configuration

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `PERMISSIONS_GRPC_TARGET` | yes | — | `service-permissions` gRPC endpoint. Missing or blank is a hard error at startup. |
| `GROUNDS_PERMISSION_SERVER_TYPE` | no | unset | Resolves server-type-scoped grants (e.g. `lobby`). Unset means checks resolve at global scope — which is what a proxy wants. |
| `GROUNDS_PERMISSION_SERVER_ID` | no | unset | Resolves server-scoped grants. |
| `PERMISSIONS_REFRESH_INTERVAL_SECONDS` | no | `60` | How often the refresh sweep runs. |
