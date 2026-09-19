package dev.docswatcher.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "docswatcher")
public record AppProperties(GitHub github, Api api, Web web, Knowledge knowledge, Worker worker) {

  public record GitHub(
      String appId,
      String privateKey,
      String webhookSecret,
      String apiBase,
      String fixLabel,
      String snoozeLabel,
      String notInProdLabel) {}

  public record Api(String token, boolean requireToken) {}

  public record Web(String origin) {}

  public record Knowledge(String dir) {}

  public record Worker(boolean enabled, int threads, long pollMs, long cloneTimeoutSeconds) {}
}
