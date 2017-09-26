/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.jvm.java;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.common.ResourceValidator;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import javax.annotation.Nullable;
import org.immutables.value.Value;

@Value.Immutable
@Value.Style(
  overshadowImplementation = true,
  init = "set*",
  visibility = Value.Style.ImplementationVisibility.PACKAGE
)
public abstract class DefaultJavaLibraryRules {
  public interface DefaultJavaLibraryConstructor {
    DefaultJavaLibrary newInstance(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        ImmutableSortedSet<BuildRule> buildDeps,
        SourcePathResolver resolver,
        JarBuildStepsFactory jarBuildStepsFactory,
        Optional<SourcePath> proguardConfig,
        SortedSet<BuildRule> fullJarDeclaredDeps,
        ImmutableSortedSet<BuildRule> fullJarExportedDeps,
        ImmutableSortedSet<BuildRule> fullJarProvidedDeps,
        @Nullable BuildTarget abiJar,
        Optional<String> mavenCoords,
        ImmutableSortedSet<BuildTarget> tests,
        boolean requiredForSourceAbi);
  }

  @org.immutables.builder.Builder.Parameter
  abstract BuildTarget getInitialBuildTarget();

  @Value.Lazy
  BuildTarget getLibraryTarget() {
    BuildTarget initialBuildTarget = getInitialBuildTarget();
    return HasJavaAbi.isLibraryTarget(initialBuildTarget)
        ? initialBuildTarget
        : HasJavaAbi.getLibraryTarget(initialBuildTarget);
  }

  @org.immutables.builder.Builder.Parameter
  abstract ProjectFilesystem getProjectFilesystem();

  @org.immutables.builder.Builder.Parameter
  abstract BuildRuleParams getInitialParams();

  @org.immutables.builder.Builder.Parameter
  abstract BuildRuleResolver getBuildRuleResolver();

  @Value.Lazy
  SourcePathRuleFinder getSourcePathRuleFinder() {
    return new SourcePathRuleFinder(getBuildRuleResolver());
  }

  @Value.Lazy
  SourcePathResolver getSourcePathResolver() {
    return DefaultSourcePathResolver.from(getSourcePathRuleFinder());
  }

  @org.immutables.builder.Builder.Parameter
  abstract ConfiguredCompilerFactory getConfiguredCompilerFactory();

  @org.immutables.builder.Builder.Parameter
  @Nullable
  abstract JavaBuckConfig getJavaBuckConfig();

  @Value.Default
  DefaultJavaLibraryConstructor getConstructor() {
    return DefaultJavaLibrary::new;
  }

  @Value.NaturalOrder
  abstract ImmutableSortedSet<SourcePath> getSrcs();

  @Value.NaturalOrder
  abstract ImmutableSortedSet<SourcePath> getResources();

  @Value.Check
  void validateResources() {
    ResourceValidator.validateResources(
        getSourcePathResolver(), getProjectFilesystem(), getResources());
  }

  abstract Optional<SourcePath> getProguardConfig();

  abstract ImmutableList<String> getPostprocessClassesCommands();

  abstract Optional<Path> getResourcesRoot();

  abstract Optional<SourcePath> getManifestFile();

  abstract Optional<String> getMavenCoords();

  @Value.NaturalOrder
  abstract ImmutableSortedSet<BuildTarget> getTests();

  @Value.Default
  RemoveClassesPatternsMatcher getClassesToRemoveFromJar() {
    return RemoveClassesPatternsMatcher.EMPTY;
  }

  @Value.Default
  boolean getSourceAbisAllowed() {
    return true;
  }

  abstract JavacOptions getJavacOptions();

  @Nullable
  abstract JavaLibraryDeps getDeps();

  @org.immutables.builder.Builder.Parameter
  @Nullable
  abstract JavaLibraryDescription.CoreArg getArgs();

  @Nullable private DefaultJavaLibrary libraryRule;
  @Nullable private CalculateAbiFromSource sourceAbiRule;
  @Nullable private ImmutableSortedSet<BuildRule> finalBuildDeps;
  @Nullable private ImmutableSortedSet<BuildRule> finalFullJarDeclaredDeps;
  @Nullable private ImmutableSortedSet<BuildRule> compileTimeClasspathUnfilteredFullDeps;
  @Nullable private ImmutableSortedSet<BuildRule> compileTimeClasspathFullDeps;
  @Nullable private ImmutableSortedSet<BuildRule> compileTimeClasspathAbiDeps;
  @Nullable private ZipArchiveDependencySupplier abiClasspath;
  @Nullable private JarBuildStepsFactory jarBuildStepsFactory;
  @Nullable private BuildTarget abiJar;
  @Nullable private ConfiguredCompiler configuredCompiler;

  public DefaultJavaLibrary buildLibrary() {
    return getLibraryRule(false);
  }

  public BuildRule buildAbi() {
    if (HasJavaAbi.isClassAbiTarget(getInitialBuildTarget())) {
      return buildAbiFromClasses();
    } else if (HasJavaAbi.isSourceAbiTarget(getInitialBuildTarget())) {
      CalculateAbiFromSource abiRule = getSourceAbiRule(false);
      getLibraryRule(true);
      return abiRule;
    } else if (HasJavaAbi.isVerifiedSourceAbiTarget(getInitialBuildTarget())) {
      BuildRule classAbi =
          getBuildRuleResolver().requireRule(HasJavaAbi.getClassAbiJar(getLibraryTarget()));
      BuildRule sourceAbi =
          getBuildRuleResolver().requireRule(HasJavaAbi.getSourceAbiJar(getLibraryTarget()));

      return new CompareAbis(
          getInitialBuildTarget(),
          getProjectFilesystem(),
          getInitialParams()
              .withDeclaredDeps(ImmutableSortedSet.of(classAbi, sourceAbi))
              .withoutExtraDeps(),
          getSourcePathResolver(),
          classAbi.getSourcePathToOutput(),
          sourceAbi.getSourcePathToOutput(),
          getJavaBuckConfig().getSourceAbiVerificationMode());
    }

    throw new AssertionError(
        String.format(
            "%s is not an ABI target but went down the ABI codepath", getInitialBuildTarget()));
  }

  @Nullable
  private BuildTarget getAbiJar() {
    if (!willProduceOutputJar()) {
      return null;
    }

    if (abiJar == null) {
      if (shouldBuildAbiFromSource()) {
        JavaBuckConfig.SourceAbiVerificationMode sourceAbiVerificationMode =
            getJavaBuckConfig().getSourceAbiVerificationMode();
        abiJar =
            sourceAbiVerificationMode == JavaBuckConfig.SourceAbiVerificationMode.OFF
                ? HasJavaAbi.getSourceAbiJar(getLibraryTarget())
                : HasJavaAbi.getVerifiedSourceAbiJar(getLibraryTarget());
      } else {
        abiJar = HasJavaAbi.getClassAbiJar(getLibraryTarget());
      }
    }

    return abiJar;
  }

  private boolean willProduceOutputJar() {
    return !getSrcs().isEmpty() || !getResources().isEmpty() || getManifestFile().isPresent();
  }

  private boolean willProduceSourceAbi() {
    return willProduceOutputJar() && shouldBuildAbiFromSource();
  }

  private boolean shouldBuildAbiFromSource() {
    return isCompilingJava()
        && !getSrcs().isEmpty()
        && sourceAbisEnabled()
        && getSourceAbisAllowed()
        && getPostprocessClassesCommands().isEmpty();
  }

  private boolean isCompilingJava() {
    return getConfiguredCompiler() instanceof JavacToJarStepFactory;
  }

  private boolean sourceAbisEnabled() {
    return getJavaBuckConfig() != null && getJavaBuckConfig().shouldGenerateAbisFromSource();
  }

  private DefaultJavaLibrary getLibraryRule(boolean addToIndex) {
    if (libraryRule == null) {
      ImmutableSortedSet.Builder<BuildRule> buildDepsBuilder = ImmutableSortedSet.naturalOrder();

      buildDepsBuilder.addAll(getFinalBuildDeps());
      CalculateAbiFromSource sourceAbiRule = null;
      if (willProduceSourceAbi()) {
        sourceAbiRule = getSourceAbiRule(true);
        buildDepsBuilder.add(sourceAbiRule);
      }

      libraryRule =
          getConstructor()
              .newInstance(
                  getInitialBuildTarget(),
                  getProjectFilesystem(),
                  buildDepsBuilder.build(),
                  getSourcePathResolver(),
                  getJarBuildStepsFactory(),
                  getProguardConfig(),
                  getFinalFullJarDeclaredDeps(),
                  Preconditions.checkNotNull(getDeps()).getExportedDeps(),
                  Preconditions.checkNotNull(getDeps()).getProvidedDeps(),
                  getAbiJar(),
                  getMavenCoords(),
                  getTests(),
                  getRequiredForSourceAbi());

      if (sourceAbiRule != null) {
        libraryRule.setSourceAbi(sourceAbiRule);
      }

      if (addToIndex) {
        getBuildRuleResolver().addToIndex(libraryRule);
      }
    }

    return libraryRule;
  }

  private boolean getRequiredForSourceAbi() {
    return getArgs() != null && getArgs().getRequiredForSourceAbi();
  }

  private CalculateAbiFromSource getSourceAbiRule(boolean addToIndex) {
    BuildTarget abiTarget = HasJavaAbi.getSourceAbiJar(getLibraryTarget());
    if (sourceAbiRule == null) {
      sourceAbiRule =
          new CalculateAbiFromSource(
              abiTarget,
              getProjectFilesystem(),
              getFinalBuildDeps(),
              getSourcePathRuleFinder(),
              getJarBuildStepsFactory());
      if (addToIndex) {
        getBuildRuleResolver().addToIndex(sourceAbiRule);
      }
    }
    return sourceAbiRule;
  }

  private BuildRule buildAbiFromClasses() {
    BuildTarget abiTarget = HasJavaAbi.getClassAbiJar(getLibraryTarget());
    BuildRule libraryRule = getBuildRuleResolver().requireRule(getLibraryTarget());

    return CalculateAbiFromClasses.of(
        abiTarget,
        getSourcePathRuleFinder(),
        getProjectFilesystem(),
        getInitialParams(),
        Preconditions.checkNotNull(libraryRule.getSourcePathToOutput()),
        getJavaBuckConfig() != null
            && getJavaBuckConfig().getSourceAbiVerificationMode()
                != JavaBuckConfig.SourceAbiVerificationMode.OFF);
  }

  private ImmutableSortedSet<BuildRule> getFinalFullJarDeclaredDeps() {
    if (finalFullJarDeclaredDeps == null) {
      finalFullJarDeclaredDeps =
          ImmutableSortedSet.copyOf(
              Iterables.concat(
                  Preconditions.checkNotNull(getDeps()).getDeps(),
                  getConfiguredCompiler().getDeclaredDeps(getSourcePathRuleFinder())));
    }

    return finalFullJarDeclaredDeps;
  }

  private ImmutableSortedSet<SourcePath> getFinalCompileTimeClasspathSourcePaths() {
    ImmutableSortedSet<BuildRule> buildRules =
        getConfiguredCompilerFactory().compileAgainstAbis()
            ? getCompileTimeClasspathAbiDeps()
            : getCompileTimeClasspathFullDeps();

    return buildRules
        .stream()
        .map(BuildRule::getSourcePathToOutput)
        .filter(Objects::nonNull)
        .collect(MoreCollectors.toImmutableSortedSet());
  }

  private ImmutableSortedSet<BuildRule> getCompileTimeClasspathFullDeps() {
    if (compileTimeClasspathFullDeps == null) {
      compileTimeClasspathFullDeps =
          getCompileTimeClasspathUnfilteredFullDeps()
              .stream()
              .filter(dep -> dep instanceof HasJavaAbi)
              .collect(MoreCollectors.toImmutableSortedSet());
    }

    return compileTimeClasspathFullDeps;
  }

  private ImmutableSortedSet<BuildRule> getCompileTimeClasspathAbiDeps() {
    if (compileTimeClasspathAbiDeps == null) {
      compileTimeClasspathAbiDeps =
          JavaLibraryRules.getAbiRules(getBuildRuleResolver(), getCompileTimeClasspathFullDeps());
    }

    return compileTimeClasspathAbiDeps;
  }

  private ZipArchiveDependencySupplier getAbiClasspath() {
    if (abiClasspath == null) {
      abiClasspath =
          new ZipArchiveDependencySupplier(
              getSourcePathRuleFinder(),
              getCompileTimeClasspathAbiDeps()
                  .stream()
                  .map(BuildRule::getSourcePathToOutput)
                  .collect(MoreCollectors.toImmutableSortedSet()));
    }

    return abiClasspath;
  }

  private ConfiguredCompiler getConfiguredCompiler() {
    if (configuredCompiler == null) {
      configuredCompiler =
          getConfiguredCompilerFactory()
              .configure(getArgs(), getJavacOptions(), getBuildRuleResolver());
    }

    return configuredCompiler;
  }

  private ImmutableSortedSet<BuildRule> getFinalBuildDeps() {
    if (finalBuildDeps == null) {
      ImmutableSortedSet.Builder<BuildRule> depsBuilder = ImmutableSortedSet.naturalOrder();

      depsBuilder
          // We always need the non-classpath deps, whether directly specified or specified via
          // query
          .addAll(
              Sets.difference(getInitialParams().getBuildDeps(), getCompileTimeClasspathFullDeps()))
          .addAll(
              Sets.difference(
                  getCompileTimeClasspathUnfilteredFullDeps(), getCompileTimeClasspathFullDeps()))
          // It's up to the compiler to use an ABI jar for these deps if appropriate, so we can
          // add them unconditionally
          .addAll(getConfiguredCompiler().getBuildDeps(getSourcePathRuleFinder()))
          // We always need the ABI deps (at least for rulekey computation)
          // TODO(jkeljo): It's actually incorrect to use ABIs for rulekey computation for languages
          // that can't compile against them. Generally the reason they can't compile against ABIs
          // is that the ABI generation for that language isn't fully correct.
          .addAll(getCompileTimeClasspathAbiDeps());

      if (!getConfiguredCompilerFactory().compileAgainstAbis()) {
        depsBuilder.addAll(getCompileTimeClasspathFullDeps());
      }

      finalBuildDeps = depsBuilder.build();
    }

    return finalBuildDeps;
  }

  private ImmutableSortedSet<BuildRule> getCompileTimeClasspathUnfilteredFullDeps() {
    if (compileTimeClasspathUnfilteredFullDeps == null) {
      Iterable<BuildRule> firstOrderDeps =
          Iterables.concat(
              getFinalFullJarDeclaredDeps(),
              Preconditions.checkNotNull(getDeps()).getProvidedDeps());

      ImmutableSortedSet<BuildRule> rulesExportedByDependencies =
          BuildRules.getExportedRules(firstOrderDeps);

      compileTimeClasspathUnfilteredFullDeps =
          RichStream.from(Iterables.concat(firstOrderDeps, rulesExportedByDependencies))
              .collect(MoreCollectors.toImmutableSortedSet());
    }

    return compileTimeClasspathUnfilteredFullDeps;
  }

  private JarBuildStepsFactory getJarBuildStepsFactory() {
    if (jarBuildStepsFactory == null) {
      jarBuildStepsFactory =
          new JarBuildStepsFactory(
              getProjectFilesystem(),
              getSourcePathRuleFinder(),
              getConfiguredCompiler(),
              getSrcs(),
              getResources(),
              getResourcesRoot(),
              getManifestFile(),
              getPostprocessClassesCommands(),
              getAbiClasspath(),
              getConfiguredCompilerFactory().trackClassUsage(getJavacOptions()),
              getFinalCompileTimeClasspathSourcePaths(),
              getClassesToRemoveFromJar(),
              getRequiredForSourceAbi());
    }
    return jarBuildStepsFactory;
  }

  @org.immutables.builder.Builder.AccessibleFields
  public static class Builder extends ImmutableDefaultJavaLibraryRules.Builder {
    public Builder(
        BuildTarget initialBuildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams initialParams,
        BuildRuleResolver buildRuleResolver,
        ConfiguredCompilerFactory configuredCompilerFactory,
        @Nullable JavaBuckConfig javaBuckConfig,
        @Nullable JavaLibraryDescription.CoreArg args) {
      super(
          initialBuildTarget,
          projectFilesystem,
          initialParams,
          buildRuleResolver,
          configuredCompilerFactory,
          javaBuckConfig,
          args);

      this.buildRuleResolver = buildRuleResolver;

      if (args != null) {
        if (args.getGenerateAbiFromSource().isPresent()) {
          setSourceAbisAllowed(args.getGenerateAbiFromSource().get());
        }

        setSrcs(args.getSrcs())
            .setResources(args.getResources())
            .setResourcesRoot(args.getResourcesRoot())
            .setProguardConfig(args.getProguardConfig())
            .setPostprocessClassesCommands(args.getPostprocessClassesCommands())
            .setDeps(JavaLibraryDeps.newInstance(args, buildRuleResolver))
            .setTests(args.getTests())
            .setManifestFile(args.getManifestFile())
            .setMavenCoords(args.getMavenCoords())
            .setClassesToRemoveFromJar(new RemoveClassesPatternsMatcher(args.getRemoveClasses()));
      }
    }

    Builder() {
      throw new UnsupportedOperationException();
    }

    @Nullable
    public JavaLibraryDeps getDeps() {
      return super.deps;
    }
  }
}