package dev.docswatcher.app.support;

import dev.docswatcher.app.engine.ScanEngine;
import dev.docswatcher.app.github.GitHubClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestBeans {

  @Bean
  @Primary
  FakeGitHubClient fakeGitHubClient() {
    return new FakeGitHubClient();
  }

  @Bean
  @Primary
  ScanEngine fakeScanEngine() {
    return new FakeScanEngine();
  }
}
