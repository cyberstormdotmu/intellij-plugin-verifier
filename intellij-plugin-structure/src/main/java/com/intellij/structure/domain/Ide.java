package com.intellij.structure.domain;

import com.intellij.structure.resolvers.Resolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * @author Sergey Patrikeev
 */
public interface Ide {
  @NotNull
  IdeVersion getVersion();

  void addCustomPlugin(@NotNull Plugin plugin);

  @NotNull
  List<Plugin> getCustomPlugins();

  @NotNull
  List<Plugin> getBundledPlugins();

  @Nullable
  Plugin getPluginById(@NotNull String pluginId);

  @Nullable
  Plugin getPluginByModule(@NotNull String moduleId);

  @NotNull
  Resolver getClassPool();
}
