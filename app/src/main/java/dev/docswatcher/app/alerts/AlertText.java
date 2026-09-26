package dev.docswatcher.app.alerts;

import java.time.LocalDate;
import java.util.List;

/**
 * The words of every alert. Plain text for email, Slack's mrkdwn for Slack, and nothing else: no
 * HTML anywhere, so a repository name or a contract id is only ever shown, never run.
 */
public final class AlertText {

  private AlertText() {}

  /** One shutdown, and where it is used. {@code places} is empty for a public subscriber. */
  public record Item(String title, String provider, String severity, LocalDate effective, long days, String summary,
      String guide, List<Place> places) {}

  /** One repository's use of it: the contract, and links to the lines. */
  public record Place(String repo, String contract, List<String> links) {}

  public record Message(String subject, String text) {}

  /** Only plain http(s) links from the knowledge base go into a message. */
  public static boolean isWebLink(String url) {
    return url != null && url.length() <= 500 && url.matches("https?://[^\\s<>]+");
  }

  static String when(long days) {
    return days == 0 ? "Today" : days == 1 ? "Tomorrow" : "In " + days + " days";
  }

  private static String subjectFor(String prefix, List<Item> items) {
    Item first = items.get(0);
    if (items.size() == 1) {
      return prefix + first.title() + ", " + (first.days() == 0 ? "today" : first.days() == 1 ? "tomorrow" : "in " + first.days() + " days");
    }
    return prefix + items.size() + " shutdowns coming, the first " + (first.days() == 0 ? "today" : first.days() == 1 ? "tomorrow" : "in " + first.days() + " days");
  }

  /** A team's digest by email. Items are in date order. */
  public static Message teamEmail(String org, List<Item> items, String dashboardUrl, String unsubscribeUrl) {
    StringBuilder b = new StringBuilder();
    b.append("DocsWatcher found code in ").append(org).append(" that calls something due to shut down.\n");
    for (Item i : items) {
      b.append('\n').append(when(i.days())).append(", ").append(i.effective()).append(": ")
          .append(i.provider()).append(", ").append(i.title()).append(" (").append(i.severity()).append(")\n");
      for (Place p : i.places()) {
        b.append("  ").append(p.repo()).append(": ").append(p.contract()).append('\n');
        for (String link : p.links()) {
          b.append("    ").append(link).append('\n');
        }
      }
      if (i.guide() != null) {
        b.append("  How to migrate: ").append(i.guide()).append('\n');
      }
    }
    b.append("\nSee every finding, snooze one or mark it not in production: ").append(dashboardUrl).append('\n');
    b.append("\nYou get this because a member of ").append(org)
        .append(" added this address to DocsWatcher's alerts. Stop them: ").append(unsubscribeUrl).append('\n');
    return new Message(subjectFor(org + ": ", items), b.toString());
  }

  /** A team's digest in Slack: one line per shutdown, the repositories it touches, and the dashboard. */
  public static String teamSlack(String org, List<Item> items, String dashboardUrl) {
    StringBuilder b = new StringBuilder();
    b.append("*DocsWatcher*: ").append(items.size() == 1 ? "a shutdown" : items.size() + " shutdowns")
        .append(" coming in ").append(SlackNotifier.escape(org)).append('\n');
    for (Item i : items) {
      b.append("• *").append(when(i.days())).append("* (").append(i.effective()).append(") ")
          .append(SlackNotifier.escape(i.provider() + ", " + i.title())).append(": ")
          .append(SlackNotifier.escape(String.join(", ", i.places().stream().map(Place::repo).distinct().toList())))
          .append('\n');
    }
    b.append('<').append(dashboardUrl).append("|Open the dashboard>");
    return b.toString();
  }

  /** A public subscriber's digest. */
  public static Message subscriberEmail(List<Item> items, String calendarUrl, String unsubscribeUrl) {
    StringBuilder b = new StringBuilder();
    b.append("An API you asked DocsWatcher to watch is due to shut down.\n");
    for (Item i : items) {
      b.append('\n').append(when(i.days())).append(", ").append(i.effective()).append(": ")
          .append(i.provider()).append(", ").append(i.title()).append(" (").append(i.severity()).append(")\n");
      if (i.summary() != null && !i.summary().isBlank()) {
        b.append("  ").append(i.summary().strip()).append('\n');
      }
      if (i.guide() != null) {
        b.append("  How to migrate: ").append(i.guide()).append('\n');
      }
    }
    b.append("\nFind out whether your code calls it, in your browser: ").append(calendarUrl).append('\n');
    b.append("\nYou subscribed to these alerts on the DocsWatcher calendar. Stop them with one click: ")
        .append(unsubscribeUrl).append('\n');
    return new Message(subjectFor("DocsWatcher: ", items), b.toString());
  }

  public static Message confirmation(String confirmUrl, List<String> providers) {
    String which = providers.isEmpty() ? "every provider DocsWatcher tracks" : String.join(", ", providers);
    String text = "Someone, hopefully you, asked for DocsWatcher's email alerts at this address.\n\n"
        + "Confirm, and you will get an email 30 and 7 days before an API shutdown for " + which + ":\n"
        + confirmUrl + "\n\n"
        + "If it was not you, ignore this email. Nothing more will be sent, and the address is\n"
        + "forgotten within a week.\n";
    return new Message("Confirm your DocsWatcher alerts", text);
  }

  public static Message test(String org, String unsubscribeUrl) {
    String text = "This is a test of DocsWatcher's alerts for " + org + ". It worked.\n\n"
        + "You will be emailed here when a repository in " + org + " calls something due to shut down.\n\n"
        + "Stop these emails: " + unsubscribeUrl + "\n";
    return new Message(org + ": DocsWatcher alerts test", text);
  }
}
