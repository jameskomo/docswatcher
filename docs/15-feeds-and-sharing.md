# Feeds and sharing

Written 2026-09-23, before the code. The test plan at the end is the definition of done.

## The problem

The knowledge base answers "what is expiring" well, but only for someone who comes to the site
and asks. A deprecation is a date, and people keep dates in calendars and feed readers, not in
browser tabs. And a scan result is useful to one person until it can be sent to a second one.

Everything below is static. It is generated at build time from the same YAML the scanner reads,
served by the nginx that already serves the site, and adds no running cost.

## What ships

### Subscribable calendar

| URL | Contents |
|---|---|
| `/feeds/deprecations.ics` | Every dated change record, all providers |
| `/feeds/<provider>.ics` | One provider, for example `/feeds/openai.ics` |

One all-day event per change record, on its effective date. Subscribing in Google Calendar,
Outlook or Apple Calendar means the next OpenAI shutdown appears next to your standup, and updates
itself when the knowledge base changes.

- `UID` is the change ID, so an edited record updates its event instead of duplicating it.
- `SUMMARY` is `<Provider>: <title>`.
- `DESCRIPTION` holds the summary, the replacement and the guide link.
- Two `VALARM` reminders, 30 days and 7 days before. Some clients ignore alarms in subscribed
  calendars (Google does); the events still appear.
- `DTSTAMP` is the newest `observed` date in the knowledge base, not the build time, so an
  unchanged knowledge base produces a byte-identical file.

### Feed

`/feeds/deprecations.atom` has one entry per change record, newest announcement first. A feed
reader, a Slack RSS app or a Zapier trigger can follow it. `updated` is the newest of `announced`
and every source's `observed` date, so re-verifying a record surfaces it again.

### Open data

`/feeds/deprecations.json` is every change record as JSON, flattened, with the provider's name
added and the knowledge base version at the top. It is served with
`Access-Control-Allow-Origin: *`. Anyone can build on it, and the calendar and feed are generated
from the same function, so the three can never disagree.

### Live scan links

`https://docswatcher.vukisha.co.ke/#/?repo=owner/name` opens the site and scans that public
repository immediately. The scan runs in the visitor's browser, as every public scan does.

A GitLab project's link leads with the host and carries the whole group path:
`/#/?repo=gitlab.com/group/subgroup/project`. A self-managed instance works the same way, for
example `?repo=gitlab.example.com/team/api`, where the site's policy allows that host (see
[`08-features.md`](./08-features.md)). A GitHub name never contains a dot, so a first segment with
one is always a host, and every existing `owner/name` link means what it always did. Only plain
names are accepted: no scheme, no query and no ref. A GitLab link always means https.

After a GitHub or GitLab scan, the results show:

- **Copy link**, which copies the live scan link.
- **Badge**, a Markdown snippet for a README:

  ```markdown
  [![Scanned with DocsWatcher](https://img.shields.io/badge/Scanned%20with-DocsWatcher-2563eb)](https://docswatcher.vukisha.co.ke/#/?repo=owner/name)
  ```

The badge does not claim a count. It says the repository was scanned, and clicking it runs a fresh
scan. [ADR 0004](./adr/0004-live-links-not-status-badges.md) explains why.

## Serving

`web/scripts/lib/feeds.mjs`, called from `scripts/bundle-knowledge.mjs`, writes `web/public/feeds/`
during `npm run bundle`, which every build
already runs. The directory is generated and ignored by git.

Two nginx changes:

- `.ics` is not in nginx's default MIME table, so `/feeds/*.ics` gets `text/calendar` in its own
  location block. Declaring it at server level would replace the whole table and break `.wasm`,
  which the existing config warns about.
- `/feeds/` answers with `Access-Control-Allow-Origin: *`.

## Test plan

| Test | Proves |
|---|---|
| Every dated change appears once in `deprecations.ics`, with its ID as `UID` | Nothing lost, nothing duplicated |
| Undated records are left out of calendars but kept in the feed and JSON | Calendars only hold real dates |
| Per-provider calendars together hold exactly the events of the combined one | The split is complete |
| ICS lines are CRLF-terminated, folded at 75 octets, and text is escaped (`,` `;` `\` newline) | Strict clients accept it |
| Two builds of the same knowledge base produce identical bytes | Output is deterministic |
| Atom parses as XML, entries are sorted by `announced` descending, and each has an `id`, `updated` and a link | Feed readers accept it |
| JSON record count equals the knowledge base change count | Open data is complete |
| e2e: visiting `/#/?repo=owner/name` fills the URL field and starts a scan | Live links work |
| e2e: after a scan, the share controls exist and the badge snippet contains the live link | Sharing works |
| unit: `?repo=` values for GitHub, gitlab.com, nested groups and a self-managed host round-trip, and crafted values are refused | GitLab links work, GitHub links unchanged |
| e2e: a GitLab scan's address bar and badge carry `?repo=gitlab.com/group/sub/project`, and opening it scans again | GitLab links work |
