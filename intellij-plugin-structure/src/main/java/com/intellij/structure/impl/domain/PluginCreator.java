package com.intellij.structure.impl.domain;

import com.intellij.structure.ide.IdeVersion;
import com.intellij.structure.impl.beans.*;
import com.intellij.structure.impl.extractor.ExtractedPluginFile;
import com.intellij.structure.impl.resolvers.PluginResolver;
import com.intellij.structure.impl.utils.FileUtil;
import com.intellij.structure.impl.utils.StringUtil;
import com.intellij.structure.impl.utils.xml.JDOMXIncluder;
import com.intellij.structure.plugin.Plugin;
import com.intellij.structure.plugin.PluginCreationResult;
import com.intellij.structure.plugin.PluginCreationSuccess;
import com.intellij.structure.plugin.PluginDependency;
import com.intellij.structure.problems.*;
import com.intellij.structure.resolvers.Resolver;
import org.jdom2.Document;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.ValidationEvent;
import javax.xml.bind.ValidationEventHandler;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.intellij.structure.impl.utils.StringUtil.isEmpty;
import static com.intellij.structure.impl.utils.StringUtil.isEmptyOrSpaces;

/**
 * @author Sergey Patrikeev
 */
final class PluginCreator {

  private static final Logger LOG = LoggerFactory.getLogger(PluginCreator.class);
  private final PluginImpl myPlugin;
  private final List<PluginProblem> myProblems = new ArrayList<PluginProblem>();
  private final String myDescriptorPath;
  private final boolean myValidateDescriptor;
  private final File myActualFile;
  private Resolver myResolver;

  PluginCreator(String descriptorPath,
                boolean validateDescriptor,
                Document document,
                URL documentUrl,
                JDOMXIncluder.PathResolver pathResolver,
                File actualFile) {
    myDescriptorPath = descriptorPath;
    myValidateDescriptor = validateDescriptor;
    myActualFile = actualFile;
    myPlugin = resolveDocumentAndValidateBean(document, documentUrl, pathResolver);
  }

  PluginCreator(String descriptorPath, PluginProblem singleProblem, File actualFile) {
    if (singleProblem.getLevel() != PluginProblem.Level.ERROR) {
      throw new IllegalArgumentException("Only severe problems allowed here");
    }
    myActualFile = actualFile;
    myDescriptorPath = descriptorPath;
    myValidateDescriptor = true;
    myProblems.add(singleProblem);
    myPlugin = null;
  }

  private void validatePluginBean(PluginBean bean) {
    if (myValidateDescriptor) {
      validateId(bean.id);
      validateName(bean.name);
      validateVersion(bean.pluginVersion);
      validateDescription(bean.description);
      validateChangeNotes(bean.changeNotes);
      validateVendor(bean.vendor);
      validateIdeaVersion(bean.ideaVersion);

      if (bean.dependencies != null) {
        for (PluginDependencyBean dependencyBean : bean.dependencies) {
          if (isEmpty(dependencyBean.pluginId)) {
            registerProblem(new InvalidDependencyBean(myDescriptorPath));
          }
        }
      }

      if (bean.modules != null) {
        for (String module : bean.modules) {
          if (isEmpty(module)) {
            registerProblem(new InvalidModuleBean(myDescriptorPath));
          }
        }
      }
    }
  }

  private void validateVersion(String pluginVersion) {
    if (isEmpty(pluginVersion)) {
      registerProblem(new PropertyNotSpecified(myDescriptorPath, "version"));
    }
  }

  private void validatePlugin(Plugin plugin) {
    if (plugin.getModuleDependencies().isEmpty()) {
      registerProblem(new NoModuleDependencies(myDescriptorPath));
    }
  }

  public Map<PluginDependency, String> getOptionalDependenciesConfigurationFiles() {
    return myPlugin.getOptionalDependenciesConfigFiles();
  }

  public void addOptionalDescriptor(PluginDependency pluginDependency, String configurationFile, PluginCreator optionalCreator) {
    PluginCreationResult pluginCreationResult = optionalCreator.getPluginCreationResult();
    if (pluginCreationResult instanceof PluginCreationSuccess) {
      myPlugin.addOptionalDescriptor(configurationFile, ((PluginCreationSuccess) pluginCreationResult).getPlugin());
    } else {
      registerProblem(new MissingOptionalDependencyConfigurationFile(myDescriptorPath, pluginDependency, configurationFile));
    }
  }

  @Nullable
  private PluginImpl resolveDocumentAndValidateBean(@NotNull Document originalDocument,
                                                    @NotNull URL documentUrl,
                                                    @NotNull JDOMXIncluder.PathResolver pathResolver) {
    Document document = resolveXIncludesOfDocument(originalDocument, documentUrl, pathResolver);
    if (document == null) {
      return null;
    }
    PluginBean bean = readDocumentIntoXmlBean(document);
    if (bean == null) {
      return null;
    }
    validatePluginBean(bean);
    if (hasErrors()) {
      return null;
    }
    PluginImpl plugin = new PluginImpl(document, bean);
    validatePlugin(plugin);
    if (hasErrors()) {
      return null;
    }
    return plugin;
  }

  @Nullable
  private Document resolveXIncludesOfDocument(@NotNull Document originalDocument, @NotNull URL documentUrl, @NotNull JDOMXIncluder.PathResolver pathResolver) {
    try {
      return PluginXmlExtractor.resolveXIncludes(originalDocument, documentUrl, pathResolver);
    } catch (Exception e) {
      LOG.error("Unable to resolve x-include elements " + myDescriptorPath, e);
      registerProblem(new UnableToResolveXIncludeElements(myDescriptorPath));
      return null;
    }
  }

  @Nullable
  private PluginBean readDocumentIntoXmlBean(@NotNull Document document) {
    try {
      return PluginBeanExtractor.extractPluginBean(document, new ReportingValidationEventHandler());
    } catch (Exception e) {
      LOG.error("Unable to read plugin descriptor " + myDescriptorPath, e);
      registerProblem(new UnableToReadDescriptor(myDescriptorPath));
      return null;
    }
  }

  public File getActualFile() {
    return myActualFile;
  }

  public void registerProblem(@NotNull PluginProblem problem) {
    myProblems.add(problem);
  }

  public boolean isSuccess() {
    return !hasErrors();
  }

  public boolean hasOnlyInvalidDescriptorErrors() {
    for (PluginProblem problem : getProblems()) {
      if (problem.getLevel() == PluginProblem.Level.ERROR && !(problem instanceof InvalidDescriptorProblem)) {
        return false;
      }
    }
    return true;
  }

  public boolean hasErrors() {
    for (PluginProblem problem : getProblems()) {
      if (problem.getLevel() == PluginProblem.Level.ERROR) return true;
    }
    return false;
  }

  @NotNull
  public PluginCreationResult getPluginCreationResult() {
    if (hasErrors()) {
      return new PluginCreationFailImpl(myProblems);
    }
    return new PluginCreationSuccessImpl(myPlugin, myProblems, myResolver);
  }

  @NotNull
  public List<PluginProblem> getProblems() {
    return myProblems;
  }

  public void setOriginalFile(File originalFile) {
    if (myPlugin != null) {
      myPlugin.setOriginalPluginFile(originalFile);
    }
  }

  public void readClassFiles(@NotNull ExtractedPluginFile extractedPluginFile) {
    if (myPlugin != null) {
      try {
        myResolver = new PluginResolver(extractedPluginFile);
      } catch (Exception e) {
        LOG.error("Unable to read plugin class files " + extractedPluginFile.getActualPluginFile(), e);
        registerProblem(new UnableToReadPluginClassFiles(extractedPluginFile.getActualPluginFile()));
      }
    } else {
      FileUtil.closeQuietly(extractedPluginFile);
    }
  }

  private void validateId(@Nullable String id) {
    if ("com.your.company.unique.plugin.id".equals(id)) {
      registerProblem(new PropertyWithDefaultValue(myDescriptorPath, "id"));
    }
  }

  private void validateName(@Nullable String name) {
    if (isEmpty(name)) {
      registerProblem(new PropertyNotSpecified(myDescriptorPath, "name"));
    } else if ("Plugin display name here".equals(name)) {
      registerProblem(new PropertyWithDefaultValue(myDescriptorPath, "name"));
    } else if ("plugin".contains(name)) {
      registerProblem(new PluginWordInPluginName(myDescriptorPath));
    }
  }

  private void validateDescription(@Nullable String description) {
    if (isEmpty(description)) {
      registerProblem(new PropertyNotSpecified(myDescriptorPath, "description"));
      return;
    }

    if (description.length() < 40) {
      registerProblem(new ShortDescription(myDescriptorPath));
      return;
    }

    if (description.contains("Enter short description for your plugin here.") ||
        description.contains("most HTML tags may be used")) {
      registerProblem(new DefaultDescription(myDescriptorPath));
      return;
    }

    int latinSymbols = StringUtil.numberOfPatternMatches(description, Pattern.compile("[A-Za-z]|\\s"));
    if (latinSymbols < 40) {
      registerProblem(new NonLatinDescription(myDescriptorPath));
    }
  }

  private void validateChangeNotes(String changeNotes) {
    if (isEmptyOrSpaces(changeNotes)) {
      //Too many plugins don't specify the change-notes, so it's too strict to require them.
      //But if specified, let's check that the change-notes are long enough.
      return;
    }

    if (changeNotes.length() < 40) {
      registerProblem(new ShortChangeNotes(myDescriptorPath));
      return;
    }

    if (changeNotes.contains("Add change notes here") ||
        changeNotes.contains("most HTML tags may be used")) {
      registerProblem(new DefaultChangeNotes(myDescriptorPath));
    }
  }

  private void validateVendor(PluginVendorBean vendorBean) {
    if (vendorBean == null || isEmptyOrSpaces(vendorBean.name)) {
      registerProblem(new PropertyNotSpecified(myDescriptorPath, "vendor"));
      return;
    }

    if ("YourCompany".equals(vendorBean.name)) {
      registerProblem(new PropertyWithDefaultValue(myDescriptorPath, "vendor"));
    }

    if ("http://www.yourcompany.com".equals(vendorBean.url)) {
      registerProblem(new PropertyWithDefaultValue(myDescriptorPath, "vendor url"));
    }

    if ("support@yourcompany.com".equals(vendorBean.email)) {
      registerProblem(new PropertyWithDefaultValue(myDescriptorPath, "vendor email"));
    }
  }

  private void validateIdeaVersion(IdeaVersionBean versionBean) {
    if (versionBean == null) {
      registerProblem(new PropertyNotSpecified(myDescriptorPath, "idea-version"));
      return;
    }

    String sinceBuild = versionBean.sinceBuild;
    if (sinceBuild == null) {
      registerProblem(new SinceBuildNotSpecified(myDescriptorPath));
    } else {
      if (!IdeVersion.isValidIdeVersion(sinceBuild)) {
        registerProblem(new InvalidSinceBuild(myDescriptorPath, sinceBuild));
      }
    }

    String untilBuild = versionBean.untilBuild;
    if (untilBuild != null && !IdeVersion.isValidIdeVersion(untilBuild)) {
      registerProblem(new InvalidUntilBuild(myDescriptorPath, untilBuild));
    }
  }

  private static class ReportingValidationEventHandler implements ValidationEventHandler {
    @Override
    public boolean handleEvent(ValidationEvent event) {
      return true;
      //todo: fix
      /*String reason = event.getMessage();
      if (reason.contains("unexpected element")) return true;
      registerProblem(new UnableToParseDescriptor(descriptorPath, reason));
      return true;*/
    }
  }
}