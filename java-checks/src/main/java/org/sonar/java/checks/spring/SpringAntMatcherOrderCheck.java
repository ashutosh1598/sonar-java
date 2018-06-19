/*
 * SonarQube Java
 * Copyright (C) 2012-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.java.checks.spring;

import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import org.sonar.check.Rule;
import org.sonar.java.checks.helpers.ConstantUtils;
import org.sonar.java.checks.methods.AbstractMethodDetection;
import org.sonar.java.matcher.MethodMatcher;
import org.sonar.java.matcher.TypeCriteria;
import org.sonar.plugins.java.api.JavaFileScannerContext;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.Tree;

@Rule(key = "S4601")
public class SpringAntMatcherOrderCheck extends AbstractMethodDetection {

  private static final Pattern MATCHER_SPECIAL_CHAR = Pattern.compile("[?*{]");

  private static final MethodMatcher AUTHORIZE_REQUESTS_MATCHER = MethodMatcher.create()
    .typeDefinition(TypeCriteria.anyType())
    .name("authorizeRequests")
    .withAnyParameters();

  private static final List<MethodMatcher> METHOD_MATCHERS = Collections.singletonList(
    MethodMatcher.create()
      .typeDefinition(TypeCriteria.subtypeOf("org.springframework.security.config.annotation.web.AbstractRequestMatcherRegistry"))
      .name("antMatchers")
      .withAnyParameters());

  @Override
  protected List<MethodMatcher> getMethodInvocationMatchers() {
    return METHOD_MATCHERS;
  }

  @Override
  protected void onMethodInvocationFound(MethodInvocationTree method) {
    List<StringConstant> patterns = antMatchersPatterns(method);
    if (!patterns.isEmpty()) {
      List<StringConstant> previousPatterns = new PreviousMethodDetection().scan(method).previousPatterns;
      patterns.forEach(pattern -> checkAntPattern(pattern, previousPatterns));
    }
  }

  private void checkAntPattern(StringConstant pattern, List<StringConstant> previousPatterns) {
    for (int i = previousPatterns.size() - 1; i >= 0; i--) {
      StringConstant previousPattern = previousPatterns.get(i);
      if (matches(previousPattern.value, pattern.value)) {
        List<JavaFileScannerContext.Location> secondary = Collections.singletonList(
          new JavaFileScannerContext.Location("Less restrictive", previousPattern.expression));

        reportIssue(pattern.expression, "Reorder the URL patterns from most to less specific, the pattern \"" +
          pattern.value + "\" should occurs before \"" + previousPattern.value + "\".", secondary, null);
        break;
      }
    }
  }

  @VisibleForTesting
  static boolean matches(String pattern, String text) {
    if (pattern.equals(text)) {
      return true;
    }
    if (pattern.endsWith("**") && text.startsWith(pattern.substring(0, pattern.length() - 2))) {
      return true;
    }
    boolean antPatternContainsRegExp = pattern.contains("{");
    boolean textIsAlsoAnAntPattern = MATCHER_SPECIAL_CHAR.matcher(text).find();
    if (pattern.isEmpty() || antPatternContainsRegExp || textIsAlsoAnAntPattern) {
      return false;
    }
    return text.matches(antMatcherToRegEx(pattern));
  }

  @VisibleForTesting
  static String antMatcherToRegEx(String pattern) {
    // Note, regexp is not supported: {spring:[a-z]+} matches the regexp [a-z]+ as a path variable named "spring"

    // escape regexp special characters
    return escapeRegExpChars(pattern)
      // ? matches one character
      .replace("?", "[^/]")
      // ** matches zero or more directories in a path ("$$" is a temporary place holder)
      .replace("**", "$$")
      // * matches zero or more characters
      .replace("*", "[^/]*")
      .replace("$$", ".*");
  }

  @VisibleForTesting
  static String escapeRegExpChars(String pattern) {
    return pattern.replaceAll("([.(){}+|^$\\[\\]\\\\])", "\\\\$1");
  }

  private static class PreviousMethodDetection {

    List<StringConstant> previousPatterns = new ArrayList<>();

    private PreviousMethodDetection scan(MethodInvocationTree parent) {
      MethodInvocationTree childMethod = childMethodInvocation(parent);
      while (childMethod != null) {
        if (AUTHORIZE_REQUESTS_MATCHER.matches(childMethod)) {
          break;
        }
        visitMethodInvocation(childMethod);
        childMethod = childMethodInvocation(childMethod);
      }
      return this;
    }

    private void visitMethodInvocation(MethodInvocationTree methodInvocation) {
      for (MethodMatcher invocationMatcher : METHOD_MATCHERS) {
        if (invocationMatcher.matches(methodInvocation)) {
          previousPatterns.addAll(antMatchersPatterns(methodInvocation));
        }
      }
    }

    @CheckForNull
    private static MethodInvocationTree childMethodInvocation(MethodInvocationTree parent) {
      Tree methodSelect = parent.methodSelect();
      if (methodSelect.is(Tree.Kind.MEMBER_SELECT)) {
        ExpressionTree expression = ((MemberSelectExpressionTree) methodSelect).expression();
        if (expression.is(Tree.Kind.METHOD_INVOCATION)) {
          return (MethodInvocationTree) expression;
        }
      }
      return null;
    }

  }

  private static List<StringConstant> antMatchersPatterns(MethodInvocationTree mit) {
    List<StringConstant> argumentsAsString = mit.arguments().stream()
      .map(StringConstant::of)
      .collect(Collectors.toList());
    return argumentsAsString.stream().allMatch(Objects::nonNull) ? argumentsAsString : Collections.emptyList();
  }

  private static class StringConstant {
    private final ExpressionTree expression;
    private final String value;

    private StringConstant(ExpressionTree expression, String value) {
      this.expression = expression;
      this.value = value;
    }

    @CheckForNull
    private static StringConstant of(ExpressionTree expression) {
      String value = ConstantUtils.resolveAsStringConstant(expression);
      if (value != null) {
        return new StringConstant(expression, value);
      }
      return null;
    }
  }

}
