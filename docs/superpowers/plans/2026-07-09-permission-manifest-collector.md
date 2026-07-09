# Permission Manifest Collector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Register catalog entries from every active Velocity plugin and Minestom module through the permissions runtime component.

**Architecture:** A shared parser represents `META-INF/grounds/permissions.json`. Velocity and Minestom turn active components into classloader origins, then the permissions runtime registers every valid manifest through the existing gRPC service. service-permissions persists the validated entries for Portal.

**Tech Stack:** Kotlin, Jackson, gRPC/protobuf, Velocity, Grounds Minestom runtime, Quarkus, PostgreSQL, JUnit 5.

## Global Constraints

- Use exactly `META-INF/grounds/permissions.json`.
- Discover only active Velocity plugins and installed Minestom providers.
- Use the existing `PermissionCatalogService.RegisterPermissionManifest` RPC.
- Never fail server startup because a manifest is missing, malformed, or temporarily unregistrable.
- Runtime records use `custom = false`; Portal API changes are out of scope.
- Log messages must be factual English with non-sensitive origin context.
- In every changed Gradle repository run `./gradlew test`, `./gradlew spotlessApply`, and `./gradlew build` with escalated permissions.

---

### Task 1: Persist Runtime Catalog Registrations

**Repository:** `/home/lukas/grounds/service-permissions`

**Files:**
- Modify: `src/main/kotlin/gg/grounds/permissions/api/PermissionCatalogGrpcService.kt`
- Modify: `src/test/kotlin/gg/grounds/permissions/api/PermissionSnapshotGrpcServiceTest.kt`

**Interfaces:**
- Consumes `RegisterPermissionManifestRequest` and `PermissionRepository.upsertCatalogEntry(CatalogEntryRecord)`.
- Produces runtime-owned `CatalogEntryRecord` values returned by `PermissionCatalogResource.listCatalog()`.

- [ ] Write a failing test that registers one manifest and asserts `repository.listCatalogEntries().single()` has the request key and `custom == false`.
- [ ] Run `./gradlew test --tests gg.grounds.permissions.api.PermissionSnapshotGrpcServiceTest`; verify the catalog is empty before implementation.
- [ ] Inject `PermissionRepository`; after validation, map every protobuf entry to `CatalogEntryRecord(key, label, description, source, sourceVersion, supportedScopes, custom = false, lastSeenAt = Instant.now())` and call `upsertCatalogEntry`.
- [ ] Run `./gradlew test`, `./gradlew spotlessApply`, and `./gradlew build`.
- [ ] Commit `feat: persist permission manifests`.

### Task 2: Expose Active Minestom Provider Origins

**Repository:** `/home/lukas/grounds/grounds-minestom-runtime`

**Files:**
- Modify: `runtime-api/src/main/kotlin/gg/grounds/runtime/GroundsServerContext.kt`
- Modify: `runtime-core/src/main/kotlin/gg/grounds/runtime/core/GroundsModuleComposition.kt`
- Modify: `runtime-core/src/main/kotlin/gg/grounds/runtime/core/GroundsServer.kt`
- Test: `runtime-core/src/test/kotlin/gg/grounds/runtime/core/GroundsServerTest.kt`

**Interfaces:**
- Produces `ActiveGroundsModuleProvider(id: String, version: String, classLoader: ClassLoader)`.
- Extends `GroundsServerContext` with `activeModuleProviders: List<ActiveGroundsModuleProvider>`.

- [ ] Write a failing test proving the context exposes selected provider IDs but omits merely discovered providers.
- [ ] Run `./gradlew :runtime-core:test --tests gg.grounds.runtime.core.GroundsServerTest`; confirm the new context property is absent.
- [ ] Retain provider ID, version, and classloader in `GroundsModuleComposition`; pass only server-type-matched providers into `DefaultGroundsServerContext`.
- [ ] Run `./gradlew test`, `./gradlew spotlessApply`, and `./gradlew build`.
- [ ] Commit `feat: expose active module providers`.

### Task 3: Collect and Register Active Runtime Manifests

**Repository:** `/home/lukas/grounds/plugin-permissions`

**Files:**
- Modify: `common/build.gradle.kts`
- Create: `common/src/main/kotlin/gg/grounds/permissions/catalog/PermissionManifest.kt`
- Create: `common/src/main/kotlin/gg/grounds/permissions/catalog/PermissionManifestCollector.kt`
- Modify: `velocity/src/main/kotlin/gg/grounds/permissions/velocity/GroundsPermissionsPlugin.kt`
- Modify: `minestom/src/main/kotlin/gg/grounds/permissions/minestom/GroundsPermissionsModule.kt`
- Create: `minestom/src/main/kotlin/gg/grounds/permissions/minestom/PermissionCatalogClient.kt`
- Modify: `velocity/src/main/resources/META-INF/grounds/permissions.json`
- Test: `common/src/test/kotlin/gg/grounds/permissions/catalog/PermissionManifestCollectorTest.kt`
- Test: `velocity/src/test/kotlin/gg/grounds/permissions/velocity/PermissionManifestDiscoveryTest.kt`
- Test: `minestom/src/test/kotlin/gg/grounds/permissions/minestom/PermissionManifestDiscoveryTest.kt`

**Interfaces:**
- Consumes `ManifestOrigin(id: String, version: String, classLoader: ClassLoader)`.
- Produces one registration attempt per valid active manifest and the own manifest used for command authorization.

- [ ] Write failing parser tests for the `plugin-agones` JSON shape, missing resources, duplicate keys, and invalid scopes.
- [ ] Write failing discovery tests asserting that two active origins register two manifests and an origin with no resource is skipped.
- [ ] Add Jackson JSON dependencies to `common/build.gradle.kts`; implement the shared parser and `PermissionManifestCollector.collect(origins)` using `META-INF/grounds/permissions.json`.
- [ ] In Velocity map `PluginManager.getPlugins()` containers with instances to origins; in Minestom map `ctx.activeModuleProviders` to origins.
- [ ] Add a Minestom `PermissionCatalogClient` backed by the existing gRPC channel pattern; register each collected manifest off the server thread with five bounded retry attempts and log final failures without aborting startup.
- [ ] Replace PR #4's self-only `META-INF/grounds-permissions.json` implementation with the standardized collector and the own `META-INF/grounds/permissions.json` manifest.
- [ ] Run `./gradlew test`, `./gradlew spotlessApply`, and `./gradlew build`.
- [ ] Commit `feat: collect permission manifests` and push the existing PR branch.
