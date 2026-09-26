package dev.docswatcher.app.alerts;

import dev.docswatcher.app.auth.OAuthProperties;
import dev.docswatcher.app.engine.ScanEngine;
import dev.docswatcher.app.model.ChangeDoc;
import dev.docswatcher.app.model.ProviderDoc;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Warned before the date: each organisation with alerts on gets one digest per channel, listing
 * the open findings that have just come within one of its thresholds. ADR 0010.
 *
 * <p>Channels are Slack, and each email address separately, so one bad address or a Slack outage
 * holds back only its own channel. A channel's findings are recorded as sent only once that
 * channel accepted the message; everything else is tried again on the next run.
 */
@Service
public class TeamAlerts {

  private static final Logger log = LoggerFactory.getLogger(TeamAlerts.class);
  static final int MAX_LINKS = 3;
  static final String SLACK = "slack";

  /** What one run did. */
  public record Result(int emails, int slackMessages, int failures) {}

  private final AlertStore store;
  private final BrevoMailer mailer;
  private final SlackNotifier slack;
  private final AlertLinks links;
  private final ScanEngine engine;
  private final ObjectMapper mapper;
  private final String githubWeb;

  public TeamAlerts(AlertStore store, BrevoMailer mailer, SlackNotifier slack, AlertLinks links, ScanEngine engine,
      ObjectMapper mapper, OAuthProperties oauth) {
    this.store = store;
    this.mailer = mailer;
    this.slack = slack;
    this.links = links;
    this.engine = engine;
    this.mapper = mapper;
    String web = oauth.webBase();
    this.githubWeb = web.endsWith("/") ? web.substring(0, web.length() - 1) : web;
  }

  public Result run(LocalDate today) {
    List<AlertStore.Settings> teams = store.enabled();
    if (teams.isEmpty()) {
      return new Result(0, 0, 0);
    }
    int furthest = teams.stream().flatMap(s -> s.thresholds().stream()).mapToInt(Integer::intValue).max().orElse(0);
    Map<Long, List<AlertStore.Candidate>> byTeam = store.candidates(today, today.plusDays(furthest)).stream()
        .collect(Collectors.groupingBy(AlertStore.Candidate::installationId));
    Map<String, Set<Integer>> sent = store.sent(today);

    int emails = 0, slacks = 0, failures = 0;
    for (AlertStore.Settings team : teams) {
      List<AlertStore.Candidate> candidates = byTeam.getOrDefault(team.installationId(), List.of());
      if (candidates.isEmpty()) {
        continue;
      }
      String org = candidates.get(0).login();
      if (team.slackWebhook() != null) {
        List<AlertStore.Candidate> due = due(candidates, team.thresholds(), sent, SLACK, today);
        if (!due.isEmpty()) {
          if (slack.post(team.slackWebhook(), AlertText.teamSlack(org, items(due, today), links.dashboard()))) {
            record(due, team.thresholds(), SLACK, today);
            slacks++;
          } else {
            failures++;
          }
        }
      }
      if (!mailer.configured()) {
        continue;
      }
      for (String email : team.emails()) {
        String channel = "email:" + email;
        List<AlertStore.Candidate> due = due(candidates, team.thresholds(), sent, channel, today);
        if (due.isEmpty()) {
          continue;
        }
        String unsubscribe = links.unsubscribeTeam(team.installationId(), email);
        AlertText.Message m = AlertText.teamEmail(org, items(due, today), links.dashboard(), unsubscribe);
        if (mailer.send(new BrevoMailer.Mail(email, m.subject(), m.text(), unsubscribe))) {
          record(due, team.thresholds(), channel, today);
          emails++;
        } else {
          failures++;
        }
      }
    }
    if (emails + slacks + failures > 0) {
      log.info("Team alerts: {} email(s), {} Slack message(s), {} failed and left for the next run", emails, slacks, failures);
    }
    return new Result(emails, slacks, failures);
  }

  /** A test message on every channel an organisation has, so a team knows its settings work. */
  public Result test(String org, AlertStore.Settings team) {
    int emails = 0, slacks = 0, failures = 0;
    if (team.slackWebhook() != null) {
      if (slack.post(team.slackWebhook(), "*DocsWatcher*: this is a test of the alerts for " + SlackNotifier.escape(org) + ". It worked.")) {
        slacks++;
      } else {
        failures++;
      }
    }
    if (mailer.configured()) {
      for (String email : team.emails()) {
        String unsubscribe = links.unsubscribeTeam(team.installationId(), email);
        AlertText.Message m = AlertText.test(org, unsubscribe);
        if (mailer.send(new BrevoMailer.Mail(email, m.subject(), m.text(), unsubscribe))) {
          emails++;
        } else {
          failures++;
        }
      }
    }
    return new Result(emails, slacks, failures);
  }

  private static List<AlertStore.Candidate> due(List<AlertStore.Candidate> candidates, List<Integer> thresholds,
      Map<String, Set<Integer>> sent, String channel, LocalDate today) {
    return candidates.stream()
        .filter(c -> Thresholds.due(days(c, today), thresholds, sent.getOrDefault(c.key(channel), Set.of())).isPresent())
        .toList();
  }

  private void record(List<AlertStore.Candidate> due, List<Integer> thresholds, String channel, LocalDate today) {
    for (AlertStore.Candidate c : due) {
      store.record(c, Thresholds.crossed(days(c, today), thresholds), channel);
    }
  }

  private static long days(AlertStore.Candidate c, LocalDate today) {
    return ChronoUnit.DAYS.between(today, c.effective());
  }

  /** Findings grouped by shutdown, in date order, each with the repositories and lines it touches. */
  List<AlertText.Item> items(List<AlertStore.Candidate> due, LocalDate today) {
    Map<String, String> providerNames = engine.providers().stream()
        .collect(Collectors.toMap(ProviderDoc::id, ProviderDoc::name, (a, b) -> a));
    Map<String, List<AlertStore.Candidate>> byChange = new LinkedHashMap<>();
    for (AlertStore.Candidate c : due) {
      byChange.computeIfAbsent(c.changeId() + "\n" + c.effective(), k -> new ArrayList<>()).add(c);
    }
    List<AlertText.Item> items = new ArrayList<>();
    for (List<AlertStore.Candidate> group : byChange.values()) {
      AlertStore.Candidate first = group.get(0);
      Optional<ChangeDoc> change = engine.change(first.changeId());
      String provider = change.map(ChangeDoc::provider).orElse(first.contractId().split(":", 2)[0]);
      List<AlertText.Place> places = group.stream().map(c -> new AlertText.Place(c.repoFullName(), c.contractId(), lines(c))).toList();
      items.add(new AlertText.Item(
          change.map(ChangeDoc::title).orElse(first.changeId()),
          providerNames.getOrDefault(provider, provider),
          first.severity(),
          first.effective(),
          days(first, today),
          change.map(ChangeDoc::summary).orElse(null),
          change.map(ChangeDoc::migration).map(ChangeDoc.Migration::guide).filter(AlertText::isWebLink).orElse(null),
          places));
    }
    return items;
  }

  /** Links to the first few lines the contract was found on, at the commit last scanned. */
  List<String> lines(AlertStore.Candidate c) {
    if (c.evidenceJson() == null) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    try {
      for (JsonNode e : mapper.readTree(c.evidenceJson())) {
        if (out.size() == MAX_LINKS) {
          break;
        }
        String path = e.path("path").asString("");
        int line = e.path("line").asInt(0);
        if (path.isEmpty()) {
          continue;
        }
        String encoded = String.join("/", Arrays.stream(path.split("/")).map(s -> UriUtils.encodePathSegment(s, StandardCharsets.UTF_8)).toList());
        out.add(githubWeb + "/" + c.repoFullName() + "/blob/" + UriUtils.encodePathSegment(c.ref(), StandardCharsets.UTF_8)
            + "/" + encoded + (line > 0 ? "#L" + line : ""));
      }
    } catch (RuntimeException e) {
      log.debug("Evidence for {} could not be read", c.contractId());
    }
    return out;
  }
}
