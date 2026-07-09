# Permission Manifest Collector Design

## Goal

The permissions plugin or module collects manifests from every active runtime component and registers the entries with service-permissions so Portal displays runtime-owned permission nodes.

## Manifest Contract

Every participating artifact places one JSON resource at `META-INF/grounds/permissions.json`.

```json
{
  "source": "plugin-agones",
  "permissions": [
    {
      "key": "grounds.command.agones",
      "label": "Use Agones command",
      "description": "Allows using the Agones command.",
      "supportedScopes": ["GLOBAL", "SERVER_TYPE", "SERVER"]
    }
  ]
}
```

The collector skips missing resources. It reports malformed JSON, duplicate keys, blank required fields, and entries without scopes without stopping the server.

## Discovery

Velocity enumerates active `PluginContainer` instances through `PluginManager.getPlugins()` and reads the resource from each plugin instance classloader. Minestom exposes the provider classloaders for modules selected and installed by `GroundsServer`; discovered but unselected providers are excluded.

## Registration

The collector sends one `RegisterPermissionManifest` request per valid manifest using the manifest source, component version, and runtime scope. It retries transient failures five times with increasing delays. `service-permissions` persists runtime entries with `custom = false`; Portal continues to use its existing catalog endpoint.
