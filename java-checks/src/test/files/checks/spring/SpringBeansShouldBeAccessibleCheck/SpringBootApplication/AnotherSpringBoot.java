package another.foo;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AnotherApplication {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}

@Component
public class AnotherFoo { } // Compliant
