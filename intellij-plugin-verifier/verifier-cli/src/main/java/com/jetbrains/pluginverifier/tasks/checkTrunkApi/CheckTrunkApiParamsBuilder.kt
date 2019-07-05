package com.jetbrains.pluginverifier.tasks.checkTrunkApi

import com.jetbrains.plugin.structure.base.utils.closeOnException
import com.jetbrains.plugin.structure.base.utils.isDirectory
import com.jetbrains.plugin.structure.base.utils.listPresentationInColumns
import com.jetbrains.plugin.structure.ide.Ide
import com.jetbrains.plugin.structure.intellij.version.IdeVersion
import com.jetbrains.pluginverifier.VerificationTarget
import com.jetbrains.pluginverifier.ide.IdeDescriptor
import com.jetbrains.pluginverifier.ide.IdeFilesBank
import com.jetbrains.pluginverifier.ide.IdeResourceUtil
import com.jetbrains.pluginverifier.misc.retry
import com.jetbrains.pluginverifier.options.CmdOpts
import com.jetbrains.pluginverifier.options.OptionsParser
import com.jetbrains.pluginverifier.options.PluginsSet
import com.jetbrains.pluginverifier.options.filter.ExcludedPluginFilter
import com.jetbrains.pluginverifier.options.filter.PluginFilter
import com.jetbrains.pluginverifier.reporting.verification.Reportage
import com.jetbrains.pluginverifier.repository.PluginInfo
import com.jetbrains.pluginverifier.repository.PluginRepository
import com.jetbrains.pluginverifier.repository.files.FileLock
import com.jetbrains.pluginverifier.repository.files.IdleFileLock
import com.jetbrains.pluginverifier.repository.repositories.empty.EmptyPluginRepository
import com.jetbrains.pluginverifier.repository.repositories.local.LocalPluginRepositoryFactory
import com.jetbrains.pluginverifier.repository.repositories.marketplace.UpdateInfo
import com.jetbrains.pluginverifier.tasks.TaskParametersBuilder
import com.sampullara.cli.Args
import com.sampullara.cli.Argument
import java.nio.file.Paths

class CheckTrunkApiParamsBuilder(
    private val pluginRepository: PluginRepository,
    private val ideFilesBank: IdeFilesBank,
    private val reportage: Reportage
) : TaskParametersBuilder {

  override fun build(opts: CmdOpts, freeArgs: List<String>): CheckTrunkApiParams {
    val apiOpts = CheckTrunkApiOpts()
    val args = Args.parse(apiOpts, freeArgs.toTypedArray(), false)
    if (args.isEmpty()) {
      throw IllegalArgumentException("The IDE to be checked is not specified")
    }

    reportage.logVerificationStage("Reading classes of the trunk IDE ${args[0]}")
    val trunkIdeDescriptor = OptionsParser.createIdeDescriptor(Paths.get(args[0]), opts)
    return trunkIdeDescriptor.closeOnException {
      buildParameters(opts, apiOpts, trunkIdeDescriptor)
    }
  }

  private fun buildParameters(opts: CmdOpts, apiOpts: CheckTrunkApiOpts, trunkIdeDescriptor: IdeDescriptor): CheckTrunkApiParams {
    val releaseIdeFileLock: FileLock
    val deleteReleaseIdeOnExit: Boolean

    when {
      apiOpts.majorIdePath != null -> {
        val majorPath = Paths.get(apiOpts.majorIdePath!!)
        if (!majorPath.isDirectory) {
          throw IllegalArgumentException("The specified major IDE doesn't exist: $majorPath")
        }
        releaseIdeFileLock = IdleFileLock(majorPath)
        deleteReleaseIdeOnExit = false
      }
      apiOpts.majorIdeVersion != null -> {
        val ideVersion = parseIdeVersion(apiOpts.majorIdeVersion!!)
        releaseIdeFileLock = retry("download ide $ideVersion") {
          val result = ideFilesBank.getIdeFile(ideVersion)
          if (result is IdeFilesBank.Result.Found) {
            result.ideFileLock
          } else {
            throw RuntimeException("IDE $ideVersion is not found in $ideFilesBank")
          }
        }
        deleteReleaseIdeOnExit = !apiOpts.saveMajorIdeFile
      }
      else -> throw IllegalArgumentException("Neither the version (-miv) nor the path to the IDE (-mip) with which to compare API problems are specified")
    }

    reportage.logVerificationStage("Reading classes of the release IDE ${releaseIdeFileLock.file}")
    val releaseIdeDescriptor = OptionsParser.createIdeDescriptor(releaseIdeFileLock.file, opts)
    return releaseIdeDescriptor.closeOnException {
      releaseIdeFileLock.closeOnException {
        buildParameters(opts, apiOpts, releaseIdeDescriptor, trunkIdeDescriptor, deleteReleaseIdeOnExit, releaseIdeFileLock)
      }
    }
  }

  private fun buildParameters(
      opts: CmdOpts,
      apiOpts: CheckTrunkApiOpts,
      releaseIdeDescriptor: IdeDescriptor,
      trunkIdeDescriptor: IdeDescriptor,
      deleteReleaseIdeOnExit: Boolean,
      releaseIdeFileLock: FileLock
  ): CheckTrunkApiParams {
    val externalClassesPackageFilter = OptionsParser.getExternalClassesPackageFilter(opts)
    val problemsFilters = OptionsParser.getProblemsFilters(opts)

    val releaseVersion = releaseIdeDescriptor.ideVersion
    val trunkVersion = trunkIdeDescriptor.ideVersion

    val releaseLocalRepository = apiOpts.releaseLocalPluginRepositoryRoot
        ?.let { LocalPluginRepositoryFactory.createLocalPluginRepository(Paths.get(it)) }
        ?: EmptyPluginRepository

    val trunkLocalRepository = apiOpts.trunkLocalPluginRepositoryRoot
        ?.let { LocalPluginRepositoryFactory.createLocalPluginRepository(Paths.get(it)) }
        ?: EmptyPluginRepository

    val message = "Requesting a list of plugins compatible with the release IDE $releaseVersion"
    reportage.logVerificationStage(message)
    val releaseCompatibleVersions = pluginRepository.retry(message) {
      getLastCompatiblePlugins(releaseVersion)
    }

    val releaseExcludedPluginsFilter = ExcludedPluginFilter(IdeResourceUtil.getBrokenPlugins(releaseIdeDescriptor.ide))
    val releaseIgnoreInLocalRepositoryFilter = IgnorePluginsAvailableInOtherRepositoryFilter(releaseLocalRepository)
    val releaseBundledFilter = IgnoreBundledPluginsFilter(releaseIdeDescriptor.ide)

    val releasePluginsSet = PluginsSet()
    releasePluginsSet.addPluginFilter(releaseExcludedPluginsFilter)
    releasePluginsSet.addPluginFilter(releaseIgnoreInLocalRepositoryFilter)
    releasePluginsSet.addPluginFilter(releaseBundledFilter)

    releasePluginsSet.schedulePlugins(releaseCompatibleVersions)
    for ((pluginInfo, ignoreReason) in releasePluginsSet.ignoredPlugins) {
      reportage.logPluginVerificationIgnored(pluginInfo, VerificationTarget.Ide(releaseVersion), ignoreReason)
    }

    val trunkExcludedPluginsFilter = ExcludedPluginFilter(IdeResourceUtil.getBrokenPlugins(trunkIdeDescriptor.ide))
    val trunkIgnoreInLocalRepositoryFilter = IgnorePluginsAvailableInOtherRepositoryFilter(trunkLocalRepository)
    val trunkBundledFilter = IgnoreBundledPluginsFilter(trunkIdeDescriptor.ide)

    val trunkPluginsSet = PluginsSet()
    trunkPluginsSet.addPluginFilter(trunkExcludedPluginsFilter)
    trunkPluginsSet.addPluginFilter(trunkIgnoreInLocalRepositoryFilter)
    trunkPluginsSet.addPluginFilter(trunkBundledFilter)

    //Verify the same plugin versions as for the release IDE.
    trunkPluginsSet.schedulePlugins(releaseCompatibleVersions)

    //For plugins that are not compatible with the trunk IDE verify their latest versions, too.
    //This is in order to check if found compatibility problems are also present in the latest version.
    val latestCompatibleVersions = arrayListOf<PluginInfo>()
    for (pluginInfo in releaseCompatibleVersions) {
      if (!pluginInfo.isCompatibleWith(trunkVersion)) {
        val lastCompatibleVersion = pluginRepository.getLastCompatibleVersionOfPlugin(trunkVersion, pluginInfo.pluginId)
        if (lastCompatibleVersion != null && lastCompatibleVersion != pluginInfo) {
          latestCompatibleVersions += lastCompatibleVersion
        }
      }
    }
    trunkPluginsSet.schedulePlugins(latestCompatibleVersions)

    for ((pluginInfo, ignoreReason) in trunkPluginsSet.ignoredPlugins) {
      reportage.logPluginVerificationIgnored(pluginInfo, VerificationTarget.Ide(trunkVersion), ignoreReason)
    }

    val releasePluginsToCheck = releasePluginsSet.pluginsToCheck.sortedBy { (it as UpdateInfo).updateId }
    if (releasePluginsToCheck.isNotEmpty()) {
      reportage.logVerificationStage(
          "The following updates will be checked with both $trunkVersion and #$releaseVersion:\n" +
              releasePluginsToCheck
                  .listPresentationInColumns(4, 60)
      )
    }

    val trunkLatestPluginsToCheck = latestCompatibleVersions.filter { trunkPluginsSet.shouldVerifyPlugin(it) }
    if (trunkLatestPluginsToCheck.isNotEmpty()) {
      reportage.logVerificationStage(
          "The following updates will be checked with $trunkVersion only for comparison with the release versions of the same plugins:\n" +
              trunkLatestPluginsToCheck.listPresentationInColumns(4, 60)
      )
    }

    return CheckTrunkApiParams(
        releasePluginsSet,
        trunkPluginsSet,
        OptionsParser.getJdkPath(opts),
        trunkIdeDescriptor,
        releaseIdeDescriptor,
        externalClassesPackageFilter,
        problemsFilters,
        deleteReleaseIdeOnExit,
        releaseIdeFileLock,
        releaseLocalRepository,
        trunkLocalRepository
    )
  }

  private class IgnorePluginsAvailableInOtherRepositoryFilter(val repository: PluginRepository) : PluginFilter {
    override fun shouldVerifyPlugin(pluginInfo: PluginInfo): PluginFilter.Result {
      if (repository.getAllVersionsOfPlugin(pluginInfo.pluginId).isNotEmpty()) {
        return PluginFilter.Result.Ignore("Plugin is available in $repository")
      }
      return PluginFilter.Result.Verify
    }
  }

  private class IgnoreBundledPluginsFilter(val ide: Ide) : PluginFilter {
    override fun shouldVerifyPlugin(pluginInfo: PluginInfo): PluginFilter.Result {
      if (ide.getPluginById(pluginInfo.pluginId) != null) {
        return PluginFilter.Result.Ignore("Plugin is bundled with $ide")
      }
      return PluginFilter.Result.Verify
    }
  }

  private fun parseIdeVersion(ideVersion: String) = IdeVersion.createIdeVersionIfValid(ideVersion)
      ?: throw IllegalArgumentException(
          "Invalid IDE version: $ideVersion. Please provide IDE version (with product ID) with which to compare API problems; " +
              "See https://www.jetbrains.com/intellij-repository/releases/"
      )

}

class CheckTrunkApiOpts {
  @set:Argument("major-ide-version", alias = "miv", description = "The IDE version with which to compare API problems. This IDE will be downloaded from the IDE builds repository: https://www.jetbrains.com/intellij-repository/releases/.")
  var majorIdeVersion: String? = null

  @set:Argument("save-major-ide-file", alias = "smif", description = "Whether to save a downloaded release IDE in cache directory for use in later verifications")
  var saveMajorIdeFile: Boolean = false

  @set:Argument("major-ide-path", alias = "mip", description = "The path to release (major) IDE build with which to compare API problems in trunk (master) IDE build.")
  var majorIdePath: String? = null

  @set:Argument(
      "release-jetbrains-plugins", alias = "rjbp", description = "The root of the local plugin repository containing JetBrains plugins compatible with the release IDE. " +
      "The local repository is a set of non-bundled JetBrains plugins built from the same sources (see Installers/<artifacts>/IU-plugins). " +
      "The Plugin Verifier will read the plugin descriptors from every plugin-like file under the specified directory." +
      "On the release IDE verification, the JetBrains plugins will be taken from the local repository if present, and from the public repository, otherwise."
  )
  var releaseLocalPluginRepositoryRoot: String? = null

  @set:Argument("trunk-jetbrains-plugins", alias = "tjbp", description = "The same as --release-local-repository but specifies the local repository of the trunk IDE.")
  var trunkLocalPluginRepositoryRoot: String? = null

}
