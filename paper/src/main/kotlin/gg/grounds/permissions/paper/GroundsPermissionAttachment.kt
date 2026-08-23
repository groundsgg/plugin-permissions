package gg.grounds.permissions.paper

import java.util.Collections
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.bukkit.permissions.PermissionAttachment
import org.bukkit.permissions.PermissionRemovedExecutor
import org.bukkit.plugin.Plugin

internal class GroundsPermissionAttachment(
    private val owner: GroundsPermissible,
    plugin: Plugin,
    sourcePermissions: Map<String, Boolean> = emptyMap(),
) : PermissionAttachment(plugin, owner) {
    private val values = Collections.synchronizedMap(HashMap<String, Boolean>())
    private val hooked = AtomicBoolean()

    @Volatile private var callback: PermissionRemovedExecutor? = null

    init {
        sourcePermissions.forEach { (node, value) -> values[normalize(node)] = value }
    }

    override fun setPermission(name: String, value: Boolean) {
        require(name.isNotEmpty()) { "name is empty" }
        values[normalize(name)] = value
        owner.attachmentChanged(this)
    }

    override fun unsetPermission(name: String) {
        require(name.isNotEmpty()) { "name is empty" }
        values.remove(normalize(name))
        owner.attachmentChanged(this)
    }

    override fun getPermissions(): Map<String, Boolean> = Collections.unmodifiableMap(snapshotPermissions())

    override fun setRemovalCallback(callback: PermissionRemovedExecutor?) {
        this.callback = callback
    }

    override fun getRemovalCallback(): PermissionRemovedExecutor? = callback

    override fun remove(): Boolean = owner.detach(this)

    internal fun permissionsSnapshot(): Map<String, Boolean> = snapshotPermissions()

    internal fun markHooked(): Boolean = hooked.compareAndSet(false, true)

    internal fun markRemoved(): Boolean = hooked.compareAndSet(true, false)

    internal fun notifyRemoved() = callback?.attachmentRemoved(this)

    private fun snapshotPermissions(): Map<String, Boolean> = synchronized(values) { HashMap(values) }

    private fun normalize(node: String): String = node.lowercase(Locale.ROOT)
}
