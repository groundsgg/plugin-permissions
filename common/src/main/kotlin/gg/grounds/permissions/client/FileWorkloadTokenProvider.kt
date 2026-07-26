package gg.grounds.permissions.client

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class FileWorkloadTokenProvider(private val tokenFile: Path) : WorkloadTokenProvider {
    override fun readToken(): String {
        val token =
            try {
                Files.readString(tokenFile).trim()
            } catch (exception: IOException) {
                throw tokenReadFailure(exception)
            } catch (exception: SecurityException) {
                throw tokenReadFailure(exception)
            }
        check(token.isNotEmpty()) { "Workload token is blank (tokenFile=$tokenFile)" }
        return token
    }

    private fun tokenReadFailure(cause: Exception): IllegalStateException =
        IllegalStateException("Failed to read workload token (tokenFile=$tokenFile)", cause)
}
