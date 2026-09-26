# ADR 0010: Alerts before the date, for teams and for anyone

Date: 2026-09-26
Status: accepted

## Context

The Teams page promises "Warned before the date: email or Slack when a repository calls something
due to shut down, 30 and 7 days ahead." The calendar already puts every date in a calendar, with
reminders, but a calendar reminder knows nothing about a team's code, and many people would rather
have an email than another subscription.

So there are two audiences. A team using the GitHub App wants to hear about its own findings, in
its own channels. Anyone else wants to hear about the shutdowns in the providers they use.

The `notify/` worker (ADR 0005) sends through Cloudflare Email Routing, which delivers only to
verified addresses. It can tell the owner about a lead; it cannot email customers.

## Decision

### Sending

- **Email goes through Brevo's transactional API** (`POST /v3/smtp/email`). The key is the secret
  file `docswatcher.brevo.api-key`. Without it the app starts, logs that email alerts are off, and
  still sends Slack alerts. The sender is `DocsWatcher <alerts@vukisha.co.ke>`, configurable.
- **Email is plain text only.** There is no HTML part, so there is nowhere for an open-tracking
  pixel to go. Nothing from a repository can render as markup in a mail program.
- **Slack is an incoming webhook per organisation.** The URL is a credential typed in by a
  customer and then fetched by the server, which is how request forgery starts. Only
  `https://hooks.slack.com/services/A/B/C` with three plain tokens is accepted. It is checked on
  save and again before every post, and redirects are not followed. The dashboard is never sent
  the URL back, only its last four characters.

### When

A daily job (`docswatcher.alerts.cron`, 06:00 UTC) looks at what is due today and what was already
sent. A date is due at a threshold once it is that many days away or fewer, and has not passed.
Only the nearest threshold crossed matters. A finding first seen five days out gets one warning,
not a 30-day one and a 7-day one together. Sending records every threshold crossed.

What was sent is recorded per finding (or change, for subscribers), effective date, threshold and
channel, and only after that channel accepted the message. So:

- running the job twice sends nothing new;
- a day the job did not run is caught up the next day, once;
- a failed channel is retried the next day without repeating the channels that worked;
- a moved date is a new date, and is warned about again.

Team alerts cover findings that are open, or snoozed with the snooze over. Snoozed, not in
production, not affected and fixed findings are left out. Each organisation gets one digest per
channel: one Slack message, and one email per address.

### Team settings

Per installation, not per login, because a login can be renamed and taken by someone else:
on or off, up to ten email addresses, a Slack webhook, and one to four thresholds (30 and 7 by
default). Anyone who can see the organisation can read them. Changing them, or sending a test,
needs write access to at least one of its repositories. That is the bar a repository action
already sets, applied through `Viewer.canWriteOrg`, and the same-origin rule every member's state
change is held to. Tests are limited to three an hour per organisation.

Every team email carries a link that takes that one address off the list, so a person added by a
colleague can remove themselves.

### Public subscriptions

`POST /subscribe` on the calendar page is guarded like early access: validated fields, a honeypot,
five requests an hour per client (`CF-Connecting-IP`). It then:

- sends one confirmation email, at most once an hour per address, and answers the same whatever
  the address's state, so the form reveals nobody's subscription;
- confirms only when the person presses the button on the page the link opens. A mail scanner
  that fetches the link changes nothing;
- carries the provider choice in the signed link, so a new choice for a confirmed address waits
  for its own confirmation;
- stores only the address, its providers, whether and when it was confirmed, and what it was
  sent. An address never confirmed is deleted after the link's seven days;
- puts a one-click unsubscribe link in every email (`GET /unsubscribe?token=`), plus RFC 8058
  `List-Unsubscribe` headers so mail programs can do it without a click. Unsubscribing deletes
  the row.

### Links

Confirm and unsubscribe tokens are HMAC-SHA256 over their fields, with the token kind as the first
field so one kind cannot be used as another. The key is 256 random bits that the app generates on
first use and keeps in the `signing_key` table, so there is nothing to configure. Deleting the row
revokes every link already sent.

## Options considered

| Option | Why not |
|---|---|
| The `notify/` worker | It delivers only to verified addresses |
| An SMTP relay | Another credential and protocol for what one HTTPS call does |
| HTML email | Mail services add open tracking to HTML; plain text leaves nothing to add it to |
| Random tokens stored per subscriber | Works, but a signed token needs no storage, carries the confirmed choice, and never expires for unsubscribing |
| Confirm on the GET | Mail scanners fetch links and would subscribe people who never asked |
| Settings keyed by organisation login | A renamed login taken by someone else would inherit the old recipients |

## Consequences

- nginx must send `/subscribe` (which covers `/subscribe/confirm`) and `/unsubscribe` to the app,
  as it does `/early-access`.
- One app process runs the job. A lock keeps two runs in that process from overlapping. Two
  processes could both send before either records. The deployment runs one.
- The mailer stops at `docswatcher.brevo.daily-limit` sends per UTC day (300 by default). Whatever
  is left waits for the next run, because nothing unsent is recorded.
- Brevo can add click tracking to links in some accounts. Turn tracking off in the Brevo account
  so the links reach recipients as written.
- A Slack webhook is stored in Postgres as it is. Anyone who can read the database can post to
  that channel, as the owner can already read every finding.
