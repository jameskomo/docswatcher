package dev.docswatcher.app;

import dev.docswatcher.app.auth.OAuthProperties;
import dev.docswatcher.app.config.AppProperties;
import dev.docswatcher.app.config.ScanIsolationProperties;
import dev.docswatcher.app.config.ScanLimitsProperties;
import dev.docswatcher.app.engine.ScanChild;
import dev.docswatcher.app.gitlab.GitLabProperties;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AppProperties.class, ScanLimitsProperties.class, ScanIsolationProperties.class, OAuthProperties.class, GitLabProperties.class})
public class DocsWatcherApplication {

  public static void main(String[] args) {
    // The same jar or executable, started again to run one scan in a process of its own.
    if (args.length > 0 && ScanChild.COMMAND.equals(args[0])) {
      ScanChild.main(Arrays.copyOfRange(args, 1, args.length));
      return;
    }
    SpringApplication.run(DocsWatcherApplication.class, args);
  }
}
