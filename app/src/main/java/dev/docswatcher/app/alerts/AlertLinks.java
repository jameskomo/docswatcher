package dev.docswatcher.app.alerts;

import dev.docswatcher.app.config.AppProperties;
import org.springframework.stereotype.Component;

/**
 * Every link an alert carries, on the site's public origin. nginx sends {@code /subscribe} and
 * {@code /unsubscribe} to the app, so the links in an email work wherever the site is served.
 */
@Component
public class AlertLinks {

  /** Token kinds. The first field of every signed token, so one kind can never be used as another. */
  static final String CONFIRM = "confirm";
  static final String SUBSCRIBER = "subscriber";
  static final String TEAM = "team";

  private final String origin;
  private final LinkSigner signer;

  public AlertLinks(AppProperties properties, LinkSigner signer) {
    String o = properties.web().origin();
    this.origin = o.endsWith("/") ? o.substring(0, o.length() - 1) : o;
    this.signer = signer;
  }

  public String origin() {
    return origin;
  }

  public String dashboard() {
    return origin + "/#/app";
  }

  public String calendar() {
    return origin + "/#/calendar";
  }

  public String confirm(String email, String providers, long expiresEpochDay) {
    return origin + "/subscribe/confirm?token=" + signer.sign(CONFIRM, email, providers, Long.toString(expiresEpochDay));
  }

  public String unsubscribeSubscriber(String email) {
    return origin + "/unsubscribe?token=" + signer.sign(SUBSCRIBER, email);
  }

  public String unsubscribeTeam(long installationId, String email) {
    return origin + "/unsubscribe?token=" + signer.sign(TEAM, Long.toString(installationId), email);
  }
}
