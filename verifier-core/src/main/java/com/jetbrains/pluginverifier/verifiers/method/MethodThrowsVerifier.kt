package com.jetbrains.pluginverifier.verifiers.method

import com.intellij.structure.resolvers.Resolver
import com.jetbrains.pluginverifier.api.VContext
import com.jetbrains.pluginverifier.utils.VerifierUtil
import org.jetbrains.intellij.plugins.internal.asm.tree.ClassNode
import org.jetbrains.intellij.plugins.internal.asm.tree.MethodNode

/**
 * @author Sergey Patrikeev
 */
class MethodThrowsVerifier : MethodVerifier {
  @Suppress("UNCHECKED_CAST")
  override fun verify(clazz: ClassNode, method: MethodNode, resolver: Resolver, ctx: VContext) {
    val exceptions = method.exceptions as List<String>
    for (exception in exceptions) {
      val descr = VerifierUtil.extractClassNameFromDescr(exception) ?: continue
      VerifierUtil.checkClassExistsOrExternal(resolver, descr, ctx, { VerifierUtil.fromMethod(clazz.name, method) })
    }
  }
}
