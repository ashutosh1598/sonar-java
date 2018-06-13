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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.sonar.check.Rule;
import org.sonar.java.checks.helpers.ConstantUtils;
import org.sonar.java.model.PackageUtils;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;
import org.sonar.plugins.java.api.tree.AnnotationTree;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.ClassTree;
import org.sonar.plugins.java.api.tree.CompilationUnitTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.NewArrayTree;
import org.sonar.plugins.java.api.tree.Tree;

@Rule(key = "S4605")
public class SpringBeansShouldBeAccessibleCheck extends IssuableSubscriptionVisitor {

  private static final String MESSAGE_FORMAT = "'%s' is not reachable by @ComponentsScan or @SpringBootApplication. "
    + "Either move it to a package configured in @ComponentsScan or update your @ComponentsScan configuration.";

  private static final String[] SPRING_BEAN_ANNOTATIONS = {
    "org.springframework.stereotype.Component",
    "org.springframework.stereotype.Service",
    "org.springframework.stereotype.Repository",
    "org.springframework.stereotype.Controller",
    "org.springframework.web.bind.annotation.RestController"
  };

  private static final String COMPONENT_SCAN = "org.springframework.context.annotation.ComponentScan";
  private static final List<String> COMPONENT_SCAN_CONFIG = Arrays.asList("basePackages", "value");
  private static final String SPRING_BOOT_APP = "org.springframework.boot.autoconfigure.SpringBootApplication";

  private final Map<String, List<ClassTree>> springBeansPerPackage = new HashMap<>();
  private final Set<String> componentScanPackageNames = new HashSet<>();
  private final Set<String> springBootApplicationPackageNames = new HashSet<>();

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return Collections.singletonList(Tree.Kind.CLASS);
  }

  @Override
  public void endOfAnalysis() {
    springBeansPerPackage.forEach((packageName, beansInPackage) -> {
      boolean packageIsNotAccessible = !componentScanPackageNames.contains(packageName)
        && springBootApplicationPackageNames.stream().noneMatch(packageName::contains);
      if (packageIsNotAccessible) {
        beansInPackage.forEach(classTree -> reportIssue(classTree.simpleName(), String.format(MESSAGE_FORMAT, classTree.simpleName())));
      }
    });
  }

  @Override
  public void visitNode(Tree tree) {
    if (!hasSemantic()) {
      return;
    }

    ClassTree classTree = (ClassTree) tree;
    String classPackageName = packageNameOf(classTree);

    Optional<AnnotationTree> componentScanAnnotation = classTree.modifiers().annotations().stream()
      .filter(a -> a.annotationType().symbolType().isSubtypeOf(COMPONENT_SCAN)).findFirst();
    if (componentScanAnnotation.isPresent()) {
      componentScanAnnotation.get().arguments().forEach(this::storePackageNames);
    } else if (classHasAnnotationFromList(classTree, SPRING_BEAN_ANNOTATIONS)) {
      addBeanToMap(classPackageName, classTree);
    } else if (classHasAnnotationFromList(classTree, SPRING_BOOT_APP)) {
      springBootApplicationPackageNames.add(classPackageName);
    }
  }

  private static String packageNameOf(ClassTree classTree) {
    if (classTree.parent().is(Tree.Kind.COMPILATION_UNIT)) {
      CompilationUnitTree cTree = (CompilationUnitTree) classTree.parent();
      if (cTree.packageDeclaration() != null) {
        return PackageUtils.packageName(cTree.packageDeclaration(), ".");
      }
    }
    return "";
  }

  private static boolean classHasAnnotationFromList(ClassTree classTree, String... annotationName) {
    return Arrays.stream(annotationName).anyMatch(annotation -> classTree.symbol().metadata().isAnnotatedWith(annotation));
  }

  private void storePackageNames(ExpressionTree argsTree) {
    if (argsTree.is(Tree.Kind.ASSIGNMENT)) {
      AssignmentExpressionTree assignment = (AssignmentExpressionTree) argsTree;
      ExpressionTree variableTree = assignment.variable();
      if (variableTree.is(Tree.Kind.IDENTIFIER) && COMPONENT_SCAN_CONFIG.contains(((IdentifierTree) variableTree).name())) {
        addLiteralValues(assignment.expression());
      }
    } else {
      addLiteralValues(argsTree);
    }
  }

  private void addBeanToMap(String classPackageName, ClassTree classTree) {
    springBeansPerPackage.computeIfAbsent(classPackageName, k -> new ArrayList<>());
    springBeansPerPackage.get(classPackageName).add(classTree);
  }

  private void addLiteralValues(ExpressionTree expressionTree) {
    if (expressionTree.is(Tree.Kind.STRING_LITERAL)) {
      String name = ConstantUtils.resolveAsStringConstant(expressionTree);
      componentScanPackageNames.add(name);
    } else if (expressionTree.is(Tree.Kind.NEW_ARRAY)) {
      for (ExpressionTree p : ((NewArrayTree) expressionTree).initializers()) {
        String name = ConstantUtils.resolveAsStringConstant(p);
        componentScanPackageNames.add(name);
      }
    } else if (expressionTree.is(Tree.Kind.MEMBER_SELECT)) {
      // TODO should resolve constant assignment
    }
  }
}
