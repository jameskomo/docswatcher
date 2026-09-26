package dev.docswatcher.app.support;

import dev.docswatcher.app.gitlab.GitLabApi;
import dev.docswatcher.app.gitlab.GitLabProperties;
import java.util.HashMap;
import java.util.Map;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The real {@link GitLabApi}, talking to a MockRestServiceServer instead of GitLab. Tests script
 * gitlab.test on {@link #server}; the application's own HTTP code runs unchanged. Expectations may
 * be met in any order, since a scan's issue calls and its status call interleave by finding.
 *
 * <p>Clones are the one thing redirected: a project named in {@link #clones} is cloned from that
 * local repository instead of over HTTPS.
 */
public class GitLabMock {

  public static final String BASE = "https://gitlab.test";
  public static final String API = BASE + "/api/v4";

  public final MockRestServiceServer server;
  public final GitLabApi api;
  public final Map<String, String> clones = new HashMap<>();

  public GitLabMock(GitLabProperties properties) {
    RestClient.Builder builder = RestClient.builder();
    this.server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
    this.api = new GitLabApi(properties, builder) {
      @Override
      public String cloneUri(String pathWithNamespace) {
        return clones.getOrDefault(pathWithNamespace, super.cloneUri(pathWithNamespace));
      }
    };
  }

  public void reset() {
    server.reset();
    clones.clear();
  }
}
