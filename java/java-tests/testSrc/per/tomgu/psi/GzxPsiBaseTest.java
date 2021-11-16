// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package per.tomgu.psi;

import com.intellij.JavaTestUtil;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.annotations.NotNull;
import org.junit.Assert;

public abstract class GzxPsiBaseTest extends LightJavaCodeInsightFixtureTestCase {
  private static final String BASE_PATH = JavaTestUtil.getJavaTestDataPath() + "/gzx";
  private static final String TEST_FILE_PATH = BASE_PATH + "/psi/src";
  private static final DefaultLightProjectDescriptor PROJECT_DESCRIPTOR = new DefaultLightProjectDescriptor() {
    @Override
    public void configureModule(@NotNull Module module, @NotNull ModifiableRootModel model, @NotNull ContentEntry contentEntry) {
      super.configureModule(module, model, contentEntry);
      LibraryTable libraryTable = model.getModuleLibraryTable();
      Library library = libraryTable.createLibrary("test");
      Library.ModifiableModel libraryModel = library.getModifiableModel();
      String path = BASE_PATH + "/rt.jar";
      VirtualFile libJarLocal = LocalFileSystem.getInstance().findFileByPath(path);
      VirtualFile jarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(libJarLocal);
      assertNotNull(jarRoot);
      libraryModel.addRoot(jarRoot, OrderRootType.CLASSES);
      libraryModel.commit();
    }
  };

  @Override
  protected @NotNull LightProjectDescriptor getProjectDescriptor() {
    return PROJECT_DESCRIPTOR;
  }

  @Override
  protected String getTestDataPath() {
    return TEST_FILE_PATH;
  }

  protected PsiClass getPsiClass(String fileName) {
    myFixture.configureByFile(fileName + ".java");
    PsiFile file = myFixture.getFile();
    Assert.assertTrue(file instanceof PsiJavaFile);
    PsiJavaFile javaFile = (PsiJavaFile)file;
    return javaFile.getClasses()[0];
  }
}
