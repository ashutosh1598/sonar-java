package src.test.files.checks.C;

import org.springframework.stereotype.Component;

@Component
class C {} // Noncompliant [[sc=7;ec=8]] {{'C' is not reachable by @ComponentsScan or @SpringBootApplication. Either move it to a package configured in @ComponentsScan or update your @ComponentsScan configuration.}}
