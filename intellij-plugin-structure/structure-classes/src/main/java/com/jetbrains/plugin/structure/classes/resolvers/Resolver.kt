package com.jetbrains.plugin.structure.classes.resolvers

import org.objectweb.asm.tree.ClassNode
import java.io.Closeable
import java.io.IOException

/**
 *
 * Provides an access to the byte-code of a class by its name via the [.findClass].
 * Note that the way of constructing the `Resolver` affects the searching order
 * (it is similar to the Java *class-path* option)
 *
 * Note: the class is `Closeable` thus the `Resolver` requires to be closed when it is no longer needed.
 * Some resolvers may extract the classes in the temporary directory for performance reasons, so [.close] will
 * clean the used disk space.
 */
abstract class Resolver : Closeable {

  /**
   * Read mode used to specify whether this resolver reads [ClassNode]s fully,
   * including methods' code, debug frames, or only classes' signatures.
   */
  enum class ReadMode {
    FULL, SIGNATURES
  }

  /**
   * Read mode this resolved is opened with.
   */
  abstract val readMode: ReadMode

  /**
   * Returns the *binary* names of all the contained classes.
   */
  abstract val allClasses: Set<String>

  /**
   * Returns binary names of all contained packages and their super-packages.
   *
   * For example, if this Resolver contains classes of a package `com/example/utils`
   * then [allPackages] contains `com`, `com/example` and `com/example/utils`.
   */
  abstract val allPackages: Set<String>

  /**
   * Checks whether this resolver contains any class. Classes can be obtained through [.getAllClasses].
   */
  abstract val isEmpty: Boolean

  /**
   * Resolves class with specified binary name.
   */
  abstract fun resolveClass(className: String): ResolutionResult

  /**
   * Returns true if `this` Resolver contains the given class. It may be faster
   * than checking [.findClass] is not null.
   */
  abstract fun containsClass(className: String): Boolean

  /**
   * Returns true if `this` Resolver contains the given package,
   * specified with binary name ('/'-separated). It may be faster
   * than fetching [allPackages] and checking for presence in it.
   */
  abstract fun containsPackage(packageName: String): Boolean

  /**
   * Runs the given [processor] on every class contained in _this_ [Resolver].
   * The [processor] returns `true` to continue processing and `false` to stop.
   */
  @Throws(IOException::class)
  abstract fun processAllClasses(processor: (ClassNode) -> Boolean): Boolean

}