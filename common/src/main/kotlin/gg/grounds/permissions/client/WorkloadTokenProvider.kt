package gg.grounds.permissions.client

fun interface WorkloadTokenProvider {
    fun readToken(): String
}
