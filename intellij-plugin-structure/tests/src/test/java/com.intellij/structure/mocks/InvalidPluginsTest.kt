package com.intellij.structure.mocks

import com.intellij.structure.impl.utils.ZipUtil
import com.intellij.structure.plugin.PluginCreationFail
import com.intellij.structure.plugin.PluginCreationSuccess
import com.intellij.structure.plugin.PluginManager
import com.intellij.structure.problems.*
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.instanceOf
import org.junit.Assert.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.IllegalArgumentException

class InvalidPluginsTest {

  @Rule
  @JvmField
  val temporaryFolder = TemporaryFolder()

  @Rule
  @JvmField
  val expectedEx: ExpectedException = ExpectedException.none()

  @Test
  fun `incorrect plugin file type`() {
    val incorrect = temporaryFolder.newFile("incorrect.txt")
    assertExpectedProblems(incorrect, listOf(IncorrectPluginFile(incorrect)))
  }

  @Test
  fun `failed to read jar file`() {
    val incorrect = temporaryFolder.newFile("incorrect.jar")
    assertExpectedProblems(incorrect, listOf(UnableToReadJarFile(incorrect)))
  }

  @Test()
  fun `plugin file does not exist`() {
    val nonExistentFile = File("non-existent-file")
    expectedEx.expect(IllegalArgumentException::class.java)
    expectedEx.expectMessage("Plugin file non-existent-file does not exist")
    PluginManager.getInstance().createPlugin(nonExistentFile)
  }

  @Test
  fun `unable to extract plugin`() {
    val brokenZipArchive = temporaryFolder.newFile("broken.zip")
    assertExpectedProblems(brokenZipArchive, listOf(UnableToExtractZip(brokenZipArchive)))
  }

  @Test
  fun `no meta-inf plugin xml found`() {
    val folder = temporaryFolder.newFolder()
    assertExpectedProblems(folder, listOf(PluginDescriptorIsNotFound("plugin.xml")))
  }

  private fun assertExpectedProblems(pluginFile: File, expectedProblems: List<PluginProblem>) {
    val creationFail = getFailedResult(pluginFile)
    assertThat(creationFail.errorsAndWarnings, `is`(expectedProblems))
  }

  private fun getSuccessResult(pluginFile: File): PluginCreationSuccess {
    val pluginCreationResult = PluginManager.getInstance().createPlugin(pluginFile)
    assertThat(pluginCreationResult, instanceOf(PluginCreationSuccess::class.java))
    return pluginCreationResult as PluginCreationSuccess
  }

  private fun getFailedResult(pluginFile: File): PluginCreationFail {
    val pluginCreationResult = PluginManager.getInstance().createPlugin(pluginFile)
    assertThat(pluginCreationResult, instanceOf(PluginCreationFail::class.java))
    return pluginCreationResult as PluginCreationFail
  }

  @Test
  fun `plugin description is empty`() {
    `test invalid plugin xml`(
        perfectXmlBuilder.modify {
          description = "<description></description>"
        },
        listOf(PropertyNotSpecified("plugin.xml", "description")))
  }

  @Test
  fun `plugin name is not specified`() {
    `test invalid plugin xml`(
        perfectXmlBuilder.modify {
          name = ""
        },
        listOf(PropertyNotSpecified("plugin.xml", "name")))
  }

  @Test
  fun `plugin id is not specified but it is equal to name`() {
    val pluginXmlContent = perfectXmlBuilder.modify {
      id = ""
    }
    val pluginFolder = getTempPluginFolder(pluginXmlContent)
    val successResult = getSuccessResult(pluginFolder)
    val plugin = successResult.plugin
    assertThat(plugin.pluginId, `is`(plugin.pluginName))
  }

  @Test
  fun `plugin vendor is not specified`() {
    `test invalid plugin xml`(
        perfectXmlBuilder.modify {
          vendor = ""
        },
        listOf(PropertyNotSpecified("plugin.xml", "vendor")))
  }

  @Test
  fun `plugin version is not specified`() {
    `test invalid plugin xml`(
        perfectXmlBuilder.modify {
          version = ""
        }
        , listOf(PropertyNotSpecified("plugin.xml", "version")))
  }

  @Test
  fun `idea version is not specified`() {
    `test invalid plugin xml`(
        perfectXmlBuilder.modify {
          ideaVersion = ""
        },
        listOf(PropertyNotSpecified("plugin.xml", "idea-version")))
  }

  @Test
  fun `invalid dependency bean`() {
    `test invalid plugin xml`(
        perfectXmlBuilder.modify {
          depends = listOf("")
        },
        listOf(InvalidDependencyBean("plugin.xml")))
  }

  @Test
  fun `invalid module bean`() {
    `test invalid plugin xml`(
        perfectXmlBuilder.modify {
          modules = listOf("")
        },
        listOf(InvalidModuleBean("plugin.xml")))
  }

  @Test
  fun `missing since build`() {
    `test invalid plugin xml`(
        perfectXmlBuilder.modify {
          ideaVersion = "<idea-version/>"
        },
        listOf(SinceBuildNotSpecified("plugin.xml")))
  }

  @Test
  fun `invalid since build`() {
    `test invalid plugin xml`(
        perfectXmlBuilder.modify {
          ideaVersion = """<idea-version since-build="131."/>"""
        },
        listOf(InvalidSinceBuild("plugin.xml", "131.")))
  }

  @Test
  fun `invalid until build`() {
    `test invalid plugin xml`(
        perfectXmlBuilder.modify {
          ideaVersion = """<idea-version since-build="131.1" until-build="141."/>"""
        },
        listOf(InvalidUntilBuild("plugin.xml", "141.")))
  }

  @Test
  fun `empty vendor`() {
    `test invalid plugin xml`(
        perfectXmlBuilder.modify {
          vendor = """<vendor></vendor>"""
        },
        listOf(PropertyNotSpecified("plugin.xml", "vendor")))
  }

  @Test
  fun `non latin description`() {
    `test plugin xml warnings`(
        perfectXmlBuilder.modify {
          description = "<description>Описание без английского, но достаточно длинное</description>"
        },
        listOf(NonLatinDescription("plugin.xml")))
  }

  @Test
  fun `default values`() {
    `test invalid plugin xml`("""<idea-plugin>
      <id>com.your.company.unique.plugin.id</id>
      <name>Plugin display name here</name>
      <version>1.0</version>
      <vendor email="support@yourcompany.com" url="http://www.yourcompany.com">YourCompany</vendor>
      <description><![CDATA[
        Enter short description for your plugin here.<br>
        <em>most HTML tags may be used</em>
      ]]></description>
      <change-notes><![CDATA[
          Add change notes here.<br>
          <em>most HTML tags may be used</em>
        ]]>
      </change-notes>
      <idea-version since-build="145.0"/>
      <extensions defaultExtensionNs="com.intellij">
      </extensions>
      <actions>
      </actions>
    </idea-plugin>
      """, listOf(
        PropertyWithDefaultValue("plugin.xml", "id"),
        PropertyWithDefaultValue("plugin.xml", "name"),
        DefaultDescription("plugin.xml"),
        DefaultChangeNotes("plugin.xml"),
        PropertyWithDefaultValue("plugin.xml", "vendor"),
        PropertyWithDefaultValue("plugin.xml", "vendor url"),
        PropertyWithDefaultValue("plugin.xml", "vendor email")
    ))
  }

  private fun `test plugin xml warnings`(pluginXmlContent: String, expectedWarnings: List<PluginProblem>) {
    val pluginFolder = getTempPluginFolder(pluginXmlContent)
    val successResult = getSuccessResult(pluginFolder)
    assertThat(successResult.warnings, `is`(expectedWarnings))
  }

  private fun `test invalid plugin xml`(pluginXmlContent: String, expectedProblems: List<PluginProblem>) {
    val pluginFolder = getTempPluginFolder(pluginXmlContent)
    assertExpectedProblems(pluginFolder, expectedProblems)
  }

  private fun getTempPluginFolder(pluginXmlContent: String): File {
    val pluginFolder = temporaryFolder.newFolder()
    val metaInf = File(pluginFolder, "META-INF")
    metaInf.mkdirs()
    File(metaInf, "plugin.xml").writeText(pluginXmlContent)
    return pluginFolder
  }

  @Test
  fun `plugin has multiple plugin descriptors in lib directory where descriptor might miss mandatory elements`() {
    /*
      plugin/
      plugin/lib
      plugin/lib/one.jar!/META-INF/plugin.xml
      plugin/lib/two.jar!/META-INF/plugin.xml
    */
    val validPluginXmlOne = perfectXmlBuilder.modify {
      id = """<id>one</id>"""
      name = "<name>one</name>"
      version = "<version>one</version>"
    }
    val invalidPluginXmlOne = perfectXmlBuilder.modify {
      version = ""
    }

    val firstDescriptors = listOf(validPluginXmlOne, invalidPluginXmlOne)

    val validPluginXmlTwo = perfectXmlBuilder.modify {
      id = """<id>two</id>"""
      name = """<name>two</name>"""
      version = """<version>two</version>"""
    }
    val invalidPluginXmlTwo = perfectXmlBuilder.modify {
      version = ""
    }
    val secondDescriptors = listOf(validPluginXmlTwo, invalidPluginXmlTwo)

    var testNumber = 0
    for (firstDescriptor in firstDescriptors) {
      for (secondDescriptor in secondDescriptors) {
        testNumber++
        val pluginFolder = temporaryFolder.newFolder(testNumber.toString())
        val lib = File(pluginFolder, "lib")
        lib.mkdirs()

        val oneMetaInf = temporaryFolder.newFolder("one$testNumber", "META-INF")
        File(oneMetaInf, "plugin.xml").writeText(firstDescriptor)
        ZipUtil.archiveDirectory(oneMetaInf, File(lib, "one.jar"))

        val twoMetaInf = temporaryFolder.newFolder("two$testNumber", "META-INF")
        File(twoMetaInf, "plugin.xml").writeText(secondDescriptor)
        ZipUtil.archiveDirectory(twoMetaInf, File(lib, "two.jar"))

        assertExpectedProblems(pluginFolder, listOf(MultiplePluginDescriptorsInLibDirectory("one.jar", "two.jar")))
      }
    }
  }

}