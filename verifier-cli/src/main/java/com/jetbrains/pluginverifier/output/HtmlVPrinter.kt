package com.jetbrains.pluginverifier.output

import com.google.common.collect.ImmutableMultimap
import com.google.common.collect.Multimap
import com.google.common.html.HtmlEscapers
import com.google.common.io.Resources
import com.intellij.structure.domain.IdeVersion
import com.jetbrains.pluginverifier.api.PluginDescriptor
import com.jetbrains.pluginverifier.api.VResult
import com.jetbrains.pluginverifier.api.VResults
import com.jetbrains.pluginverifier.format.UpdateInfo
import com.jetbrains.pluginverifier.location.ProblemLocation
import com.jetbrains.pluginverifier.problems.BrokenPluginProblem
import com.jetbrains.pluginverifier.problems.Problem
import com.jetbrains.pluginverifier.utils.ToStringCachedComparator
import com.jetbrains.pluginverifier.utils.VersionComparatorUtil
import java.io.File
import java.io.PrintWriter
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*
import kotlin.comparisons.compareBy

class HtmlVPrinter(val ideVersion: IdeVersion,
                   val isExcluded: (Pair<String, String>) -> Boolean,
                   val htmlFile: File) : VPrinter {

  private val UPDATE_DATE_FORMAT = SimpleDateFormat("yyyy.MM.dd HH:mm")

  override fun printResults(results: VResults) {
    PrintWriter(htmlFile).use { out ->

      out.append("<html>\n<head>\n  <title>Result of checking $ideVersion</title>\n\n  " +
          "<link rel='stylesheet' href='http://code.jquery.com/ui/1.10.4/themes/smoothness/jquery-ui.css'>\n  " +
          "<script src='http://code.jquery.com/jquery-1.9.1.js'></script>\n  " +
          "<script src='http://code.jquery.com/ui/1.10.4/jquery-ui.js'></script>\n  " +
          "<style type='text/css'>\n${Resources.toString(HtmlVPrinter::class.java.getResource("/reportCss.css"), Charset.forName("UTF-8"))}  " +
          "</style>\n" +
          "</head>\n\n" +
          "<body>\n\n<h2>$ideVersion</h2>\n<label>\n  <input id='problematicOnlyCB' type='checkbox' onchange=\"" +
          "if ($('#problematicOnlyCB').is(':checked')) {$('body').addClass('problematicOnly')} else {$('body').removeClass('problematicOnly')} \">\n  " +
          "Show problematic plugins only\n</label>\n")

      if (results.results.isEmpty()) {
        out.print("No plugins checked.\n")
      } else {
        results.results.groupBy { it.pluginDescriptor.pluginId }.forEach { pidToResults ->
          val pluginId = pidToResults.key

          out.printf("<div class='plugin %s'>\n", if (pluginHasProblems(pidToResults.value, isExcluded)) "pluginHasProblem" else "pluginOk")
          out.printf("  <h3><span class='pMarker'>   </span> %s</h3>\n", HtmlEscapers.htmlEscaper().escape(pluginId))
          out.printf("  <div>\n")

          if (pidToResults.value.isEmpty()) {
            out.printf("There are no updates compatible with %s in the Plugin Repository\n", ideVersion)
          } else {

            pidToResults.value
                .sortedWith(compareBy(VersionComparatorUtil.COMPARATOR, { it.pluginDescriptor.version }))
                .groupBy { it.pluginDescriptor.version }
                .mapValues { it.value.first() }
                .forEach { versionToResult ->
                  val version = versionToResult.key
                  val vResult = versionToResult.value
                  val descriptor = vResult.pluginDescriptor
                  val updateInfo = if (descriptor is PluginDescriptor.ByUpdateInfo) descriptor.updateInfo else UpdateInfo(descriptor.pluginId, descriptor.pluginId, descriptor.version)
                  val problems: Multimap<Problem, ProblemLocation> = when (vResult) {
                    is VResult.Nice -> ImmutableMultimap.of()
                    is VResult.Problems -> vResult.problems
                    is VResult.BadPlugin -> ImmutableMultimap.of(BrokenPluginProblem(vResult.overview), ProblemLocation.fromPlugin(pluginId))
                  }

                  out.printf("<div class='update %s %s'>\n", if (vResult is VResult.Nice) "updateOk" else "updateHasProblems", if (isExcluded(pluginId to version)) "excluded" else "")

                  out.printf("  <h3><span class='uMarker'>   </span> %s <small>(#%d%s)</small> %s</h3>\n",
                      HtmlEscapers.htmlEscaper().escape(version),
                      updateInfo.updateId,
                      if (updateInfo.cdate == null) "" else ", " + UPDATE_DATE_FORMAT.format(Date(updateInfo.cdate!!)),
                      if (problems.isEmpty) "" else "<small>" + problems.keySet().size + " problems found</small>")

                  out.printf("  <div>\n")

                  if (problems.isEmpty) {
                    out.printf("No problems.\n")
                  } else {
                    for (problem in problems.keySet()) {
                      out.append("    <div class='errorDetails'>").append(HtmlEscapers.htmlEscaper().escape(problem.description)).append(' ').append("<a href=\"#\" class='detailsLink'>details</a>\n")


                      out.append("<div class='errLoc'>")

                      var isFirst = true
                      for (location in problems.get(problem).sortedWith(ToStringCachedComparator<ProblemLocation>())) {
                        if (isFirst) {
                          isFirst = false
                        } else {
                          out.append("<br>")
                        }

                        out.append(HtmlEscapers.htmlEscaper().escape(location.toString()))
                      }

                      out.append("</div></div>")
                    }
                  }

                  out.printf("  </div>\n")
                  out.printf("  </div>\n") // <div class='update'>

                }

          }
          out.printf("  </div>\n")
          out.printf("</div>\n") //  <div class='plugin'>
        }

      }

      out.append("<script>\n")
      out.append(Resources.toString(HtmlVPrinter::class.java.getResource("/reportScript.js"), Charset.forName("UTF-8")))
      out.append("</script>\n")

      out.append("</body>\n")
      out.append("</html>")
    }

  }

  fun pluginHasProblems(pluginResults: List<VResult>,
                        isExcluded: (Pair<String, String>) -> Boolean): Boolean =
      pluginResults
          .filterNot { isExcluded.invoke(it.pluginDescriptor.pluginId to it.pluginDescriptor.version) }
          .filterNot { it is VResult.Nice }
          .isNotEmpty()

}



