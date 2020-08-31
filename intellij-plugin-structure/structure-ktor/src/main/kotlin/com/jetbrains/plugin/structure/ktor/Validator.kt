/*
 * Copyright 2000-2020 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package com.jetbrains.plugin.structure.ktor

import com.jetbrains.plugin.structure.base.plugin.PluginProblem
import com.jetbrains.plugin.structure.base.problems.PropertyNotSpecified
import com.jetbrains.plugin.structure.ktor.bean.*

internal fun validateKtorPluginBean(descriptor: KtorFeatureDescriptor): List<PluginProblem> {
  val problems = mutableListOf<PluginProblem>()
  val vendor = descriptor.vendor
  if (vendor == null || vendor.name.isNullOrBlank()) {
    problems.add(PropertyNotSpecified(VENDOR))
  }
  if (descriptor.pluginName.isNullOrBlank()){
    problems.add(PropertyNotSpecified(NAME))
  }
  if (descriptor.pluginId.isNullOrBlank()){
    problems.add(PropertyNotSpecified(ID))
  }
  if (descriptor.pluginVersion.isNullOrBlank()){
    problems.add(PropertyNotSpecified(VERSION))
  }
  if (descriptor.installSnippet.isNullOrBlank()){
    problems.add(PropertyNotSpecified(INSTALL_SNIPPET))
  }
  if (descriptor.dependency.isNullOrBlank()){
    problems.add(PropertyNotSpecified(DEPENDENCY))
  }
  // TODO add field validation
  return problems
}