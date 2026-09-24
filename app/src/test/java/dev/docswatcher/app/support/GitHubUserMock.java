package dev.docswatcher.app.support;

import dev.docswatcher.app.auth.OAuthProperties;
import dev.docswatcher.app.github.GitHubUserApi;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The real {@link GitHubUserApi}, talking to a MockRestServiceServer instead of GitHub. The
 * sign-in tests script github.com and api.github.com on {@link #server} and the application's own
 * HTTP code runs unchanged.
 */
public class GitHubUserMock {

  public final MockRestServiceServer server;
  public final GitHubUserApi api;

  public GitHubUserMock(OAuthProperties oauth) {
    RestClient.Builder builder = RestClient.builder();
    this.server = MockRestServiceServer.bindTo(builder).build();
    this.api = new GitHubUserApi(oauth, builder);
  }
}
