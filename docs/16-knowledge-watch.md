# Knowledge watch

Written 2026-09-23, before the code. The test plan at the end is the definition of done.

## The problem

The knowledge base is the product. The scanner is only as right as the 77 records it matches
against, and those records were written by hand from provider pages that keep changing.

A stale knowledge base does not fail. It returns green. Someone scans a repository that calls a
model OpenAI announced for shutdown last Tuesday, DocsWatcher says nothing is expiring, and that
person is now worse off than if they had never run it. The README warns that green means nothing
*known* is expiring. This document is about keeping "known" close to "true".

## What ships

A scheduled workflow, `.github/workflows/knowledge-watch.yml`, that runs daily and does three
things.

### 1. Watch every source

It reads every URL the knowledge base cites: each provider's `changelog` and every change record's
`sources`. For each one it fetches the page, reduces it to readable text and compares it with the
last run.

When a page changes, the report shows the **added lines that look like deprecation news**: lines
mentioning deprecation, retirement, shutdown, sunset, end of life, removal or legacy, or carrying a
date. A cookie banner changing is noise. "`o1-mini` will be shut down on 2027-01-15" is the whole
point.

### 2. Notice what is ageing

Every `sources` entry has an `observed` date: the day a person last confirmed the page said what
the record claims. The report lists records whose newest `observed` date is more than 30 days old.
Re-checking them is how the dates stay honest.

### 3. Report once, in one place

Findings go to one GitHub issue per run, labelled `knowledge-watch`, and only when there is
something to say. A day with no changes and nothing ageing produces no issue. The first run records
a baseline and reports nothing.

### Optional: an agent drafts the records

If the repository has a `CLAUDE_CODE_OAUTH_TOKEN` secret (from `claude setup-token`, billed to a
Claude subscription, no API credit), a second job hands the report to Claude Code and asks it to
draft change records for real deprecation news. The workflow validates the drafts with
`docswatcher validate` and opens a **draft** pull request labelled `agent-proposed`.

Nothing is merged automatically. A person reads the provider page and the PR, and merges or closes.

Without the secret, the job is skipped and the issue is the whole output.

## How it works

### State

State lives on an orphan branch, `knowledge-watch`, never on `main`:

```
state.json          # per URL: content hash, last fetch, HTTP status, consecutive failures
pages/<slug>.txt    # the reduced text of each page, as last seen
```

Keeping the page text, not just its hash, is what makes the diff possible. Keeping it on its own
branch keeps daily commits out of `main`'s history while leaving every change browsable on GitHub.

### Reducing a page to text

Remove `script`, `style`, `noscript`, `svg` and `head`; turn block-level tags into line breaks;
strip the remaining tags; decode entities; collapse whitespace; drop empty lines. The same page
fetched twice must reduce to the same text, or every run reports a change.

A page that reduces to under 400 characters is almost certainly rendered by JavaScript or blocked,
so there is nothing to compare. It is reported once as unreadable rather than silently watched.

### Failures

A fetch that fails keeps the previous snapshot and increments a failure counter. Three consecutive
failures put the URL in the report. One bad day at a provider's CDN is not news.

## Security

Fetched pages are untrusted input, and in the optional job they reach an agent.

- The agent gets `Read`, `Write` and `Edit` only, scoped to `knowledge/`. No `Bash`, no network,
  no git. It cannot push or change a workflow.
- The workflow, not the agent, runs validation and opens the pull request.
- The pull request is a draft and is never auto-merged. A record an injected page talks the agent
  into writing still has to get past a person who reads the source.
- The report job has `issues: write` and `contents: write` on the watch branch only. The agent job
  has `contents: write` and `pull-requests: write`, and is skipped entirely without the secret.

## Cost

GitHub Actions minutes on a public repository are free. The optional agent job uses the
subscription the token belongs to.

## Test plan

| Test | Proves |
|---|---|
| The URL list contains every provider `changelog` and every record's `sources` URL, once each | Nothing cited goes unwatched |
| Reducing the same HTML twice gives the same text; scripts, styles and tags never appear in it | Changes are real changes |
| A changed page reports only its added lines, and only those with deprecation words or dates | The report has signal, not noise |
| An unchanged page reports nothing | Quiet days are quiet |
| A page under 400 characters is marked unreadable | Client-rendered pages are not silently trusted |
| A failing URL keeps its old snapshot and is reported only after three consecutive failures | CDN blips are not news |
| Records whose newest `observed` is over 30 days old are listed, and a fresh one is not | Ageing is visible |
| A first run with no state writes a baseline and returns no report | No false alarm on day one |
| The report names the records that cite each changed URL | The reader knows what to re-check |
