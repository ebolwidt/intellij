/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.android.sync.importer;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.idea.blaze.android.sync.importer.aggregators.DependencyUtil;
import com.google.idea.blaze.android.sync.importer.problems.GeneratedResourceRetentionFilter;
import com.google.idea.blaze.android.sync.importer.problems.GeneratedResourceWarnings;
import com.google.idea.blaze.android.sync.model.AarLibrary;
import com.google.idea.blaze.android.sync.model.AndroidResourceModule;
import com.google.idea.blaze.android.sync.model.BlazeAndroidImportResult;
import com.google.idea.blaze.base.ideinfo.AndroidIdeInfo;
import com.google.idea.blaze.base.ideinfo.AndroidResFolder;
import com.google.idea.blaze.base.ideinfo.ArtifactLocation;
import com.google.idea.blaze.base.ideinfo.LibraryArtifact;
import com.google.idea.blaze.base.ideinfo.TargetIdeInfo;
import com.google.idea.blaze.base.ideinfo.TargetKey;
import com.google.idea.blaze.base.model.LibraryKey;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.scope.Output;
import com.google.idea.blaze.base.scope.output.IssueOutput;
import com.google.idea.blaze.base.scope.output.IssueOutput.Category;
import com.google.idea.blaze.base.scope.output.PerformanceWarning;
import com.google.idea.common.experiments.BoolExperiment;
import com.intellij.openapi.project.Project;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Builds a BlazeWorkspace. */
public class BlazeAndroidWorkspaceImporter {

  @VisibleForTesting
  static final BoolExperiment mergeResourcesEnabled =
      new BoolExperiment("blaze.merge.conflicting.resources", true);

  private final Project project;
  private final Consumer<Output> context;
  private final BlazeImportInput input;
  // filter used to get all ArtifactLocation that under project view resource directories
  private final Predicate<ArtifactLocation> shouldCreateFakeAar;
  ImmutableSet<String> whitelistedGenResourcePaths;
  private final WhitelistFilter whitelistFilter;

  public BlazeAndroidWorkspaceImporter(
      Project project, BlazeContext context, BlazeImportInput input) {

    this(project, BlazeImportUtil.asConsumer(context), input);
  }

  public BlazeAndroidWorkspaceImporter(
      Project project, Consumer<Output> context, BlazeImportInput input) {
    this.context = context;
    this.input = input;
    this.project = project;
    this.shouldCreateFakeAar = BlazeImportUtil.getShouldCreateFakeAarFilter(input);
    whitelistedGenResourcePaths =
        BlazeImportUtil.getWhitelistedGenResourcePaths(input.projectViewSet);
    whitelistFilter =
        new WhitelistFilter(
            whitelistedGenResourcePaths, GeneratedResourceRetentionFilter.getFilter());
  }

  public BlazeAndroidImportResult importWorkspace() {
    List<TargetIdeInfo> sourceTargets = BlazeImportUtil.getSourceTargets(input);
    LibraryFactory libraries = new LibraryFactory();
    ImmutableList.Builder<AndroidResourceModule> resourceModules = new ImmutableList.Builder<>();
    Map<TargetKey, AndroidResourceModule.Builder> targetKeyToAndroidResourceModuleBuilder =
        new HashMap<>();

    ImmutableSet<String> whitelistedGenResourcePaths =
        BlazeImportUtil.getWhitelistedGenResourcePaths(input.projectViewSet);
    for (TargetIdeInfo target : sourceTargets) {
      if (shouldCreateModule(target.getAndroidIdeInfo())) {
        AndroidResourceModule.Builder androidResourceModuleBuilder =
            getOrCreateResourceModuleBuilder(
                target, libraries, targetKeyToAndroidResourceModuleBuilder);
        resourceModules.add(androidResourceModuleBuilder.build());
      }
    }

    GeneratedResourceWarnings.submit(
        context::accept,
        project,
        input.projectViewSet,
        input.artifactLocationDecoder,
        whitelistFilter.testedAgainstWhitelist,
        whitelistedGenResourcePaths);

    ImmutableList<AndroidResourceModule> androidResourceModules =
        buildAndroidResourceModules(resourceModules.build());

    return new BlazeAndroidImportResult(
        androidResourceModules,
        libraries.getAarLibs(),
        BlazeImportUtil.getJavacJars(input.targetMap.targets()),
        BlazeImportUtil.getResourceJars(input.targetMap.targets()));
  }

  /**
   * Creates and populates an AndroidResourceModule.Builder for the given target by recursively
   * aggregating the AndroidResourceModule.Builders of its transitive dependencies, or reuses an
   * existing builder if it's cached in resourceModuleBuilderCache.
   */
  private AndroidResourceModule.Builder getOrCreateResourceModuleBuilder(
      TargetIdeInfo target,
      LibraryFactory libraryFactory,
      Map<TargetKey, AndroidResourceModule.Builder> resourceModuleBuilderCache) {
    TargetKey targetKey = target.getKey();
    if (resourceModuleBuilderCache.containsKey(targetKey)) {
      return resourceModuleBuilderCache.get(targetKey);
    }
    AndroidResourceModule.Builder targetResourceModule =
        createResourceModuleBuilder(target, libraryFactory);
    resourceModuleBuilderCache.put(targetKey, targetResourceModule);
    for (TargetKey dep : DependencyUtil.getResourceDependencies(target)) {
      TargetIdeInfo depIdeInfo = input.targetMap.get(dep);
      reduce(
          targetKey,
          targetResourceModule,
          dep,
          depIdeInfo,
          libraryFactory,
          resourceModuleBuilderCache);
    }
    return targetResourceModule;
  }

  protected void reduce(
      TargetKey targetKey,
      AndroidResourceModule.Builder targetResourceModule,
      TargetKey depKey,
      TargetIdeInfo depIdeInfo,
      LibraryFactory libraryFactory,
      Map<TargetKey, AndroidResourceModule.Builder> resourceModuleBuilderCache) {
    if (depIdeInfo != null) {
      AndroidResourceModule.Builder depTargetResourceModule =
          getOrCreateResourceModuleBuilder(depIdeInfo, libraryFactory, resourceModuleBuilderCache);
      targetResourceModule.addTransitiveResources(depTargetResourceModule.getTransitiveResources());
      targetResourceModule.addResourceLibraryKeys(depTargetResourceModule.getResourceLibraryKeys());
      targetResourceModule.addTransitiveResourceDependencies(
          depTargetResourceModule.getTransitiveResourceDependencies().stream()
              .filter(key -> !targetKey.equals(key))
              .collect(Collectors.toList()));
      if (shouldCreateModule(depIdeInfo.getAndroidIdeInfo()) && !depKey.equals(targetKey)) {
        targetResourceModule.addTransitiveResourceDependency(depKey);
      }
    }
  }

  private boolean shouldCreateModule(@Nullable AndroidIdeInfo androidIdeInfo) {
    if (androidIdeInfo == null) {
      return false;
    }
    return shouldGenerateResources(androidIdeInfo)
        && shouldGenerateResourceModule(androidIdeInfo, whitelistFilter);
  }

  /**
   * Helper function to create an AndroidResourceModule.Builder with initial resource information.
   * The builder is incomplete since it doesn't contain information about dependencies. {@link
   * #getOrCreateResourceModuleBuilder} will aggregate AndroidResourceModule.Builder over its
   * transitive dependencies.
   */
  protected AndroidResourceModule.Builder createResourceModuleBuilder(
      TargetIdeInfo target, LibraryFactory libraryFactory) {
    AndroidIdeInfo androidIdeInfo = target.getAndroidIdeInfo();
    TargetKey targetKey = target.getKey();
    AndroidResourceModule.Builder androidResourceModule =
        new AndroidResourceModule.Builder(targetKey);
    if (androidIdeInfo == null) {
      String libraryKey = libraryFactory.createAarLibrary(target);
      if (libraryKey != null) {
        ArtifactLocation artifactLocation = target.getAndroidAarIdeInfo().getAar();
        if (isSourceOrWhitelistedGenPath(artifactLocation, whitelistFilter)) {
          androidResourceModule.addResourceLibraryKey(libraryKey);
        }
      }
      return androidResourceModule;
    }

    for (AndroidResFolder androidResFolder : androidIdeInfo.getResFolders()) {
      ArtifactLocation artifactLocation = androidResFolder.getRoot();
      if (isSourceOrWhitelistedGenPath(artifactLocation, whitelistFilter)) {
        if (shouldCreateFakeAar.test(artifactLocation)) {
          // we are creating aar libraries, and this resource isn't inside the project view
          // so we can skip adding it to the module
          String libraryKey = libraryFactory.createAarLibrary(androidResFolder.getAar());
          if (libraryKey != null) {
            androidResourceModule.addResourceLibraryKey(libraryKey);
          }
        } else {
          if (shouldCreateModule(androidIdeInfo)) {
            androidResourceModule.addResource(artifactLocation);
          }
          androidResourceModule.addTransitiveResource(artifactLocation);
        }
      }
    }
    return androidResourceModule;
  }

  public static boolean shouldGenerateResources(AndroidIdeInfo androidIdeInfo) {
    // Generate an android resource module if this rule defines resources
    // We don't want to generate one if this depends on a legacy resource rule through :resources
    // In this case, the resource information is redundantly forwarded to this class for
    // backwards compatibility, but the android_resource rule itself is already generating
    // the android resource module
    return androidIdeInfo.generateResourceClass() && androidIdeInfo.getLegacyResources() == null;
  }

  public static boolean shouldGenerateResourceModule(
      AndroidIdeInfo androidIdeInfo, Predicate<ArtifactLocation> whitelistTester) {
    return androidIdeInfo.getResFolders().stream()
        .map(resource -> resource.getRoot())
        .anyMatch(location -> isSourceOrWhitelistedGenPath(location, whitelistTester));
  }

  public static boolean isSourceOrWhitelistedGenPath(
      ArtifactLocation artifactLocation, Predicate<ArtifactLocation> tester) {
    return artifactLocation.isSource() || tester.test(artifactLocation);
  }

  private ImmutableList<AndroidResourceModule> buildAndroidResourceModules(
      ImmutableList<AndroidResourceModule> inputModules) {
    // Filter empty resource modules
    List<AndroidResourceModule> androidResourceModules =
        inputModules.stream()
            .filter(
                androidResourceModule ->
                    !(androidResourceModule.resources.isEmpty()
                        && androidResourceModule.resourceLibraryKeys.isEmpty()))
            .collect(Collectors.toList());

    // Detect, filter, and warn about multiple R classes
    Multimap<String, AndroidResourceModule> javaPackageToResourceModule =
        ArrayListMultimap.create();
    for (AndroidResourceModule androidResourceModule : androidResourceModules) {
      TargetIdeInfo target = input.targetMap.get(androidResourceModule.targetKey);
      AndroidIdeInfo androidIdeInfo = target.getAndroidIdeInfo();
      assert androidIdeInfo != null;
      javaPackageToResourceModule.put(
          androidIdeInfo.getResourceJavaPackage(), androidResourceModule);
    }

    List<AndroidResourceModule> result = Lists.newArrayList();
    for (String resourceJavaPackage : javaPackageToResourceModule.keySet()) {
      Collection<AndroidResourceModule> androidResourceModulesWithJavaPackage =
          javaPackageToResourceModule.get(resourceJavaPackage);

      if (androidResourceModulesWithJavaPackage.size() == 1) {
        result.addAll(androidResourceModulesWithJavaPackage);
      } else {
        StringBuilder messageBuilder = new StringBuilder();
        messageBuilder
            .append("Multiple R classes generated with the same java package ")
            .append(resourceJavaPackage)
            .append(".R: ");
        messageBuilder.append('\n');
        for (AndroidResourceModule androidResourceModule : androidResourceModulesWithJavaPackage) {
          messageBuilder.append("  ").append(androidResourceModule.targetKey).append('\n');
        }

        if (mergeResourcesEnabled.getValue()) {
          messageBuilder.append("  ").append("Merging Resources...").append("\n");
          String message = messageBuilder.toString();
          context.accept(IssueOutput.issue(Category.INFORMATION, message).build());

          result.add(mergeAndroidResourceModules(androidResourceModulesWithJavaPackage));
        } else {
          String message = messageBuilder.toString();
          context.accept(new PerformanceWarning(message));
          context.accept(IssueOutput.warn(message).build());

          result.add(selectBestAndroidResourceModule(androidResourceModulesWithJavaPackage));
        }
      }
    }

    Collections.sort(result, Comparator.comparing(m -> m.targetKey));
    return ImmutableList.copyOf(result);
  }

  private AndroidResourceModule selectBestAndroidResourceModule(
      Collection<AndroidResourceModule> androidResourceModulesWithJavaPackage) {
    return androidResourceModulesWithJavaPackage.stream()
        .max(
            (lhs, rhs) ->
                ComparisonChain.start()
                    .compare(lhs.resources.size(), rhs.resources.size()) // Most resources wins
                    .compare(
                        lhs.transitiveResources.size(),
                        rhs.transitiveResources.size()) // Most transitive resources wins
                    .compare(
                        lhs.resourceLibraryKeys.size(),
                        rhs.resourceLibraryKeys.size()) // Most transitive resources wins
                    .compare(
                        rhs.targetKey.toString().length(),
                        lhs.targetKey
                            .toString()
                            .length()) // Shortest label wins - note lhs, rhs are flipped
                    .result())
        .get();
  }

  private static AndroidResourceModule mergeAndroidResourceModules(
      Collection<AndroidResourceModule> modules) {
    // Choose the shortest label as the canonical label (arbitrarily chosen from the original
    // filtering logic)
    TargetKey targetKey =
        modules.stream()
            .map(m -> m.targetKey)
            .min(Comparator.comparingInt(tk -> tk.toString().length()))
            .get();

    AndroidResourceModule.Builder moduleBuilder = AndroidResourceModule.builder(targetKey);
    modules.forEach(
        m ->
            moduleBuilder
                .addResources(m.resources)
                .addTransitiveResources(m.transitiveResources)
                .addResourceLibraryKeys(m.resourceLibraryKeys)
                .addTransitiveResourceDependencies(m.transitiveResourceDependencies));
    return moduleBuilder.build();
  }

  static class LibraryFactory {
    private Map<String, AarLibrary> aarLibraries = new HashMap<>();

    public ImmutableMap<String, AarLibrary> getAarLibs() {
      return ImmutableMap.copyOf(aarLibraries);
    }

    /**
     * Creates a new Aar repository for this target, if possible, or locates an existing one if one
     * already existed for this location. Returns the key for the library or null if no aar exists
     * for this target.
     */
    @Nullable
    private String createAarLibrary(@NotNull TargetIdeInfo target) {
      // NOTE: we are not doing jdeps optimization, even though we have the jdeps data for the AAR's
      // jar. The aar might still have resources that are used (e.g., @string/foo in .xml), and we
      // don't have the equivalent of jdeps data.
      if (target.getAndroidAarIdeInfo() == null
          || target.getJavaIdeInfo() == null
          || target.getJavaIdeInfo().getJars().isEmpty()) {
        return null;
      }

      String libraryKey =
          LibraryKey.libraryNameFromArtifactLocation(target.getAndroidAarIdeInfo().getAar());
      if (!aarLibraries.containsKey(libraryKey)) {
        // aar_import should only have one jar (a merged jar from the AAR's jars).
        LibraryArtifact firstJar = target.getJavaIdeInfo().getJars().iterator().next();
        aarLibraries.put(
            libraryKey, new AarLibrary(firstJar, target.getAndroidAarIdeInfo().getAar()));
      }
      return libraryKey;
    }

    /**
     * Creates a new Aar library for this ArtifactLocation. Returns the key for the library or null
     * if no aar exists for this target. Note that this function is designed for aar created by
     * aspect which does not contains class jar. Mistakenly using this function for normal aar
     * imported by user will fail to cache jar file in this Aar.
     */
    @Nullable
    private String createAarLibrary(@Nullable ArtifactLocation aar) {
      if (aar == null) {
        return null;
      }
      String libraryKey = LibraryKey.libraryNameFromArtifactLocation(aar);
      if (!aarLibraries.containsKey(libraryKey)) {
        // aar_import should only have one jar (a merged jar from the AAR's jars).
        aarLibraries.put(libraryKey, new AarLibrary(aar));
      }
      return libraryKey;
    }
  }
}
