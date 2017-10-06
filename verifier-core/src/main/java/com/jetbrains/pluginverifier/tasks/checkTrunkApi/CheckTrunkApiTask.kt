package com.jetbrains.pluginverifier.tasks.checkTrunkApi

import com.jetbrains.plugin.structure.classes.resolvers.EmptyResolver
import com.jetbrains.plugin.structure.intellij.plugin.PluginDependency
import com.jetbrains.pluginverifier.dependencies.resolution.BundledPluginDependencyFinder
import com.jetbrains.pluginverifier.dependencies.resolution.DependencyFinder
import com.jetbrains.pluginverifier.dependencies.resolution.RepositoryDependencyFinder
import com.jetbrains.pluginverifier.dependencies.resolution.repository.LastCompatibleSelector
import com.jetbrains.pluginverifier.dependencies.resolution.repository.LastSelector
import com.jetbrains.pluginverifier.parameters.ide.IdeDescriptor
import com.jetbrains.pluginverifier.parameters.ide.IdeResourceUtil
import com.jetbrains.pluginverifier.plugin.PluginCoordinate
import com.jetbrains.pluginverifier.plugin.PluginDetailsProvider
import com.jetbrains.pluginverifier.reporting.verification.VerificationReportage
import com.jetbrains.pluginverifier.repository.PluginRepository
import com.jetbrains.pluginverifier.repository.UpdateInfo
import com.jetbrains.pluginverifier.tasks.Task
import com.jetbrains.pluginverifier.tasks.checkIde.CheckIdeParams
import com.jetbrains.pluginverifier.tasks.checkIde.CheckIdeResult
import com.jetbrains.pluginverifier.tasks.checkIde.CheckIdeTask
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * @author Sergey Patrikeev
 */
class CheckTrunkApiTask(private val parameters: CheckTrunkApiParams,
                        private val pluginRepository: PluginRepository,
                        private val pluginDetailsProvider: PluginDetailsProvider) : Task() {

  companion object {
    private val LOG: Logger = LoggerFactory.getLogger(CheckTrunkApiTask::class.java)
  }

  override fun execute(verificationReportage: VerificationReportage): CheckTrunkApiResult {
    val releaseVersion = parameters.releaseIde.ideVersion
    val trunkVersion = parameters.trunkIde.ideVersion

    val pluginsToCheck = pluginRepository.getLastCompatibleUpdates(releaseVersion).filterNot { it.pluginId in parameters.jetBrainsPluginIds }

    LOG.debug("The following updates will be checked with both #$trunkVersion and #$releaseVersion: " + pluginsToCheck.joinToString())

    val releaseResults = checkIde(parameters.releaseIde, pluginsToCheck, ReleaseFinder(), verificationReportage)
    val trunkResults = checkIde(parameters.trunkIde, pluginsToCheck, TrunkFinder(), verificationReportage)

    return CheckTrunkApiResult(trunkResults, releaseResults)
  }

  private fun checkIde(ideDescriptor: IdeDescriptor,
                       pluginsToCheck: List<UpdateInfo>,
                       dependencyFinder: DependencyFinder,
                       progress: VerificationReportage): CheckIdeResult {
    val pluginCoordinates = pluginsToCheck.map { PluginCoordinate.ByUpdateInfo(it, pluginRepository) }
    val excludedPlugins = IdeResourceUtil.getBrokenPluginsListedInIde(ideDescriptor.ide) ?: emptyList()
    val checkIdeParams = CheckIdeParams(ideDescriptor,
        parameters.jdkDescriptor,
        pluginCoordinates,
        excludedPlugins,
        emptyList(),
        EmptyResolver,
        parameters.externalClassesPrefixes,
        parameters.problemsFilters,
        dependencyFinder
    )
    return CheckIdeTask(checkIdeParams, pluginRepository, pluginDetailsProvider).execute(progress)
  }

  private val downloadReleaseCompatibleResolver = RepositoryDependencyFinder(pluginRepository, LastCompatibleSelector(parameters.releaseIde.ideVersion), pluginDetailsProvider)

  private inner class ReleaseFinder : DependencyFinder {

    private val releaseBundledResolver = BundledPluginDependencyFinder(parameters.releaseIde.ide, pluginDetailsProvider)

    override fun findPluginDependency(dependency: PluginDependency): DependencyFinder.Result {
      val result = releaseBundledResolver.findPluginDependency(dependency)
      return if (result is DependencyFinder.Result.NotFound) {
        downloadReleaseCompatibleResolver.findPluginDependency(dependency)
      } else {
        result
      }
    }
  }

  private inner class TrunkFinder : DependencyFinder {

    private val trunkBundledResolver = BundledPluginDependencyFinder(parameters.trunkIde.ide, pluginDetailsProvider)

    private val downloadLastUpdateResolver = RepositoryDependencyFinder(pluginRepository, LastSelector(), pluginDetailsProvider)

    override fun findPluginDependency(dependency: PluginDependency): DependencyFinder.Result {
      val bundledResult = trunkBundledResolver.findPluginDependency(dependency)
      if (bundledResult !is DependencyFinder.Result.NotFound) {
        return bundledResult
      }

      if (dependency.isModule || dependency.id in parameters.jetBrainsPluginIds) {
        return downloadLastUpdateResolver.findPluginDependency(dependency)
      }

      return downloadReleaseCompatibleResolver.findPluginDependency(dependency)
    }
  }


}