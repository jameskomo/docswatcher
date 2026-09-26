package dev.docswatcher.app.support;

import dev.docswatcher.app.auth.OAuthProperties;
import dev.docswatcher.app.engine.ScanEngine;
import dev.docswatcher.app.github.GitHubClient;
import dev.docswatcher.app.github.GitHubUserApi;
import dev.docswatcher.app.gitlab.GitLabApi;
import dev.docswatcher.app.gitlab.GitLabProperties;
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

  @Bean
  GitHubUserMock gitHubUserMock(OAuthProperties oauth) {
    return new GitHubUserMock(oauth);
  }

  @Bean
  @Primary
  GitHubUserApi mockedGitHubUserApi(GitHubUserMock mock) {
    return mock.api;
  }

  @Bean
  GitLabMock gitLabMock(GitLabProperties properties) {
    return new GitLabMock(properties);
  }

  @Bean
  @Primary
  GitLabApi mockedGitLabApi(GitLabMock mock) {
    return mock.api;
  }
}
