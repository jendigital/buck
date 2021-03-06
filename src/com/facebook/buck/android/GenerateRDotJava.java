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

package com.facebook.buck.android;

import com.facebook.buck.android.aapt.RDotTxtEntry;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.SortedSet;
import javax.annotation.Nullable;

public class GenerateRDotJava extends AbstractBuildRule {
  @AddToRuleKey private final EnumSet<RDotTxtEntry.RType> bannedDuplicateResourceTypes;
  @AddToRuleKey private final SourcePath pathToRDotTxtFile;
  @AddToRuleKey private final Optional<SourcePath> pathToOverrideSymbolsFile;
  @AddToRuleKey private Optional<String> resourceUnionPackage;

  private final ImmutableList<HasAndroidResourceDeps> resourceDeps;
  private FilteredResourcesProvider resourcesProvider;
  // TODO(cjhopman): allResourceDeps is used for getBuildDeps(), can that just use resourceDeps?
  private final ImmutableSortedSet<BuildRule> allResourceDeps;
  private final SourcePathRuleFinder ruleFinder;

  GenerateRDotJava(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      EnumSet<RDotTxtEntry.RType> bannedDuplicateResourceTypes,
      SourcePath pathToRDotTxtFile,
      Optional<String> resourceUnionPackage,
      ImmutableSortedSet<BuildRule> resourceDeps,
      FilteredResourcesProvider resourcesProvider) {
    super(buildTarget, projectFilesystem);
    this.ruleFinder = ruleFinder;
    this.bannedDuplicateResourceTypes = bannedDuplicateResourceTypes;
    this.pathToRDotTxtFile = pathToRDotTxtFile;
    this.resourceUnionPackage = resourceUnionPackage;
    this.allResourceDeps = resourceDeps;
    this.resourceDeps =
        resourceDeps
            .stream()
            .map(HasAndroidResourceDeps.class::cast)
            .collect(MoreCollectors.toImmutableList());
    this.resourcesProvider = resourcesProvider;
    this.pathToOverrideSymbolsFile = resourcesProvider.getOverrideSymbolsPath();
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.naturalOrder();
    builder
        .addAll(
            ruleFinder.filterBuildRuleInputs(
                pathToRDotTxtFile, resourcesProvider.getOverrideSymbolsPath().orElse(null)))
        .addAll(allResourceDeps);
    resourcesProvider.getResourceFilterRule().ifPresent(builder::add);
    return builder.build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    SourcePathResolver pathResolver = buildContext.getSourcePathResolver();

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // Merge R.txt of HasAndroidRes and generate the resulting R.java files per package.
    Path rDotJavaSrc = getPathToGeneratedRDotJavaSrcFiles();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), getProjectFilesystem(), rDotJavaSrc)));

    Path rDotTxtPath = pathResolver.getAbsolutePath(pathToRDotTxtFile);
    MergeAndroidResourcesStep mergeStep =
        MergeAndroidResourcesStep.createStepForUberRDotJava(
            getProjectFilesystem(),
            buildContext.getSourcePathResolver(),
            resourceDeps,
            rDotTxtPath,
            rDotJavaSrc,
            bannedDuplicateResourceTypes,
            pathToOverrideSymbolsFile.map(pathResolver::getAbsolutePath),
            resourceUnionPackage);
    steps.add(mergeStep);

    // Ensure the generated R.txt and R.java files are also recorded.
    buildableContext.recordArtifact(rDotJavaSrc);

    return steps.build();
  }

  private Path getPathToGeneratedRDotJavaSrcFiles() {
    return getPathToGeneratedRDotJavaSrcFiles(getBuildTarget(), getProjectFilesystem());
  }

  @VisibleForTesting
  static Path getPathToGeneratedRDotJavaSrcFiles(
      BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return BuildTargets.getScratchPath(filesystem, buildTarget, "__%s_rdotjava_src__");
  }

  public SourcePath getSourcePathToGeneratedRDotJavaSrcFiles() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPathToGeneratedRDotJavaSrcFiles());
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }
}
