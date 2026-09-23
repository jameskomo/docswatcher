# ADR 0005: Early-access requests go to the app server

Date: 2026-09-23
Status: accepted

## Context

The Teams page asks teams to request early access. A `mailto:` link hands the visitor to whatever
mail program the computer has, often none, and the request is lost. The site is a static export,
so something has to receive a submitted form.

ADR 0004 said the app's first unauthenticated endpoint would need its own decision. This is it.

## Options

| Option | Cost | Problem |
|---|---|---|
| `mailto:` link | Nothing | Depends on the visitor's mail setup; many requests never leave the page |
| A third-party form service | Free tier, new account | Leads are stored by someone else |
| **The app server we already run** | Nothing new | The app gains a public write endpoint |

## Decision

`POST /early-access` on the app server stores a request in Postgres. nginx routes that one path to
the app, as it does `/webhooks/`. Reading requests stays behind the API token at
`GET /api/early-access`.

The endpoint accepts anything from anyone, so it is narrow:

- Fields are validated and length-limited; the body is capped.
- A hidden field that people never fill in marks a bot; such a request is acknowledged and dropped.
- Requests per client address are rate-limited.
- One row per email address: a repeat updates it instead of adding another.
- It returns no data, only whether the request was accepted.

## Consequences

- Leads are stored where the rest of the product's data is, backed up with it, and cost nothing.
- The site's form falls back to email if the endpoint cannot be reached, so a visitor is never stuck.
- The owner is emailed about each stored request by `notify/`, a Cloudflare Worker using Email
  Routing's `send_email`: free, and on the domain that already receives the site's mail. The app
  calls it in the background with a shared token and a few retries; if it cannot, the request is
  still stored and the failure is logged.
