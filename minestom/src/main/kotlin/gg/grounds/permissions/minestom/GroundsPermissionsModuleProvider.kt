package gg.grounds.permissions.minestom

import gg.grounds.BuildInfo
import gg.grounds.modules.ModuleDescriptor
import gg.grounds.modules.serviceKey
import gg.grounds.permissions.Permissions
import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsModuleProvider
import gg.grounds.runtime.ServerType

class GroundsPermissionsModuleProvider : GroundsModuleProvider {
    override val id: String = GroundsPermissionsModule.MODULE_ID
    override val version: String = BuildInfo.VERSION
    override val serverTypes: Set<ServerType> = ServerType.entries.toSet()
    override val descriptor: ModuleDescriptor =
        ModuleDescriptor(id = id, version = version, provides = setOf(serviceKey<Permissions>()))

    override fun create(): GroundsModule = GroundsPermissionsModule()
}
