# Open-source study

Written 2026-09-23, before the first run. Describes how a study is chosen, run and published, so
that anyone can repeat it and get the same numbers.

## Why

"DocsWatcher finds deprecated calls" is a claim. "Of the 50 most-starred OpenAI repositories, N
call a model that is already shut down" is a fact someone can check. The study turns the scanner
on public code and publishes what it finds, including the repositories where it finds nothing.

It is also the largest test the scanner gets. Fifty real repositories written by strangers find
false positives that fixtures never will.

## Choosing repositories

A cohort is defined by a GitHub search, written down before the run, so nobody, including us, picks
repositories that make the number look better.

The first cohort:

> The 50 most-starred public repositories with the topic `openai`, excluding forks, archived
> repositories and mirrors, and excluding repositories not pushed to in the last 12 months.

Searched once; the list and each repository's commit SHA are saved with the results.

## Running

`study/run.sh <cohort>` does, for each repository:

1. Shallow-clone the default branch (`--depth 1`), and record the commit SHA.
2. Run `docswatcher match <dir> --format json --today <date>` with the date fixed for the whole
   run, so every repository is judged against the same day.
3. Save the findings, the contract inventory size and the file count.
4. Delete the clone.

Low-confidence contracts (found only in documentation or tests) are excluded, as they are by
default in CI. A repository that fails to clone or scan is reported as failed, never dropped.

## Reviewing before publishing

Every finding is read by a person against the file and line it points at before the numbers are
published. A finding judged wrong is recorded as a false positive with the reason, and fixed in
the knowledge base or engine. The published numbers are after that review, and the false positive
count is published with them.

## Publishing

`study/<cohort>/` in this repository holds:

| File | Contents |
|---|---|
| `cohort.md` | The search, the date it ran and the exclusions |
| `repos.txt` | Every repository in the cohort and the SHA scanned |
| `summary.json` | Aggregate counts only: repositories affected, findings by provider, model and month |
| `README.md` | The summary in words, and the review notes |

Per-repository findings are not published here. They go to the private operations repository.

## What we do with a finding

Nothing public names a repository before its maintainers could have known. For each affected
repository, a person opens a short, polite issue or pull request on that repository with the file,
the line, the date and the replacement. That is also the most useful thing the study produces: a
fix is worth more to a maintainer than a statistic about them.

Anyone can still check the numbers. `repos.txt` pins every SHA, and running the CLI on them with
the published date reproduces the findings exactly.

## Repeating it

Re-running a cohort later, at new SHAs and a new date, shows whether the ecosystem moved. The
cohort definition stays the same, so the two runs are comparable.
