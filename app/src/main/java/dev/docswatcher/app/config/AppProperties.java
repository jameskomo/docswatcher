package dev.docswatcher.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "docswatcher")
public record AppProperties(GitHub github, Api api, Web web, Knowledge knowledge, Worker worker) {

  public record GitHub(
      String appId,
      String privateKey,
      String webhookSecret,
      String apiBase,
      String fixLabel,
      String snoozeLabel,
      String notInProdLabel,
      String notAffectedLabel) {

    public static final String DEFAULT_NOT_AFFECTED_LABEL = "docswatcher:not-affected";

    @ConstructorBinding
    public GitHub {
      if (notAffectedLabel == null || notAffectedLabel.isBlank()) {
        notAffectedLabel = DEFAULT_NOT_AFFECTED_LABEL;
      }
    }

    /** The label set before not-affected existed; that label takes its default. */
    public GitHub(String appId, String privateKey, String webhookSecret, String apiBase, String fixLabel, String snoozeLabel, String notInProdLabel) {
      this(appId, privateKey, webhookSecret, apiBase, fixLabel, snoozeLabel, notInProdLabel, null);
    }
  }

  public record Api(String token, boolean requireToken) {}

  public record Web(String origin) {}

  public record Knowledge(String dir) {}

  public record Worker(boolean enabled, int threads, long pollMs, long cloneTimeoutSeconds) {}
}
