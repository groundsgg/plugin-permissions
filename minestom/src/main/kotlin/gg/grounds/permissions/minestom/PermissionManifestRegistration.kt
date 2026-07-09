package gg.grounds.permissions.minestom

import gg.grounds.permissions.catalog.CollectedPermissionManifest
import gg.grounds.permissions.catalog.ManifestOrigin
import gg.grounds.permissions.catalog.PermissionManifestCollection
import gg.grounds.permissions.catalog.PermissionManifestCollector
import gg.grounds.runtime.ActiveGroundsModuleProvider

data class MinestomPermissionManifestRegistrationFailure(
    val collected: CollectedPermissionManifest,
    val attempts: Int,
    val reason: String,
)

data class MinestomPermissionManifestRegistrationReport(
    val registered: List<CollectedPermissionManifest>,
    val failures: List<MinestomPermissionManifestRegistrationFailure>,
)

fun collectActivePermissionManifests(
    activeProviders: Iterable<ActiveGroundsModuleProvider>
): PermissionManifestCollection =
    PermissionManifestCollector()
        .collect(
            activeProviders.map { provider ->
                ManifestOrigin(
                    id = provider.id,
                    version = provider.version,
                    classLoader = provider.classLoader,
                )
            }
        )

class MinestomPermissionManifestRegistrar(
    private val client: PermissionCatalogClient,
    private val context: PermissionSnapshotContext,
    private val sleep: (Long) -> Unit = { duration -> Thread.sleep(duration) },
) {
    fun register(
        manifests: Iterable<CollectedPermissionManifest>
    ): MinestomPermissionManifestRegistrationReport {
        val registered = mutableListOf<CollectedPermissionManifest>()
        val failures = mutableListOf<MinestomPermissionManifestRegistrationFailure>()

        manifests.forEach manifestLoop@{ collected ->
            repeat(MAX_ATTEMPTS) { attempt ->
                when (
                    val result =
                        client.register(
                            manifest = collected.manifest,
                            sourceVersion = collected.origin.version,
                            context = context,
                        )
                ) {
                    PermissionManifestRegistrationResult.Accepted -> {
                        registered += collected
                        return@manifestLoop
                    }
                    is PermissionManifestRegistrationResult.Unavailable -> {
                        if (attempt == MAX_ATTEMPTS - 1) {
                            failures +=
                                MinestomPermissionManifestRegistrationFailure(
                                    collected = collected,
                                    attempts = MAX_ATTEMPTS,
                                    reason = result.reason,
                                )
                            return@manifestLoop
                        }
                        try {
                            sleep((attempt + 1) * BACKOFF_MS)
                        } catch (exception: InterruptedException) {
                            Thread.currentThread().interrupt()
                            failures +=
                                MinestomPermissionManifestRegistrationFailure(
                                    collected = collected,
                                    attempts = attempt + 1,
                                    reason = "interrupted",
                                )
                            return@manifestLoop
                        }
                    }
                }
            }
        }

        return MinestomPermissionManifestRegistrationReport(
            registered = registered,
            failures = failures,
        )
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val BACKOFF_MS = 250L
    }
}
