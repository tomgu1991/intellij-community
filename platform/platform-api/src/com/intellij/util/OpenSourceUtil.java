// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util;

import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.pom.Navigatable;
import com.intellij.pom.StatePreservingNavigatable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class OpenSourceUtil {
  private OpenSourceUtil() {
  }

  public static void openSourcesFrom(@NotNull DataContext context, boolean requestFocus) {
    if (Registry.is("ide.navigation.requests")) {
      OpenSourceUtilKt.openSourcesFrom(context, requestFocus);
      return;
    }
    navigate(requestFocus, false, CommonDataKeys.NAVIGATABLE_ARRAY.getData(context));
  }

  /**
   * @deprecated use {@link #openSourcesFrom(DataContext, boolean)} instead
   */
  @Deprecated
  public static void openSourcesFrom(@NotNull DataProvider context, boolean requestFocus) {
    openSourcesFrom((DataContext)context::getData, requestFocus);
  }

  /**
   * @return {@code true} if the specified {@code object} is {@link Navigatable} and supports navigation
   * @deprecated check instanceof/null on the caller side
   */
  @Deprecated
  public static boolean canNavigate(@Nullable Object object) {
    return object instanceof Navigatable && ((Navigatable)object).canNavigate();
  }

  /**
   * @return {@code true} if the specified {@code object} is {@link Navigatable} and supports navigation to source
   * @deprecated check instanceof/null on the caller side
   */
  @Deprecated
  public static boolean canNavigateToSource(@Nullable Object object) {
    return object instanceof Navigatable && ((Navigatable)object).canNavigateToSource();
  }

  /**
   * Invokes {@link #navigate(boolean, Navigatable...)} that always requests focus.
   */
  public static void navigate(Navigatable @Nullable ... navigatables) {
    navigate(true, navigatables);
  }

  /**
   * Invokes {@link #navigate(boolean, boolean, Navigatable...)} that does not try to preserve a state of a corresponding editor.
   */
  public static void navigate(boolean requestFocus, Navigatable @Nullable ... navigatables) {
    navigate(requestFocus, false, navigatables);
  }

  /**
   * Invokes {@link #navigate(boolean, boolean, Iterable)} if at least one navigatable exists
   */
  public static void navigate(boolean requestFocus, boolean tryNotToScroll, Navigatable @Nullable ... navigatables) {
    if (navigatables != null && navigatables.length > 0) {
      navigate(requestFocus, tryNotToScroll, List.of(navigatables));
    }
  }

  /**
   * Navigates to all available sources or to the first navigatable that represents non-source navigation.
   *
   * @param requestFocus   specifies whether a focus should be requested or not
   * @param tryNotToScroll specifies whether a corresponding editor should preserve its state if it is possible
   * @param navigatables   an iterable collection of navigatables
   * @return {@code true} if at least one navigatable was processed, {@code false} otherwise
   */
  public static boolean navigate(boolean requestFocus, boolean tryNotToScroll, @Nullable Iterable<? extends Navigatable> navigatables) {
    if (navigatables == null) {
      return false;
    }
    if (Registry.is("ide.navigation.requests")) {
      Project project = OpenSourceUtilKt.findProject(navigatables);
      if (project != null) {
        return OpenSourceUtilKt.navigate(project, requestFocus, tryNotToScroll, navigatables);
      }
    }

    Navigatable nonSourceNavigatable = null;

    int maxSourcesToNavigate = Registry.intValue("ide.source.file.navigation.limit", 100);
    int navigatedSourcesCounter = 0;
    for (Navigatable navigatable : navigatables) {
      if (maxSourcesToNavigate > 0 && navigatedSourcesCounter >= maxSourcesToNavigate) {
        break;
      }

      if (navigateToSource(requestFocus, tryNotToScroll, navigatable)) {
        navigatedSourcesCounter++;
      }
      else if (navigatedSourcesCounter == 0 && nonSourceNavigatable == null && navigatable != null && navigatable.canNavigate()) {
        nonSourceNavigatable = navigatable;
      }
    }
    if (navigatedSourcesCounter > 0) {
      return true;
    }
    if (nonSourceNavigatable == null) {
      return false;
    }
    nonSourceNavigatable.navigate(requestFocus);
    return true;
  }

  /**
   * Navigates to all available sources of the specified navigatables.
   *
   * @param requestFocus   specifies whether a focus should be requested or not
   * @param tryNotToScroll specifies whether a corresponding editor should preserve its state if it is possible
   * @param navigatables   an iterable collection of navigatables
   * @return {@code true} if at least one navigatable was processed, {@code false} otherwise
   */
  public static boolean navigateToSource(boolean requestFocus,
                                         boolean tryNotToScroll,
                                         @Nullable Iterable<? extends Navigatable> navigatables) {
    if (navigatables == null) {
      return false;
    }
    boolean alreadyNavigatedToSource = false;
    for (Navigatable navigatable : navigatables) {
      if (navigateToSource(requestFocus, tryNotToScroll, navigatable)) {
        alreadyNavigatedToSource = true;
      }
    }
    return alreadyNavigatedToSource;
  }

  /**
   * Navigates to source of the specified navigatable.
   *
   * @param requestFocus   specifies whether a focus should be requested or not
   * @param tryNotToScroll specifies whether a corresponding editor should preserve its state if it is possible
   * @return {@code true} if navigation is done, {@code false} otherwise
   */
  public static boolean navigateToSource(boolean requestFocus, boolean tryNotToScroll, @Nullable Navigatable navigatable) {
    if (navigatable == null) {
      return false;
    }
    if (Registry.is("ide.navigation.requests")) {
      Project project = OpenSourceUtilKt.findProject(navigatable);
      if (project != null) {
        return OpenSourceUtilKt.navigateToSource(project, requestFocus, tryNotToScroll, navigatable);
      }
    }
    if (!navigatable.canNavigateToSource()) {
      return false;
    }
    if (tryNotToScroll && navigatable instanceof StatePreservingNavigatable) {
      ((StatePreservingNavigatable)navigatable).navigate(requestFocus, true);
    }
    else {
      navigatable.navigate(requestFocus);
    }
    return true;
  }
}
