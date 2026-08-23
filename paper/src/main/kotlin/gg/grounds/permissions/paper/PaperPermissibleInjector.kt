package gg.grounds.permissions.paper

import java.lang.reflect.Field
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.entity.Player
import org.bukkit.permissions.PermissibleBase

class PaperPermissibleInjectionException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class PaperPermissibleInjector(
    private val target: (Player) -> Any = { it },
) {
    private val originals = ConcurrentHashMap<UUID, PermissibleBase>()

    fun validate(playerClass: Class<*>) {
        PermissibleFieldAccess.locate(playerClass)
        PermissibleBaseStateAccess.validated()
    }

    fun inject(player: Player, permissible: GroundsPermissible) {
        val holder = target(player)
        val access = PermissibleFieldAccess.locate(holder.javaClass)
        val current = access.read(holder)
        when (current) {
            is GroundsPermissible -> throw failure(player, "Grounds permissible already injected")
            else ->
                if (current.javaClass != PermissibleBase::class.java) {
                    throw failure(
                        player,
                        "Another permission provider already installed ${current.javaClass.name}",
                    )
                }
        }

        if (originals.putIfAbsent(player.uniqueId, current) != null) {
            throw failure(player, "Grounds permissible already injected")
        }
        try {
            val state = PermissibleBaseStateAccess.validated()
            permissible.importAttachments(state.attachments(current))
            state.clear(current)
            access.write(holder, permissible)
        } catch (exception: PaperPermissibleInjectionException) {
            originals.remove(player.uniqueId, current)
            throw exception
        } catch (exception: ReflectiveOperationException) {
            originals.remove(player.uniqueId, current)
            throw failure(player, "Could not install Grounds permissible", exception)
        }
    }

    fun restore(player: Player) {
        val original = originals.remove(player.uniqueId) ?: return
        val holder = target(player)
        val access = PermissibleFieldAccess.locate(holder.javaClass)
        val current = access.read(holder)
        if (current is GroundsPermissible) current.clearPermissions()
        access.write(holder, original)
    }

    fun retire(player: Player) {
        originals.remove(player.uniqueId)
        val holder = target(player)
        val access = PermissibleFieldAccess.locate(holder.javaClass)
        (access.read(holder) as? GroundsPermissible)?.clearPermissions()
        access.write(holder, RetiredPermissible)
    }

    private fun failure(player: Player, detail: String, cause: Throwable? = null) =
        PaperPermissibleInjectionException(
            "Cannot manage permissible for player ${player.uniqueId} (${player.javaClass.name}): $detail",
            cause,
        )

    private object RetiredPermissible : PermissibleBase(null) {
        override fun isPermissionSet(permission: String): Boolean = false

        override fun hasPermission(permission: String): Boolean = false
    }
}

internal class PermissibleFieldAccess private constructor(private val field: Field) {
    fun read(target: Any): PermissibleBase =
        try {
            field.get(target) as? PermissibleBase
                ?: throw PaperPermissibleInjectionException("Field ${fieldContext()} did not contain a PermissibleBase")
        } catch (exception: IllegalAccessException) {
            throw PaperPermissibleInjectionException("Could not read ${fieldContext()}", exception)
        }

    fun write(target: Any, permissible: PermissibleBase) {
        try {
            field.set(target, permissible)
        } catch (exception: IllegalAccessException) {
            throw PaperPermissibleInjectionException("Could not write ${fieldContext()}", exception)
        }
    }

    private fun fieldContext() = "permissible field on ${field.declaringClass.name}"

    companion object {
        fun locate(targetClass: Class<*>): PermissibleFieldAccess {
            var type: Class<*>? = targetClass
            while (type != null) {
                val field = type.declaredFields.firstOrNull { it.name == "perm" }
                if (field != null) {
                    if (!PermissibleBase::class.java.isAssignableFrom(field.type)) {
                        throw PaperPermissibleInjectionException(
                            "Permissible field on ${targetClass.name} has incompatible type ${field.type.name}",
                        )
                    }
                    if (!field.trySetAccessible()) {
                        throw PaperPermissibleInjectionException(
                            "Permissible field on ${targetClass.name} is not accessible",
                        )
                    }
                    return PermissibleFieldAccess(field)
                }
                type = type.superclass
            }
            throw PaperPermissibleInjectionException("No permissible field found on ${targetClass.name}")
        }
    }
}

private class PermissibleBaseStateAccess private constructor(
    private val attachments: Field,
    private val permissions: Field,
) {
    @Suppress("UNCHECKED_CAST")
    fun attachments(permissible: PermissibleBase): Collection<org.bukkit.permissions.PermissionAttachment> =
        try {
            (attachments.get(permissible) as? Collection<*>)?.filterIsInstance<org.bukkit.permissions.PermissionAttachment>()
                ?: throw PaperPermissibleInjectionException("PermissibleBase attachments field is not a collection")
        } catch (exception: IllegalAccessException) {
            throw PaperPermissibleInjectionException("Could not read PermissibleBase attachments", exception)
        }

    @Suppress("UNCHECKED_CAST")
    fun clear(permissible: PermissibleBase) {
        try {
            (attachments.get(permissible) as? MutableCollection<*>)?.clear()
                ?: throw PaperPermissibleInjectionException("PermissibleBase attachments field is not mutable")
            (permissions.get(permissible) as? MutableMap<*, *>)?.clear()
                ?: throw PaperPermissibleInjectionException("PermissibleBase permissions field is not mutable")
        } catch (exception: IllegalAccessException) {
            throw PaperPermissibleInjectionException("Could not clear PermissibleBase state", exception)
        }
    }

    companion object {
        fun validated(): PermissibleBaseStateAccess =
            PermissibleBaseStateAccess(
                field("attachments", MutableCollection::class.java),
                field("permissions", MutableMap::class.java),
            )

        private fun field(name: String, expectedType: Class<*>): Field {
            val field =
                try {
                    PermissibleBase::class.java.getDeclaredField(name)
                } catch (exception: NoSuchFieldException) {
                    throw PaperPermissibleInjectionException(
                        "PermissibleBase $name field does not exist",
                        exception,
                    )
                }
            if (!expectedType.isAssignableFrom(field.type) || !field.trySetAccessible()) {
                throw PaperPermissibleInjectionException(
                    "PermissibleBase $name field is incompatible or inaccessible",
                )
            }
            return field
        }
    }
}
