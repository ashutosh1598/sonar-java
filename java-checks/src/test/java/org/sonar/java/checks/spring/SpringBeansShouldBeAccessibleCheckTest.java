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

import com.google.common.collect.ImmutableList;
import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.Fail;
import org.junit.Test;
import org.sonar.java.AnalyzerMessage;
import org.sonar.java.ast.JavaAstScanner;
import org.sonar.java.ast.parser.JavaParser;
import org.sonar.java.ast.visitors.SubscriptionVisitor;
import org.sonar.java.checks.verifier.CheckVerifier;
import org.sonar.java.model.JavaVersionImpl;
import org.sonar.java.model.VisitorsBridgeForTests;
import org.sonar.plugins.java.api.tree.SyntaxTrivia;
import org.sonar.plugins.java.api.tree.Tree;

public class SpringBeansShouldBeAccessibleCheckTest {

  private static final String DEFAULT_TEST_JARS_DIRECTORY = "target/test-jars";

  @Test
  public void testComponentScan() {
    LocalVerifier localVerifier = new LocalVerifier();
    List<File> filesToScan = Arrays.asList(
        new File("src/test/files/checks/spring/SpringBeansShouldBeAccessibleCheck/ComponentScan/A.java"),
        new File("src/test/files/checks/spring/SpringBeansShouldBeAccessibleCheck/ComponentScan/B.java"),
        new File("src/test/files/checks/spring/SpringBeansShouldBeAccessibleCheck/ComponentScan/C.java"),
        new File("src/test/files/checks/spring/SpringBeansShouldBeAccessibleCheck/ComponentScan/DefaultPackage.java"),
        new File("src/test/files/checks/spring/SpringBeansShouldBeAccessibleCheck/ComponentScan/FalsePositive.java"),
        new File("src/test/files/checks/spring/SpringBeansShouldBeAccessibleCheck/ComponentScan/Y1.java"),
        new File("src/test/files/checks/spring/SpringBeansShouldBeAccessibleCheck/ComponentScan/Y2.java"),
        new File("src/test/files/checks/spring/SpringBeansShouldBeAccessibleCheck/ComponentScan/Z2.java"),
        new File("src/test/files/checks/spring/SpringBeansShouldBeAccessibleCheck/ComponentScan/ComponentScan.java"));

    Set<AnalyzerMessage> analysisResult = scanFiles(localVerifier, filesToScan);

    localVerifier.checkIssues(analysisResult, false);
  }

  @Test
  public void testSpringBootApplication() {
    LocalVerifier localVerifier = new LocalVerifier();
    List<File> filesToScan = Arrays.asList(
        new File("src/test/files/checks/spring/SpringBeansShouldBeAccessibleCheck/SpringBootApplication/Ko.java"),
        new File("src/test/files/checks/spring/SpringBeansShouldBeAccessibleCheck/SpringBootApplication/SpringBoot.java"),
        new File("src/test/files/checks/spring/SpringBeansShouldBeAccessibleCheck/SpringBootApplication/AnotherSpringBoot.java"),
        new File("src/test/files/checks/spring/SpringBeansShouldBeAccessibleCheck/SpringBootApplication/AnotherOk.java"),
        new File("src/test/files/checks/spring/SpringBeansShouldBeAccessibleCheck/SpringBootApplication/Ok.java")
    );

    Set<AnalyzerMessage> analysisResult = scanFiles(localVerifier, filesToScan);

    localVerifier.checkIssues(analysisResult, false);
  }

  private Set<AnalyzerMessage> scanFiles(LocalVerifier localVerifier, List<File> filesToScan) {
    List<File> classPath = Arrays.asList(
        Paths.get("target/test-jars/spring-web-4.3.7.RELEASE.jar").toFile(),
        Paths.get("target/test-jars/spring-boot-autoconfigure-2.0.2.RELEASE.jar").toFile(),
        Paths.get("target/test-jars/spring-context-4.3.7.RELEASE.jar").toFile());
    VisitorsBridgeForTests vb = new VisitorsBridgeForTests(
        ImmutableList.of(new SpringBeansShouldBeAccessibleCheck(), new ExpectedIssueCollector(localVerifier)),
        classPath,
        null);
    vb.setJavaVersion(new JavaVersionImpl());

    JavaAstScanner astScanner = new JavaAstScanner(JavaParser.createParser(), null);
    astScanner.setVisitorBridge(vb);
    astScanner.scan(filesToScan);

    VisitorsBridgeForTests.TestJavaFileScannerContext testJavaFileScannerContext = vb.lastCreatedTestContext();
    if (testJavaFileScannerContext == null) {
      Fail.fail("Semantic was required but it was not possible to create it. Please checks the logs to find out the reason.");
    }

    return testJavaFileScannerContext.getIssues();
  }

  private static class LocalVerifier extends CheckVerifier {
    @Override
    public String getExpectedIssueTrigger() {
      return "// " + ISSUE_MARKER;
    }
    @Override
    protected void collectExpectedIssues(String comment, int line) {
      super.collectExpectedIssues(comment, line);
    }
  }

  private static class ExpectedIssueCollector extends SubscriptionVisitor {

    private final LocalVerifier verifier;

    public ExpectedIssueCollector(LocalVerifier verifier) {
      this.verifier = verifier;
    }

    @Override
    public List<Tree.Kind> nodesToVisit() {
      return ImmutableList.of(Tree.Kind.TRIVIA);
    }

    @Override
    public void visitTrivia(SyntaxTrivia syntaxTrivia) {
      verifier.collectExpectedIssues(syntaxTrivia.comment(), syntaxTrivia.startLine());
    }
  }
}
