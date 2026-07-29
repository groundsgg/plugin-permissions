package gg.grounds.permissions.velocity

import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.proxy.ProxyServer
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroundsPermissionsPluginLifecycleTest {
    @Test
    fun `reinitialization closes the previous invalidation handle before owning the next`() {
        val events = mutableListOf<String>()
        var initialization = 0
        val plugin = plugin {
            initialization++
            val current = initialization
            events += "create-$current"
            RecordingHandle { events += "close-$current" }
        }

        plugin.onInitialize(ProxyInitializeEvent())
        plugin.onInitialize(ProxyInitializeEvent())
        plugin.onShutdown(ProxyShutdownEvent())
        plugin.onShutdown(ProxyShutdownEvent())

        assertEquals(listOf("create-1", "close-1", "create-2", "close-2"), events)
    }

    @Test
    fun `shutdown during initialization closes the handle returned after shutdown`() {
        val initializerEntered = CountDownLatch(1)
        val releaseInitializer = CountDownLatch(1)
        val handle = RecordingHandle()
        val plugin = plugin {
            initializerEntered.countDown()
            check(releaseInitializer.await(5, TimeUnit.SECONDS))
            handle
        }

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val initialization = executor.submit { plugin.onInitialize(ProxyInitializeEvent()) }
            assertTrue(initializerEntered.await(5, TimeUnit.SECONDS))

            plugin.onShutdown(ProxyShutdownEvent())
            releaseInitializer.countDown()
            initialization.get(5, TimeUnit.SECONDS)
        }

        assertTrue(handle.closed)
    }

    @Test
    fun `unavailable optional invalidations leave outer initialization operational`() {
        val plugin = plugin { null }

        plugin.onInitialize(ProxyInitializeEvent())
        plugin.onShutdown(ProxyShutdownEvent())
    }

    private fun plugin(
        initializeRuntime: (GroundsPermissionsPlugin) -> AutoCloseable?
    ): GroundsPermissionsPlugin =
        GroundsPermissionsPlugin(
            proxy = proxy(),
            logger = RecordingLogger(),
            runtimeInitializer = VelocityPermissionsRuntimeInitializer(initializeRuntime),
        )

    private fun proxy(): ProxyServer =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ProxyServer::class.java)) {
            _,
            method,
            _ ->
            error("Unexpected proxy method: ${method.name}")
        } as ProxyServer
}

private class RecordingHandle(private val onClose: () -> Unit = {}) : AutoCloseable {
    var closed = false

    override fun close() {
        check(!closed)
        closed = true
        onClose()
    }
}
