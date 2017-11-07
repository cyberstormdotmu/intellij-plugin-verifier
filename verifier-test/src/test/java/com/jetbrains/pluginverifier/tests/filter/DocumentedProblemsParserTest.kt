package com.jetbrains.pluginverifier.tests.filter

import com.jetbrains.pluginverifier.parameters.filtering.documented.*
import com.jetbrains.pluginverifier.parameters.filtering.documented.DocumentedProblemsParser.Companion.unwrapMarkdownTags
import org.junit.Assert.*
import org.junit.Test

/**
 * @author Sergey Patrikeev
 */
class DocumentedProblemsParserTest {

  private val parser: DocumentedProblemsParser = DocumentedProblemsParser()

  @Test
  fun `unwrap markdown tags test`() {
    assertEquals("nothing", unwrapMarkdownTags("nothing"))
    assertEquals("val x = 10", unwrapMarkdownTags("`val x = 10`"))
    assertEquals("com.example.deletedPackage", unwrapMarkdownTags("`com.example.deletedPackage`"))
    assertEquals("com.example.deletedPackage", unwrapMarkdownTags("`com.example.deletedPackage`"))
    assertEquals("com.example.Foo\$InnerClass.changedReturnType(int, String) method return type changed from One to Another", unwrapMarkdownTags("`com.example.Foo\$InnerClass.changedReturnType(int, String)` method return type changed from `One` to `Another`"))
  }

  @Test
  fun parseTest() {
    val clazz = DocumentedProblemsParserTest::class.java
    val text = clazz.getResourceAsStream("/exampleDocumentedProblems.md").bufferedReader().use { it.readText() }
    val documentedProblems: List<DocumentedProblem> = parser.parse(text)
    val expectedProblems = listOf(
        DocPackageRemoved("com/example/deletedPackage"),
        DocAbstractMethodAdded("com/example/Faz", "newAbstractMethod"),
        DocFieldRemoved("com/example/Baz", "REMOVED_FIELD"),
        DocClassRemoved("com/example/Foo"),
        DocMethodRemoved("com/example/Bar", "removedMethod"),
        DocClassMovedToPackage("com/example/Baf", "com/another"),
        DocMethodReturnTypeChanged("com/example/Foo\$InnerClass", "changedReturnType"),
        DocMethodParameterTypeChanged("com/example/Bar", "changedParameterType"),
        DocMethodVisibilityChanged("com/example/Bar", "methodVisibilityChanged"),
        DocFieldTypeChanged("com/example/Foo", "fieldTypeChanged"),
        DocFieldVisibilityChanged("com/example/Foo", "fieldVisibilityChanged")
    )
    for (expected in expectedProblems) {
      assertTrue("$expected is not found:\n${documentedProblems.joinToString("\n")}", expected in documentedProblems)
    }
    val redundant = documentedProblems - expectedProblems
    if (redundant.isNotEmpty()) {
      fail("Redundant:\n" + redundant.joinToString("\n"))
    }
  }
}