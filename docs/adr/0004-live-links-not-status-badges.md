# ADR 0004: Share live scan links, not status badges

Date: 2026-09-23
Status: accepted

## Context

A README badge is the cheapest distribution a developer tool gets: every visitor to every repository
that carries one sees it. The obvious badge is a status badge, "DocsWatcher: 0 expiring", like a CI
badge.

A status badge needs a URL that returns the current count for a repository. Something has to scan
the repository to know that count, and keep it fresh.

## Options

| Option | What it takes | Problem |
|---|---|---|
| Server scans on request | The app clones and scans any public repo on a badge request | Unbounded work triggered by anonymous traffic; the privacy claim of ADR 0003 weakens, because the server now reads public code on demand |
| Server serves the last App scan | Only repositories with the GitHub App installed | Needs a public, unauthenticated endpoint on the app, which today has none, and must never reveal that a private repository exists |
| The Action publishes the count | The user's workflow commits a badge JSON to their repository or Pages | Extra setup and extra commits for every user, for a number that is stale between runs |
| **Static badge that links to a live scan** | Nothing | The badge carries no number |

## Decision

A static badge whose link runs a fresh scan in the visitor's browser.

## Reasons

- A count on a badge is stale the day a provider announces something, which is exactly the day it
  matters. A link that scans on click is never stale.
- It adds no endpoint, no server work and no cost, and keeps ADR 0003's guarantee: the server never
  reads a repository because an anonymous visitor asked it to.
- The share link and the badge are the same URL, so there is one feature, not two.

## Consequences

- The badge says "watched", not "clean". That is weaker social proof, and it is honest.
- If App installations grow, a status badge for App-installed public repositories can be added
  later from data the app already stores. It would need its own ADR, because it is the app's first
  unauthenticated endpoint.
