package src.test.files.checks.spring.SpringBootApplication;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}

@Component
public class App1 { } // Compliant
