/*
 * Copyright 2000-2007 JetBrains s.r.o.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.plugins.groovy.lang.completion.filters.modifiers;

import com.intellij.psi.filters.ElementFilter;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.plugins.groovy.lang.completion.GroovyCompletionUtil;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMethod;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrConstructor;

/**
 * @author ilyas
 */
public class ThrowsFilter implements ElementFilter {

  public boolean isAcceptable(Object element, PsiElement context) {
    if (context.getParent() instanceof PsiErrorElement) {
      PsiElement candidate = GroovyCompletionUtil.nearestLeftSibling(context.getParent());
      if ((candidate instanceof GrMethod || candidate instanceof GrConstructor) &&
          candidate.getText().trim().endsWith(")")) {
        return true;
      }
    }
    if (context.getPrevSibling() instanceof PsiErrorElement) {
      PsiElement candidate = GroovyCompletionUtil.nearestLeftSibling(context.getPrevSibling());
      if ((candidate instanceof GrMethod || candidate instanceof GrConstructor) &&
          candidate.getText().trim().endsWith(")")) {
        return true;
      }
    }
    
    return false;
  }

  public boolean isClassAcceptable(Class hintClass) {
    return true;
  }

  @NonNls
  public String toString() {
    return "'throws' keyword filter";
  }

}
