// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.runtime.repository;

import com.intellij.platform.runtime.repository.impl.RuntimeModuleRepositoryImpl;
import com.intellij.platform.runtime.repository.serialization.RawRuntimeModuleDescriptor;
import com.intellij.platform.runtime.repository.serialization.RuntimeModuleRepositorySerialization;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;

/**
 * Represents the set of available modules. 
 */
@ApiStatus.NonExtendable
public interface RuntimeModuleRepository {
  /**
   * Creates a repository from a JAR file containing module descriptors.
   */
  static @NotNull RuntimeModuleRepository create(@NotNull Path moduleDescriptorsJarPath) throws MalformedRepositoryException {
    Map<String, RawRuntimeModuleDescriptor> map = RuntimeModuleRepositorySerialization.loadFromJar(moduleDescriptorsJarPath);
    return new RuntimeModuleRepositoryImpl(map, moduleDescriptorsJarPath.getParent());
  }

  /**
   * Returns the module by the given {@code moduleId} or throws an exception if this module or any module from its dependencies is not 
   * found in the repository.
   */
  @NotNull RuntimeModuleDescriptor getModule(@NotNull RuntimeModuleId moduleId);

  /**
   * Tries to resolve the module by the given {@code moduleId} and returns the resolution result. 
   */
  @NotNull ResolveResult resolveModule(@NotNull RuntimeModuleId moduleId);

  /**
   * Computes resource paths of a module with the given {@code moduleId} without resolving its dependencies.
   */
  @NotNull List<Path> getModuleResourcePaths(@NotNull RuntimeModuleId moduleId);
  
  interface ResolveResult {
    /**
     * Returns the module descriptor if resolution succeeded or {@code null} if it failed.
     */
    @Nullable RuntimeModuleDescriptor getResolvedModule();

    /**
     * Returns the path of transitive dependencies from the initially requested module to the module which failed to load if resolution
     * failed or an empty list of resolution succeeded.
     */
    @NotNull List<RuntimeModuleId> getFailedDependencyPath();
  }
}
