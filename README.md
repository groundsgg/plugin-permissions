# plugin-permissions

Permission client for the Grounds network. Loads a player's permission snapshot from
`service-permissions` and answers `hasPermission` checks against it.

Modules: `common` (snapshot model + resolution), `velocity`, `minestom`, `paper`.

The plugin uses the authenticated REST runtime API from `service-permissions`. Minestom, Paper and
Velocity share the HTTP transport, token handling, cache policy, and snapshot refresh behavior from
the `common` module. The Paper module publishes the common `Permissions` service through Bukkit's
`ServicesManager`; presentation such as chat, tab-list and prefix formatting remains the caller's
responsibility.

When configured on Paper, the plugin injects a Grounds-owned permissible for each player. Ordinary
`player.hasPermission` calls, including unregistered and dynamically constructed nodes, therefore
follow the player's snapshot decision; temporary Bukkit attachments override that decision.

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

## Permission catalog resources

Publish one JSON resource per manifest source at
`META-INF/grounds/permissions/<source>.json` and list its full resource path in
`META-INF/grounds/permissions.index`, one line per file. Each manifest contains
`source`, the producing artifact's `sourceVersion`, and `permissions` entries with
`key`, `label`, `description`, and `supportedScopes` (`GLOBAL`, `SERVER_TYPE`, `SERVER`).
Expand `sourceVersion` from the artifact build version during `processResources`.

Shaded hosts must append-merge `META-INF/grounds/permissions.index` and preserve
all uniquely named JSON resources. The common collector enumerates every index,
uses each manifest's embedded version, deduplicates identical records, and rejects
conflicting declarations for a source. Minestom also scans its host classloader,
so a manifest does not require an active ServiceLoader provider. Paper discovers
loaded plugins and rescans when another plugin is enabled.

The legacy `META-INF/grounds/permissions.json` remains supported only when its
owning artifact and version can be identified. A shared legacy resource is never
assigned to an arbitrary provider. Keep legacy resources while older runtimes
remain deployed. New resources take precedence over legacy copies.

Validate final artifacts with `python3 scripts/validate-permission-artifacts.py
path/to/host.jar --require-source plugin-chat`. A manifest catalog entry does not
grant a player access. Workload authorization must separately permit registration
of each explicit manifest source; unrelated sources remain forbidden.

For a collector integration check, set `PERMISSION_TEST_EXPECTATIONS` to a JSON
file mapping absolute host JAR paths to expected source/version maps, then run
`:common:test --tests '*PackagedPermissionManifestTest'`. Supply expectations
from the selected release dependencies; filenames do not determine ownership.
