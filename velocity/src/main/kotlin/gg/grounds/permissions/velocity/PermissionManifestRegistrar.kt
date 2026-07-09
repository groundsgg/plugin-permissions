package gg.grounds.permissions.velocity

import gg.grounds.permissions.catalog.CollectedPermissionManifest

data class PermissionManifestRegistrationFailure(
    val collected: CollectedPermissionManifest,
    val attempts: Int,
    val reason: String,
)

data class PermissionManifestRegistrationReport(
    val registered: List<CollectedPermissionManifest>,
    val failures: List<PermissionManifestRegistrationFailure>,
)

class PermissionManifestRegistrar(
    private val client: PermissionCatalogClient,
    private val context: PermissionSnapshotContext,
    private val sleep: (Long) -> Unit = { duration -> Thread.sleep(duration) },
) {
    fun register(
        manifests: Iterable<CollectedPermissionManifest>
    ): PermissionManifestRegistrationReport {
        val registered = mutableListOf<CollectedPermissionManifest>()
        val failures = mutableListOf<PermissionManifestRegistrationFailure>()

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
                                PermissionManifestRegistrationFailure(
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
                                PermissionManifestRegistrationFailure(
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

        return PermissionManifestRegistrationReport(registered = registered, failures = failures)
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val BACKOFF_MS = 250L
    }
}
