package gg.grounds.permissions.client

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FileWorkloadTokenProviderTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `reads the current token file content for every request`() {
        val tokenFile = tempDir.resolve("permissions-token")
        Files.writeString(tokenFile, " first-token\n")
        val provider = FileWorkloadTokenProvider(tokenFile)

        assertEquals("first-token", provider.readToken())

        Files.writeString(tokenFile, "second-token")

        assertEquals("second-token", provider.readToken())
    }

    @Test
    fun `rejects a missing token file without exposing credential content`() {
        val tokenFile = tempDir.resolve("missing-token")

        val error =
            assertThrows(IllegalStateException::class.java) {
                FileWorkloadTokenProvider(tokenFile).readToken()
            }

        assertEquals("Failed to read workload token (tokenFile=$tokenFile)", error.message)
    }

    @Test
    fun `rejects an unreadable token path`() {
        val tokenFile = tempDir.resolve("permissions-token")
        Files.createDirectory(tokenFile)

        val error =
            assertThrows(IllegalStateException::class.java) {
                FileWorkloadTokenProvider(tokenFile).readToken()
            }

        assertEquals("Failed to read workload token (tokenFile=$tokenFile)", error.message)
    }

    @Test
    fun `rejects a blank token without including its content in the error`() {
        val tokenFile = tempDir.resolve("permissions-token")
        Files.writeString(tokenFile, " \n\t")

        val error =
            assertThrows(IllegalStateException::class.java) {
                FileWorkloadTokenProvider(tokenFile).readToken()
            }

        assertEquals("Workload token is blank (tokenFile=$tokenFile)", error.message)
        assertFalse(error.message.orEmpty().contains("\n"))
    }
}
