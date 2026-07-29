package gg.grounds.permissions.minestom

import gg.grounds.runtime.GroundsServerContext
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroundsPermissionsModuleLifecycleTest {
    @Test
    fun `keeps the public no-argument module constructor`() {
        assertNotNull(GroundsPermissionsModule::class.java.getConstructor())
    }

    @Test
    fun `reinstall closes the previous invalidation handle before owning the next`() {
        val events = mutableListOf<String>()
        var installation = 0
        val module = module { _, _ ->
            installation++
            val current = installation
            events += "create-$current"
            MinestomRecordingHandle { events += "close-$current" }
        }

        module.install(context())
        module.install(context())
        module.stop()
        module.stop()

        assertEquals(listOf("create-1", "close-1", "create-2", "close-2"), events)
    }

    @Test
    fun `stop during install closes the handle returned after stop`() {
        val installerEntered = CountDownLatch(1)
        val releaseInstaller = CountDownLatch(1)
        val handle = MinestomRecordingHandle()
        val module = module { _, _ ->
            installerEntered.countDown()
            check(releaseInstaller.await(5, TimeUnit.SECONDS))
            handle
        }

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val installation = executor.submit { module.install(context()) }
            assertTrue(installerEntered.await(5, TimeUnit.SECONDS))

            module.stop()
            releaseInstaller.countDown()
            installation.get(5, TimeUnit.SECONDS)
        }

        assertTrue(handle.closed)
    }

    private fun module(
        installRuntime: (GroundsPermissionsModule, GroundsServerContext) -> AutoCloseable?
    ): GroundsPermissionsModule =
        GroundsPermissionsModule(
            runtimeInstaller = MinestomPermissionsRuntimeInstaller(installRuntime)
        )

    private fun context(): GroundsServerContext =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(GroundsServerContext::class.java)) {
            _,
            method,
            _ ->
            error("Unexpected server context method: ${method.name}")
        } as GroundsServerContext
}

private class MinestomRecordingHandle(private val onClose: () -> Unit = {}) : AutoCloseable {
    var closed = false

    override fun close() {
        check(!closed)
        closed = true
        onClose()
    }
}
