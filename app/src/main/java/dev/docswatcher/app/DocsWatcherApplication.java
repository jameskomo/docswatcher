package dev.docswatcher.app;

import dev.docswatcher.app.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class DocsWatcherApplication {

  public static void main(String[] args) {
    SpringApplication.run(DocsWatcherApplication.class, args);
  }
}
