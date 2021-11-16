// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package per.tomgu.psi.element;

import com.intellij.psi.PsiClass;
import org.junit.Assert;
import org.junit.Test;
import per.tomgu.psi.GzxPsiBaseTest;

public class GzxPsiClassTest extends GzxPsiBaseTest {
  @Test
  public void testPsiClassSimple() {
    PsiClass psiClass = getPsiClass("GzxTestPsiClassSimple");
    Assert.assertNotNull(psiClass);
    String qualName = psiClass.getQualifiedName();
    System.out.println(qualName);
  }
}
