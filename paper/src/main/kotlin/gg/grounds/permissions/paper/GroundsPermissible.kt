package gg.grounds.permissions.paper

import gg.grounds.permissions.PermissionDecision
import gg.grounds.permissions.PermissionPatterns
import gg.grounds.permissions.Permissions
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.entity.Player
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionAttachment
import org.bukkit.permissions.PermissionAttachmentInfo
import org.bukkit.permissions.PermissibleBase
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin

class GroundsPermissible(
    private val player: Player,
    private val plugin: JavaPlugin,
    private val permissions: Permissions,
) : PermissibleBase(player) {
    private val attachments = ConcurrentHashMap.newKeySet<GroundsPermissionAttachment>()

    fun importAttachments(attachments: Collection<PermissionAttachment>) {
        attachments.forEach { attachment ->
            if (attachment is GroundsPermissionAttachment && attachment.permissible === this) {
                attach(attachment)
            } else {
                GroundsPermissionAttachment(this, attachment.plugin, attachment.permissions).also { imported ->
                    imported.removalCallback = attachment.removalCallback
                    attach(imported)
                }
            }
        }
    }

    internal fun currentAttachments(): Set<GroundsPermissionAttachment> = LinkedHashSet(attachments)

    override fun isOp(): Boolean = player.isOp

    override fun setOp(value: Boolean) {
        player.isOp = value
    }

    override fun isPermissionSet(permission: Permission): Boolean = isPermissionSet(permission.name)

    override fun isPermissionSet(permission: String): Boolean =
        attachmentDecision(permission) != PermissionDecision.UNSET ||
            permissions.permissionDecision(player.uniqueId, normalize(permission)) != PermissionDecision.UNSET

    override fun hasPermission(permission: Permission): Boolean = hasPermission(permission.name)

    override fun hasPermission(permission: String): Boolean =
        when (attachmentDecision(permission)) {
            PermissionDecision.ALLOW -> true
            PermissionDecision.DENY -> false
            PermissionDecision.UNSET ->
                permissions.permissionDecision(player.uniqueId, normalize(permission)) == PermissionDecision.ALLOW
        }

    override fun addAttachment(plugin: Plugin): PermissionAttachment =
        GroundsPermissionAttachment(this, plugin).also(::attach)

    override fun addAttachment(plugin: Plugin, name: String, value: Boolean): PermissionAttachment =
        GroundsPermissionAttachment(this, plugin, mapOf(name to value)).also(::attach)

    override fun addAttachment(plugin: Plugin, ticks: Int): PermissionAttachment? {
        if (!plugin.isEnabled) return null
        return addAttachment(plugin).also { attachment -> scheduleRemoval(plugin, attachment, ticks) }
    }

    override fun addAttachment(
        plugin: Plugin,
        name: String,
        value: Boolean,
        ticks: Int,
    ): PermissionAttachment? {
        if (!plugin.isEnabled) return null
        return addAttachment(plugin, name, value).also { attachment -> scheduleRemoval(plugin, attachment, ticks) }
    }

    override fun removeAttachment(attachment: PermissionAttachment) {
        if (attachment is GroundsPermissionAttachment) detach(attachment)
    }

    override fun recalculatePermissions() = Unit

    override fun getEffectivePermissions(): Set<PermissionAttachmentInfo> {
        val effective = LinkedHashSet<PermissionAttachmentInfo>()
        permissions.snapshot(player.uniqueId)?.let { snapshot ->
            snapshot.allowPatterns.forEach { grant ->
                effective += PermissionAttachmentInfo(this, grant.pattern, null, true)
            }
            snapshot.denyPatterns.forEach { grant ->
                effective += PermissionAttachmentInfo(this, grant.pattern, null, false)
            }
        }
        attachments.forEach { attachment ->
            attachment.permissionsSnapshot().forEach { (node, value) ->
                effective += PermissionAttachmentInfo(this, node, attachment, value)
            }
        }
        return effective
    }

    override fun clearPermissions() = currentAttachments().forEach(::removeAttachment)

    internal fun attachmentChanged(attachment: GroundsPermissionAttachment) {
        if (attachments.contains(attachment)) recalculatePermissions()
    }

    internal fun detach(attachment: GroundsPermissionAttachment): Boolean {
        if (!attachments.remove(attachment)) return false
        if (attachment.markRemoved()) attachment.notifyRemoved()
        return true
    }

    private fun attach(attachment: GroundsPermissionAttachment) {
        if (attachment.markHooked()) attachments += attachment
    }

    private fun scheduleRemoval(plugin: Plugin, attachment: PermissionAttachment, ticks: Int) {
        plugin.server.scheduler.runTaskLater(plugin, Runnable { removeAttachment(attachment) }, ticks.toLong())
    }

    private fun attachmentDecision(permission: String): PermissionDecision {
        val normalized = normalize(permission)
        val candidate =
            attachments.asSequence()
                .flatMap { attachment ->
                    attachment.permissionsSnapshot().asSequence().map { (pattern, value) ->
                        AttachmentCandidate(pattern, value)
                    }
                }
                .filter { PermissionPatterns.matches(it.pattern, normalized) }
                .maxWithOrNull(
                    compareBy<AttachmentCandidate> { PermissionPatterns.specificity(it.pattern) }
                        .thenBy { if (it.value) 0 else 1 }
                )
        return when (candidate?.value) {
            true -> PermissionDecision.ALLOW
            false -> PermissionDecision.DENY
            null -> PermissionDecision.UNSET
        }
    }

    private fun normalize(permission: String): String = permission.lowercase(Locale.ROOT)

    private data class AttachmentCandidate(val pattern: String, val value: Boolean)
}
