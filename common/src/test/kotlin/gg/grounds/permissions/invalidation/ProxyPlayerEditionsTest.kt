package gg.grounds.permissions.invalidation

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProxyPlayerEditionsTest {
    @Test
    fun `proxy roster identifies linked accounts and ignores malformed replacement`() {
        val linked = UUID.randomUUID()
        ProxyPlayerEditions(emptyMap()).use { editions ->
            assertFalse(editions.isBedrock(linked))
            editions.receive(
                """{"schemaVersion":1,"proxy":"bedrock-1","players":{"$linked":true}}"""
                    .toByteArray()
            )
            assertTrue(editions.isBedrock(linked))
            editions.receive(
                """{"schemaVersion":1,"proxy":"bedrock-1","players":{"$linked":"false"}}"""
                    .toByteArray()
            )
            assertTrue(editions.isBedrock(linked))
            editions.receive(
                """{"schemaVersion":1,"proxy":"bedrock-1","players":{"$linked":false}}"""
                    .toByteArray()
            )
            assertFalse(editions.isBedrock(linked))
        }
    }

    @Test
    fun `unlinked Floodgate UUID has a fallback without a roster`() {
        ProxyPlayerEditions(emptyMap()).use { editions ->
            assertTrue(editions.isBedrock(UUID(0, 1234)))
            assertFalse(editions.isBedrock(UUID(0, 0)))
            assertFalse(editions.isBedrock(UUID.randomUUID()))
        }
    }
}
