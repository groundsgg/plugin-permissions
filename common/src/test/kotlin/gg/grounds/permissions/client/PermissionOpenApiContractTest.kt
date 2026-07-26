package gg.grounds.permissions.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class PermissionOpenApiContractTest {
    private val document: JsonNode by lazy {
        val resource =
            requireNotNull(javaClass.getResourceAsStream(CONTRACT_RESOURCE)) {
                "Permissions OpenAPI contract is missing (resource=$CONTRACT_RESOURCE)"
            }
        resource.use { JsonMapper.builder().build().readTree(it) }
    }

    @Test
    fun `runtime operations use workload bearer and expected HTTP contracts`() {
        val snapshot = operation(SNAPSHOT_PATH, "get")
        assertEquals(
            setOf("playerId" to "path", "serverType" to "query", "serverId" to "query"),
            snapshot
                .path("parameters")
                .values()
                .map { it.path("name").asString() to it.path("in").asString() }
                .toSet(),
        )
        assertOperationContract(snapshot, successStatus = "200")
        assertSchemaReference(
            snapshot
                .path("responses")
                .path("200")
                .path("content")
                .path("application/json")
                .path("schema"),
            "RuntimePermissionSnapshotResponse",
        )

        val manifest = operation(MANIFEST_PATH, "put")
        assertEquals(
            setOf("source" to "path"),
            manifest
                .path("parameters")
                .values()
                .map { it.path("name").asString() to it.path("in").asString() }
                .toSet(),
        )
        assertOperationContract(manifest, successStatus = "204")
        assertSchemaReference(
            manifest.path("requestBody").path("content").path("application/json").path("schema"),
            "RuntimeManifestRequest",
        )

        val workloadBearer =
            document.path("components").path("securitySchemes").path("workloadBearer")
        assertEquals("http", workloadBearer.path("type").asString())
        assertEquals("bearer", workloadBearer.path("scheme").asString())
        assertEquals("JWT", workloadBearer.path("bearerFormat").asString())
    }

    @Test
    fun `snapshot schemas contain every field consumed by the runtime DTOs`() {
        assertSchemaFields(
            "RuntimePermissionSnapshotResponse",
            "playerId",
            "policyVersion",
            "issuedAt",
            "refreshAfter",
            "expiresAt",
            "allowPatterns",
            "denyPatterns",
            "roleKeys",
            "roleMetadata",
        )
        assertSchemaReference(
            schema("RuntimePermissionSnapshotResponse")
                .path("properties")
                .path("allowPatterns")
                .path("items"),
            "RuntimePermissionGrantDto",
        )
        assertSchemaReference(
            schema("RuntimePermissionSnapshotResponse")
                .path("properties")
                .path("denyPatterns")
                .path("items"),
            "RuntimePermissionGrantDto",
        )
        assertSchemaReference(
            schema("RuntimePermissionSnapshotResponse")
                .path("properties")
                .path("roleMetadata")
                .path("items"),
            "RuntimeRoleMetadataDto",
        )
        assertSchemaFields(
            "RuntimePermissionGrantDto",
            "effect",
            "pattern",
            "scope",
            "source",
            "expiresAt",
        )
        assertSchemaReference(
            schema("RuntimePermissionGrantDto").path("properties").path("scope"),
            "PermissionScopeDto",
        )
        assertSchemaFields("PermissionScopeDto", "kind", "value")
        assertSchemaFields("RuntimeRoleMetadataDto", "key", "name", "prefix", "color", "sortOrder")
    }

    @Test
    fun `manifest schemas contain every field emitted by the runtime DTOs`() {
        assertSchemaFields(
            "RuntimeManifestRequest",
            "sourceVersion",
            "serverType",
            "serverId",
            "permissions",
            requireAll = false,
        )
        assertEquals(
            setOf("sourceVersion", "permissions"),
            schema("RuntimeManifestRequest")
                .path("required")
                .values()
                .map(JsonNode::asString)
                .toSet(),
        )
        assertFalse(schema("RuntimeManifestRequest").path("properties").has("source"))
        assertSchemaReference(
            schema("RuntimeManifestRequest").path("properties").path("permissions").path("items"),
            "RuntimeManifestPermissionRequest",
        )
        assertSchemaFields(
            "RuntimeManifestPermissionRequest",
            "key",
            "label",
            "description",
            "supportedScopes",
            requireAll = false,
        )
        assertTrue(
            schema("RuntimeManifestPermissionRequest")
                .path("required")
                .values()
                .map(JsonNode::asString)
                .toSet()
                .containsAll(listOf("key", "label", "supportedScopes"))
        )
    }

    private fun operation(path: String, method: String): JsonNode {
        val pathItem = document.path("paths").path(path)
        assertFalse(pathItem.isMissingNode, "Missing OpenAPI path: $path")
        val operation = pathItem.path(method)
        assertFalse(operation.isMissingNode, "Missing OpenAPI operation: $method $path")
        return operation
    }

    private fun assertOperationContract(operation: JsonNode, successStatus: String) {
        assertTrue(operation.path("security").any { it.has("workloadBearer") })
        assertTrue(operation.path("responses").has(successStatus))
        val problemResponses =
            operation.path("responses").properties().filter { (status, _) ->
                status != successStatus
            }
        assertTrue(problemResponses.any())
        problemResponses.forEach { (status, response) ->
            val schema = response.path("content").path("application/problem+json").path("schema")
            assertSchemaReference(schema, "ProblemDetails", "response=$status")
        }
    }

    private fun assertSchemaFields(
        name: String,
        vararg fields: String,
        requireAll: Boolean = true,
    ) {
        val schema = schema(name)
        val properties = schema.path("properties")
        fields.forEach { field ->
            assertTrue(properties.has(field), "Missing OpenAPI field: $name.$field")
        }
        if (requireAll) {
            assertEquals(
                fields.toSet(),
                schema.path("required").values().map(JsonNode::asString).toSet(),
            )
        }
    }

    private fun schema(name: String): JsonNode {
        val schema = document.path("components").path("schemas").path(name)
        assertFalse(schema.isMissingNode, "Missing OpenAPI schema: $name")
        return schema
    }

    private fun assertSchemaReference(
        node: JsonNode,
        expectedSchema: String,
        context: String = expectedSchema,
    ) {
        assertNotNull(node)
        assertEquals("#/components/schemas/$expectedSchema", node.path("\$ref").asString(), context)
    }

    private companion object {
        const val CONTRACT_RESOURCE = "/contracts/service-permissions-openapi.json"
        const val SNAPSHOT_PATH = "/v1/permissions/runtime/players/{playerId}/snapshot"
        const val MANIFEST_PATH = "/v1/permissions/runtime/catalog/manifests/{source}"
    }
}
