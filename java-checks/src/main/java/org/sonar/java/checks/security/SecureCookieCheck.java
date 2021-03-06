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
package org.sonar.java.checks.security;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.sonar.check.Rule;
import org.sonar.java.matcher.MethodMatcher;
import org.sonar.java.model.LiteralUtils;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;
import org.sonar.plugins.java.api.JavaFileScannerContext;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.semantic.Type;
import org.sonar.plugins.java.api.tree.Arguments;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.NewClassTree;
import org.sonar.plugins.java.api.tree.ReturnStatementTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.VariableTree;

@Rule(key = "S2092")
public class SecureCookieCheck extends IssuableSubscriptionVisitor {

  private static final String MESSAGE = "Add the \"secure\" attribute to this cookie";

  private static final String JAX_RS_COOKIE = "javax.ws.rs.core.Cookie";
  private static final String JAX_RS_NEW_COOKIE = "javax.ws.rs.core.NewCookie";
  private static final String SPRING_SAVED_COOKIE = "org.springframework.security.web.savedrequest.SavedCookie";
  private static final String PLAY_COOKIE = "play.mvc.Http$Cookie";
  private static final List<String> COOKIES = Arrays.asList(
    "javax.servlet.http.Cookie",
    "java.net.HttpCookie",
    JAX_RS_COOKIE,
    JAX_RS_NEW_COOKIE,
    "org.apache.shiro.web.servlet.SimpleCookie",
    SPRING_SAVED_COOKIE,
    PLAY_COOKIE,
    "play.mvc.Http$CookieBuilder");

  private static final List<String> SETTER_NAMES = Arrays.asList("setSecure", "withSecure");

  /**
   * Some constructors have the 'secure' parameter and do not need a 'setSecure' call afterwards.
   */
  private static final String JAVA_LANG_STRING = "java.lang.String";
  private static final String INT = "int";
  private static final String BOOLEAN = "boolean";

  private static final List<MethodMatcher> CONSTRUCTORS_WITH_SECURE_PARAM_LAST = Arrays.asList(
    constructorMatcher(JAX_RS_NEW_COOKIE).parameters(JAX_RS_COOKIE, JAVA_LANG_STRING, INT, BOOLEAN),
    constructorMatcher(JAX_RS_NEW_COOKIE).parameters(JAVA_LANG_STRING, JAVA_LANG_STRING, JAVA_LANG_STRING, JAVA_LANG_STRING, INT, JAVA_LANG_STRING, INT, BOOLEAN),
    constructorMatcher(JAX_RS_NEW_COOKIE).parameters(JAVA_LANG_STRING, JAVA_LANG_STRING, JAVA_LANG_STRING, JAVA_LANG_STRING, JAVA_LANG_STRING, INT, BOOLEAN));

  private static final List<MethodMatcher> CONSTRUCTORS_WITH_SECURE_PARAM_BEFORE_LAST = Arrays.asList(
    constructorMatcher(JAX_RS_NEW_COOKIE)
      .parameters(JAVA_LANG_STRING, JAVA_LANG_STRING, JAVA_LANG_STRING, JAVA_LANG_STRING, INT, JAVA_LANG_STRING, INT, "java.util.Date", BOOLEAN, BOOLEAN),
    constructorMatcher(JAX_RS_NEW_COOKIE).parameters(JAX_RS_COOKIE, JAVA_LANG_STRING, INT, "java.util.Date", BOOLEAN, BOOLEAN),
    constructorMatcher(JAX_RS_NEW_COOKIE).parameters(JAVA_LANG_STRING, JAVA_LANG_STRING, JAVA_LANG_STRING, JAVA_LANG_STRING, JAVA_LANG_STRING, INT, BOOLEAN, BOOLEAN),
    constructorMatcher(SPRING_SAVED_COOKIE).parameters(JAVA_LANG_STRING, JAVA_LANG_STRING, JAVA_LANG_STRING, JAVA_LANG_STRING, INT, JAVA_LANG_STRING, BOOLEAN, INT),
    constructorMatcher(PLAY_COOKIE).parameters(JAVA_LANG_STRING, JAVA_LANG_STRING, "java.lang.Integer", JAVA_LANG_STRING, JAVA_LANG_STRING, BOOLEAN, BOOLEAN));

  private static final List<MethodMatcher> CONSTRUCTORS_WITH_SECURE_PARAM_BEFORE_BEFORE_LAST = Collections.singletonList(
    constructorMatcher(PLAY_COOKIE)
      .parameters(JAVA_LANG_STRING, JAVA_LANG_STRING, "java.lang.Integer", JAVA_LANG_STRING, JAVA_LANG_STRING, BOOLEAN, BOOLEAN, "play.mvc.Http$Cookie$SameSite"));

  private final Set<Symbol.VariableSymbol> unsecuredCookies = new HashSet<>();
  private final Set<NewClassTree> unsecuredReturns = new HashSet<>();
  private final Set<MethodInvocationTree> unsecuredSetters = new HashSet<>();

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return Arrays.asList(
      Tree.Kind.VARIABLE,
      Tree.Kind.ASSIGNMENT,
      Tree.Kind.METHOD_INVOCATION,
      Tree.Kind.RETURN_STATEMENT);
  }

  @Override
  public void scanFile(JavaFileScannerContext context) {
    unsecuredCookies.clear();
    unsecuredReturns.clear();
    unsecuredSetters.clear();
    super.scanFile(context);
    unsecuredCookies.forEach(v -> reportIssue(v.declaration().simpleName(), MESSAGE));
    unsecuredReturns.forEach(r -> reportIssue(r, MESSAGE));
    unsecuredSetters.forEach(m -> reportIssue(m.arguments(), MESSAGE));
  }

  @Override
  public void visitNode(Tree tree) {
    if (!hasSemantic()) {
      return;
    }
    if (tree.is(Tree.Kind.VARIABLE)) {
      addToUnsecuredCookies((VariableTree) tree);
    } else if (tree.is(Tree.Kind.ASSIGNMENT)) {
      addToUnsecuredCookies((AssignmentExpressionTree) tree);
    } else if (tree.is(Tree.Kind.METHOD_INVOCATION)) {
      checkSecureCall((MethodInvocationTree) tree);
    } else {
      addToUnsecuredReturns((ReturnStatementTree) tree);
    }
  }

  private void addToUnsecuredCookies(VariableTree variableTree) {
    ExpressionTree initializer = variableTree.initializer();
    Symbol variableTreeSymbol = variableTree.symbol();
    // Ignore field variables
    if (initializer != null && variableTreeSymbol.isVariableSymbol() && variableTreeSymbol.owner().isMethodSymbol()) {
      boolean isInitializedWithConstructor = initializer.is(Tree.Kind.NEW_CLASS);
      boolean isMatchedType = isCookieClass(variableTreeSymbol.type()) || isCookieClass(initializer.symbolType());
      if (isInitializedWithConstructor && isMatchedType && isSecureParamFalse((NewClassTree) initializer)) {
        unsecuredCookies.add((Symbol.VariableSymbol) variableTreeSymbol);
      }
    }
  }

  private void addToUnsecuredCookies(AssignmentExpressionTree assignment) {
    if (assignment.expression().is(Tree.Kind.NEW_CLASS) && assignment.variable().is(Tree.Kind.IDENTIFIER)) {
      IdentifierTree assignmentVariable = (IdentifierTree) assignment.variable();
      Symbol assignmentVariableSymbol = assignmentVariable.symbol();
      boolean isMethodVariable = assignmentVariableSymbol.isVariableSymbol() && assignmentVariableSymbol.owner().isMethodSymbol();
      boolean isMatchedType = isCookieClass(assignmentVariable.symbolType()) || isCookieClass(assignment.expression().symbolType());
      if (isMethodVariable
          && isMatchedType
          && isSecureParamFalse((NewClassTree) assignment.expression())) {
        unsecuredCookies.add((Symbol.VariableSymbol) assignmentVariableSymbol);
      }
    }
  }

  private void checkSecureCall(MethodInvocationTree mit) {
    if (isSetSecureCall(mit) && mit.methodSelect().is(Tree.Kind.MEMBER_SELECT)) {
      ExpressionTree methodObject = ((MemberSelectExpressionTree) mit.methodSelect()).expression();
      if (methodObject.is(Tree.Kind.IDENTIFIER)) {
        IdentifierTree identifierTree = (IdentifierTree) methodObject;
        if (!LiteralUtils.isFalse(mit.arguments().get(0))) {
          unsecuredCookies.remove(identifierTree.symbol());
        } else if (identifierTree.symbol().owner().isMethodSymbol()) {
          unsecuredCookies.add((Symbol.VariableSymbol) identifierTree.symbol());
        }
      } else if (LiteralUtils.isFalse(mit.arguments().get(0))) {
        // builder method
        unsecuredSetters.add(mit);
      }
    }
  }

  private void addToUnsecuredReturns(ReturnStatementTree tree) {
    ExpressionTree returnedExpression = tree.expression();
    if (returnedExpression != null
        && returnedExpression.is(Tree.Kind.NEW_CLASS)
        && isCookieClass(returnedExpression.symbolType())
        && isSecureParamFalse((NewClassTree) returnedExpression)) {
      unsecuredReturns.add((NewClassTree) returnedExpression);
    }
  }

  private static boolean isSecureParamFalse(NewClassTree newClassTree) {
    ExpressionTree secureArgument = null;
    Arguments arguments = newClassTree.arguments();
    if (CONSTRUCTORS_WITH_SECURE_PARAM_LAST.stream().anyMatch(m -> m.matches(newClassTree))) {
      secureArgument = arguments.get(arguments.size() - 1);
    } else if (CONSTRUCTORS_WITH_SECURE_PARAM_BEFORE_LAST.stream().anyMatch(m -> m.matches(newClassTree))) {
      secureArgument = arguments.get(arguments.size() - 2);
    } else if (CONSTRUCTORS_WITH_SECURE_PARAM_BEFORE_BEFORE_LAST.stream().anyMatch(m -> m.matches(newClassTree))) {
      secureArgument = arguments.get(arguments.size() - 3);
    }
    if (secureArgument != null) {
      return LiteralUtils.isFalse(secureArgument);
    }
    return true;
  }

  private static boolean isSetSecureCall(MethodInvocationTree mit) {
    return mit.arguments().size() == 1
      && !mit.symbol().isUnknown()
      && !mit.symbol().owner().isUnknown()
      && isCookieClass(mit.symbol().owner().type())
      && SETTER_NAMES.stream().anyMatch(getIdentifier(mit).name()::equals);
  }

  private static boolean isCookieClass(Type type) {
    return COOKIES.stream().anyMatch(type::isSubtypeOf);
  }

  private static IdentifierTree getIdentifier(MethodInvocationTree mit) {
    IdentifierTree id;
    if (mit.methodSelect().is(Tree.Kind.IDENTIFIER)) {
      id = (IdentifierTree) mit.methodSelect();
    } else {
      id = ((MemberSelectExpressionTree) mit.methodSelect()).identifier();
    }
    return id;
  }

  private static MethodMatcher constructorMatcher(String fullyQualifiedName) {
    return MethodMatcher.create().typeDefinition(fullyQualifiedName).name("<init>");
  }

}
