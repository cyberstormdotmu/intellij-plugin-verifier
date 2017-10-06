package com.jetbrains.pluginverifier.core

import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.jetbrains.plugin.structure.classes.jdk.JdkResolverCreator
import com.jetbrains.plugin.structure.classes.resolvers.Resolver
import com.jetbrains.pluginverifier.misc.checkIfInterrupted
import com.jetbrains.pluginverifier.parameters.VerifierParameters
import com.jetbrains.pluginverifier.parameters.ide.IdeDescriptor
import com.jetbrains.pluginverifier.parameters.jdk.JdkDescriptor
import com.jetbrains.pluginverifier.plugin.PluginCoordinate
import com.jetbrains.pluginverifier.plugin.PluginDetailsProvider
import com.jetbrains.pluginverifier.reporting.verification.VerificationReportage
import com.jetbrains.pluginverifier.results.Result
import java.io.Closeable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * @author Sergey Patrikeev
 */
class VerifierExecutor(concurrentWorkers: Int) : Closeable {

  private val executor: ExecutorService = Executors.newFixedThreadPool(concurrentWorkers,
      ThreadFactoryBuilder()
          .setDaemon(true)
          .setNameFormat("verifier-%d")
          .build()
  )

  private val completionService = ExecutorCompletionService<Result>(executor)

  override fun close() {
    executor.shutdownNow()
  }

  fun verify(
      tasks: List<Pair<PluginCoordinate, IdeDescriptor>>,
      jdkDescriptor: JdkDescriptor,
      parameters: VerifierParameters,
      pluginDetailsProvider: PluginDetailsProvider,
      reportage: VerificationReportage
  ) = JdkResolverCreator.createJdkResolver(jdkDescriptor.homeDir).use { jdkResolver ->
    runVerificationConcurrently(tasks, parameters, jdkResolver, pluginDetailsProvider, reportage)
  }

  private fun runVerificationConcurrently(
      tasks: List<Pair<PluginCoordinate, IdeDescriptor>>,
      parameters: VerifierParameters,
      jdkResolver: Resolver,
      pluginDetailsProvider: PluginDetailsProvider,
      reportage: VerificationReportage
  ): List<Result> {
    for ((pluginCoordinate, ideDescriptor) in tasks) {
      val pluginLogger = reportage.createPluginLogger(pluginCoordinate, ideDescriptor)
      val verifier = PluginVerifier(pluginCoordinate, ideDescriptor, jdkResolver, parameters, pluginDetailsProvider, pluginLogger)
      completionService.submit(verifier)
    }
    return waitForAllResults(completionService, tasks.size)
  }

  private fun waitForAllResults(completionService: ExecutorCompletionService<Result>, tasks: Int): List<Result> {
    val results = arrayListOf<Result>()
    for (finished in 1..tasks) {
      while (true) {
        checkIfInterrupted()
        val future = completionService.poll(500, TimeUnit.MILLISECONDS)
        if (future != null) {
          val result = future.get()
          results.add(result)
          break
        }
      }
    }
    return results
  }

}