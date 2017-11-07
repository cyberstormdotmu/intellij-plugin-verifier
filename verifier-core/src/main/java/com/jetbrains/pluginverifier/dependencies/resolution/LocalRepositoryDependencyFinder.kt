package com.jetbrains.pluginverifier.dependencies.resolution

import com.jetbrains.plugin.structure.intellij.plugin.PluginDependency
import com.jetbrains.pluginverifier.plugin.PluginCoordinate
import com.jetbrains.pluginverifier.plugin.PluginDetailsProvider
import com.jetbrains.pluginverifier.repository.local.LocalPluginRepository

/**
 * Created by Sergey.Patrikeev
 */
class LocalRepositoryDependencyFinder(private val localPluginRepository: LocalPluginRepository,
                                      private val pluginDetailsProvider: PluginDetailsProvider) : DependencyFinder {
  override fun findPluginDependency(dependency: PluginDependency): DependencyFinder.Result {
    if (dependency.isModule) {
      return DependencyFinder.Result.NotFound("Modules are not supported by the local repositories: $dependency")
    }
    val localPlugin = localPluginRepository.findPluginById(dependency.id)
    return if (localPlugin != null) {
      DependencyFinder.Result.FoundCoordinates(PluginCoordinate.ByFile(localPlugin.pluginFile), pluginDetailsProvider)
    } else {
      DependencyFinder.Result.NotFound("$dependency is not found in the local repository $localPluginRepository")
    }
  }
}