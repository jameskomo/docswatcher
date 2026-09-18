# Hosting and cost

DocWatcher has no paying customers. The plan is that nothing recurs on a bill until a customer pays. Every component below has a free tier, a known trigger that forces an upgrade, and a named first paid step. Check current limits before relying on any number here. Free tiers change.

## The plan in one table

| Component | Runs on | Free tier limit | Upgrade trigger | First paid step |
|---|---|---|---|---|
| Public site and dashboard | Cloudflare Pages or GitHub Pages, static export from Nuxt | Effectively unlimited for a static site. Check build minutes. | None expected | None |
| Relay | Cloudflare Worker | On the order of one hundred thousand requests per day. Check current limits. | Public scanner goes viral, or GitHub rate-limits the relay's outbound calls | Paid Workers plan, and authenticate the relay to GitHub with an app token for a higher outbound limit |
| Postgres | Neon or a comparable free Postgres tier | A small storage allowance and compute that sleeps when idle. Check current limits. | Stored contracts and findings outgrow the allowance, or cold starts hurt webhook latency | Lowest paid Postgres tier |
| App | GraalVM native-image container on a free container tier | Memory limits on free tiers are tight, typically a few hundred megabytes. Native-image keeps the app under that. | Free tier memory or hours are exceeded, or the provider retires the free tier | A single low-cost VPS running the same container. One machine handles webhooks, API, and worker. |
| CI | GitHub Actions | Free for public repositories, including native-image builds and the benchmark corpus | We move the engine or knowledge base to a private repo | Do not. Keep them public. |
| Fix PRs | The customer's GitHub Actions and the customer's API key | Zero to us | Never on our side | If a customer wants us to run fixes, price it per fix on their behalf |
| Closed-loop fix test | Our own Actions with our own key, weekly | Small token spend | Runs more often than weekly | Keep it weekly |
| Domain | Registrar | None | None | This is the one unavoidable cost, on the order of ten to twenty dollars per year |

## Why the app fits a free tier

Free container tiers fail Spring Boot on memory, not on CPU. A JVM Spring Boot process wants around half a gigabyte. A GraalVM native-image build of the same app runs well under two hundred megabytes and starts in under a second. That is the reason ADR 0001 builds native from day one rather than as a later optimisation. It is what makes the app free to run.

If a free tier still does not fit, the fallback is one low-cost VPS. The container is the same. Webhooks, API, and worker all run in the one process because the worker is a polling loop over the `scan_run` table.

## Why scans cost nothing

- Public scans run in the browser. The relay streams bytes and holds nothing. See ADR 0003.
- App scans run in the one container on shallow clones. A shallow clone of a typical repo is small, and the engine finishes in under a second on most repos. No separate worker fleet.
- Rematches after a knowledge base update do not clone. They run the matcher over stored contracts.

## What we do not spend on, and why

**No queue service.** The worker polls a Postgres table with row locking. It is correct, it is free, and it scales to several containers before it needs anything else.

**No observability service.** Structured logs to stdout and the container platform's log viewer. A single health endpoint. The synthetic canary in the test plan is the production monitor. Add a hosted service when there is an on-call rotation to page.

**No LLM tokens on our side.** Fix PRs run on the customer's key. Ingestion drafting of change records is manual in v1. When ingestion automation arrives, it runs on a schedule with a small budget cap and drafts pull requests to the knowledge base for a human to approve.

**No email or chat delivery.** GitHub issues and check runs are the notification channel. They cost nothing and reach developers where they already are.

**No CDN, no image hosting, no analytics service.** The site is static on a platform that already has a CDN. Use the platform's built-in analytics if it has any, otherwise none.

## What to watch

Three signals mean it is time to spend money, in the order they are likely to arrive:

1. The relay's outbound GitHub rate limit is hit. Authenticate the relay with an app token first. This is free.
2. Postgres cold starts make webhook responses slow enough that GitHub retries. Move to the lowest paid Postgres tier.
3. The app container exceeds free memory or hours. Move to one VPS.

None of these should arrive before the first paying customer. If one does, it is a signal the product is working.
