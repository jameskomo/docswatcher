package dev.docswatcher.app.alerts;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Customer email, through Brevo's transactional API. docs/adr/0010-alerts-before-the-date.md.
 *
 * @param apiKey a secret file, {@code /run/secrets/docswatcher.brevo.api-key}, or {@code BREVO_API_KEY}
 *     locally. Blank: email alerts are off and the app says so at startup
 * @param apiBase Brevo's API, overridable for tests
 * @param senderEmail the From address; its domain must be authenticated in Brevo
 * @param senderName the From name
 * @param dailyLimit sends per UTC day before the app stops trying; Brevo's free tier allows 300
 */
@ConfigurationProperties(prefix = "docswatcher.brevo")
public record BrevoProperties(
    String apiKey,
    @DefaultValue("https://api.brevo.com") String apiBase,
    @DefaultValue("alerts@vukisha.co.ke") String senderEmail,
    @DefaultValue("DocsWatcher") String senderName,
    @DefaultValue("300") int dailyLimit) {

  public boolean configured() {
    return apiKey != null && !apiKey.isBlank();
  }
}
