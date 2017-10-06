package com.jetbrains.pluginverifier.output.html

import com.google.common.io.Resources
import com.jetbrains.plugin.structure.intellij.version.IdeVersion
import com.jetbrains.pluginverifier.dependencies.MissingDependency
import com.jetbrains.pluginverifier.misc.VersionComparatorUtil
import com.jetbrains.pluginverifier.misc.create
import com.jetbrains.pluginverifier.misc.pluralize
import com.jetbrains.pluginverifier.output.ResultPrinter
import com.jetbrains.pluginverifier.output.settings.dependencies.MissingDependencyIgnoring
import com.jetbrains.pluginverifier.repository.PluginIdAndVersion
import com.jetbrains.pluginverifier.repository.PluginInfo
import com.jetbrains.pluginverifier.repository.UpdateInfo
import com.jetbrains.pluginverifier.results.Result
import com.jetbrains.pluginverifier.results.Verdict
import com.jetbrains.pluginverifier.results.problems.Problem
import com.jetbrains.pluginverifier.results.warnings.Warning
import java.io.File
import java.io.PrintWriter
import java.nio.charset.Charset

class HtmlResultPrinter(val ideVersions: List<IdeVersion>,
                        val isExcluded: (PluginIdAndVersion) -> Boolean,
                        val htmlFile: File,
                        private val missingDependencyIgnoring: MissingDependencyIgnoring) : ResultPrinter {

  override fun printResults(results: List<Result>) {
    PrintWriter(htmlFile.create()).use {
      val htmlBuilder = HtmlBuilder(it)
      doPrintResults(htmlBuilder, results)
    }
  }

  private fun doPrintResults(htmlBuilder: HtmlBuilder, results: List<Result>) {
    htmlBuilder.apply {
      html {
        head {
          title("Verification result of IDE ${ideVersions.joinToString()}")
          script(src = "https://ajax.aspnetcdn.com/ajax/jQuery/jquery-1.9.1.min.js", type = "text/javascript")
          script(src = "https://code.jquery.com/ui/1.9.2/jquery-ui.min.js", type = "text/javascript")
          link(rel = "stylesheet", href = "https://code.jquery.com/ui/1.9.2/themes/base/jquery-ui.css", type = "text/css")
          style(type = "text/css") { unsafe(loadReportCss()) }
        }
        body {
          h2 { +ideVersions.joinToString() }
          label {
            unsafe("""<input id="problematicOnlyCB" type="checkbox" onchange="if ($('#problematicOnlyCB').is(':checked')) {$('body').addClass('problematicOnly')} else {$('body').removeClass('problematicOnly')}">""")
            +"Show problematic plugins only"
          }
          if (results.isEmpty()) {
            +"No plugins checked"
          } else {
            results.sortedBy { it.plugin.pluginId }.groupBy { it.plugin.pluginId }.forEach { (pluginId, pluginResults) ->
              appendPluginResults(pluginResults, pluginId)
            }
          }
          script { unsafe(loadReportScript()) }
        }
      }
    }
  }

  private fun HtmlBuilder.appendPluginResults(pluginResults: List<Result>, pluginId: String) {
    div(classes = "plugin " + getPluginStyle(pluginResults)) {
      h3 {
        span(classes = "pMarker") { +"    " }
        +pluginId
      }
      div {
        pluginResults
            .filterNot { isExcluded(PluginIdAndVersion(it.plugin.pluginId, it.plugin.version)) }
            .sortedWith(compareBy(VersionComparatorUtil.COMPARATOR, { it.plugin.version }))
            .associateBy({ it.plugin }, { it.verdict })
            .forEach { (plugin, verdict) -> printPluginVerdict(verdict, pluginId, plugin) }
      }
    }
  }

  private fun getPluginStyle(pluginResults: List<Result>): String {
    val verdicts = pluginResults
        .filterNot { isExcluded(PluginIdAndVersion(it.plugin.pluginId, it.plugin.version)) }
        .map { it.verdict }
    if (verdicts.any { it is Verdict.Problems }) {
      return "pluginHasProblems"
    }
    if (verdicts.any { it is Verdict.MissingDependencies }) {
      return "missingDeps"
    }
    if (verdicts.any { it is Verdict.Bad }) {
      return "badPlugin"
    }
    if (verdicts.any { it is Verdict.Warnings }) {
      return "warnings"
    }
    return "pluginOk"
  }

  private fun HtmlBuilder.printPluginVerdict(verdict: Verdict, pluginId: String, plugin: PluginInfo) {
    val verdictStyle = when (verdict) {
      is Verdict.OK -> "updateOk"
      is Verdict.Warnings -> "warnings"
      is Verdict.MissingDependencies -> "missingDeps"
      is Verdict.Problems -> "updateHasProblems"
      is Verdict.Bad -> "badPlugin"
      is Verdict.NotFound -> "notFound"
      is Verdict.FailedToDownload -> "failedToDownload"
    }
    val excludedStyle = if (isExcluded(PluginIdAndVersion(pluginId, plugin.version))) "excluded" else ""
    div(classes = "update $verdictStyle $excludedStyle") {
      h3 {
        printUpdateHeader(plugin, verdict, pluginId)
      }
      div {
        printVerificationResult(verdict, plugin)
      }
    }
  }

  private fun HtmlBuilder.printUpdateHeader(plugin: PluginInfo, verdict: Verdict, pluginId: String) {
    span(classes = "uMarker") { +"    " }
    +plugin.version
    small { +if (plugin is UpdateInfo) "(#${plugin.updateId})" else "" }
    small {
      +when (verdict) {
        is Verdict.OK -> "OK"
        is Verdict.Warnings -> "${verdict.warnings.size} " + "warning".pluralize(verdict.warnings.size) + " found"
        is Verdict.Problems -> "${verdict.problems.size} " + "problem".pluralize(verdict.problems.size) + " found"
        is Verdict.MissingDependencies -> "Plugin has " +
            "${verdict.directMissingDependencies.size} missing direct " + "dependency".pluralize(verdict.directMissingDependencies.size) + " and " +
            "${verdict.problems.size} " + "problem".pluralize(verdict.problems.size)
        is Verdict.Bad -> "Plugin is invalid"
        is Verdict.NotFound -> "Plugin $pluginId:${plugin.version} is not found in the Repository"
        is Verdict.FailedToDownload -> "Plugin $pluginId:${plugin.version} is not downloaded from the Repository"
      }
    }
  }

  private fun HtmlBuilder.printVerificationResult(verdict: Verdict, plugin: PluginInfo) {
    return when (verdict) {
      is Verdict.OK -> +"No problems."
      is Verdict.Warnings -> printWarnings(verdict.warnings)
      is Verdict.Problems -> printProblems(verdict.problems)
      is Verdict.Bad -> printShortAndFullDescription(verdict.pluginProblems.joinToString(), plugin.pluginId)
      is Verdict.NotFound -> printShortAndFullDescription("Plugin $plugin is not found in the Repository", verdict.reason)
      is Verdict.FailedToDownload -> printShortAndFullDescription("Plugin $plugin is not downloaded from the Repository", verdict.reason)
      is Verdict.MissingDependencies -> printMissingDependenciesResult(verdict)
    }
  }

  private fun HtmlBuilder.printMissingDependenciesResult(verdict: Verdict.MissingDependencies) {
    printProblems(verdict.problems)
    val missingDependencies = verdict.directMissingDependencies
    printMissingDependencies(missingDependencies.filterNot { it.dependency.isOptional })
    printMissingDependencies(missingDependencies.filter { it.dependency.isOptional && !missingDependencyIgnoring.ignoreMissingOptionalDependency(it.dependency) })
  }

  private fun HtmlBuilder.printWarnings(warnings: Set<Warning>) {
    p {
      warnings.forEach {
        +it.toString()
        br()
      }
    }
  }

  private fun HtmlBuilder.printMissingDependencies(nonOptionals: List<MissingDependency>) {
    nonOptionals.forEach { printShortAndFullDescription("missing dependency: $it", it.missingReason) }
  }

  private fun loadReportScript() = Resources.toString(HtmlResultPrinter::class.java.getResource("/reportScript.js"), Charset.forName("UTF-8"))

  private fun loadReportCss() = Resources.toString(HtmlResultPrinter::class.java.getResource("/reportCss.css"), Charset.forName("UTF-8"))

  private fun HtmlBuilder.printProblems(problems: Set<Problem>) {
    problems
        .sortedBy { it.shortDescription }
        .groupBy { it.shortDescription }
        .forEach { (shortDesc, problems) ->
          val allProblems = problems.joinToString(separator = "\n") { it.fullDescription }
          printShortAndFullDescription(shortDesc, allProblems)
        }
  }

  private fun HtmlBuilder.printShortAndFullDescription(shortDescription: String, fullDescription: String) {
    div(classes = "shortDescription") {
      +shortDescription
      +" "
      a(href = "#", classes = "detailsLink") {
        +"details"
      }
      div(classes = "longDescription") {
        +fullDescription
      }
    }
  }

}

