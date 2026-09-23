# Deployment Architecture and Infrastructure

DocsWatcher is built to run cheaply and to keep source code off the server. A public scan runs
entirely in the visitor's browser through WebAssembly tree-sitter parsing; only the organisational
features need a server at all.

## Architecture Topology

```
   visitor's browser                             GitHub
   (runs the scan itself)                        (webhooks out, API in)
          │                                            │
          ▼                                            ▼
   ┌────────────────────────────────────────────────────────┐
   │              Cloudflare edge: TLS, DNS, WAF            │
   └───────────────────────────┬────────────────────────────┘
                               │  outbound-only tunnel, no inbound ports open
                   ┌───────────┴────────────┐
                   │  cloudflared (VPS)     │
                   └───────────┬────────────┘
            ┌──────────────────┴──────────────────┐
            ▼                                     ▼
   ┌──────────────────┐                  ┌──────────────────┐
   │  nginx           │   /api/,         │  Spring Boot app │
   │  static Nuxt 4   │   /webhooks/     │  webhooks, API,  │
   │  export +        │ ───────────────► │  scan worker     │
   │  /feeds/*        │                  └─────────┬────────┘
   └──────────────────┘                            │
                                                   ▼
                                          ┌──────────────────┐
                                          │    PostgreSQL    │
                                          │  + Flyway        │
                                          └──────────────────┘

   Not in this picture, because they run on other people's machines:
   the CLI, the GitHub Action, the MCP server, and the fix agent in a customer's CI.
```

## Infrastructure Components

| Component | Role | Runtime / Technology |
|---|---|---|
| **Public web app** | Client-side scanner, deprecation calendar, subscribable feeds, operator dashboard | Nuxt 4 static export (`ssr: false`) served by nginx with hardened security headers |
| **Tarball relay** | Permissive streaming relay for public tarball trees. Available for self-hosters; **the deployed site does not use it** — `relayUrl` is empty, so the browser goes to jsDelivr then the GitHub API | Cloudflare Worker (`relay/`) |
| **Backend Service** | GitHub App webhooks, REST API, asynchronous scan worker | Spring Boot 4 on Java 25 (`app/`) |
| **Database** | Persistent contract inventory, findings, and scan history | PostgreSQL with Flyway database migrations |
| **Ingress Tunnel** | Zero-trust HTTPS termination with no open inbound firewall ports | Cloudflare Tunnel (`cloudflared`) |

## Privacy and Execution Model

1. **Client-Side Scanner**: In the public web app, repository files are fetched into browser memory and parsed directly using Tree-sitter WebAssembly modules. Source code is never uploaded to any remote server or stored in any database.
2. **Server-Side App**: The Spring Boot service clones repositories into ephemeral local directories inside the container, performs AST matching against the knowledge base, stores findings and inventory in PostgreSQL, and immediately removes the cloned files.
3. **Automated fix PRs**: Remediation runs inside the user's own repository CI using their own
   secrets, so proprietary code never passes through a third party. The dispatch payload carries
   structural locators — path, line, column — and never the matched source line.
4. **What the server can see**: for an installed repository the app does clone the source into an
   ephemeral directory to scan it, then deletes it. The privacy claim above is about the *public*
   scanner, which uploads nothing; it is not a claim that the GitHub App never reads your code.

## Operations and Runbooks

Detailed production deployment guides, Docker Compose configurations, and systemd maintenance
timers live in a private repository, not here. They describe one specific server, its secrets
layout and its tunnel, so publishing them would document an attack surface without helping
anyone run their own copy. This page carries everything that generalises.

- `deployment/DEPLOYMENT.md` *(private)*: initial server setup, secret generation, Cloudflare tunnel configuration.
- `deployment/OPERATIONS.md` *(private)*: upgrade runbooks, backup procedures, health monitoring, rollback steps.
