package app.beat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BeatApplication {
  public static void main(String[] args) {
    SpringApplication.run(BeatApplication.class, args);
  }
}
