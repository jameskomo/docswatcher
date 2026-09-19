# Deployment Architecture and Infrastructure

DocsWatcher is designed for low-overhead, privacy-first deployment. Public scans execute entirely client-side via WebAssembly AST parsing, while organizational features run in an isolated containerized service.

## Architecture Topology

```
                  ┌────────────────────────┐
                  │   Cloudflare Tunnel    │
                  └───────────┬────────────┘
                              │
          ┌───────────────────┴───────────────────┐
          │                                       │
          ▼                                       ▼
┌──────────────────┐                    ┌──────────────────┐
│   Nginx Static   │                    │   Spring Boot    │
│  (Nuxt Public)   │                    │   Backend App    │
└──────────────────┘                    └─────────┬────────┘
                                                  │
                                                  ▼
                                        ┌──────────────────┐
                                        │    PostgreSQL    │
                                        │     Database     │
                                        └──────────────────┘
```

## Infrastructure Components

| Component | Role | Runtime / Technology |
|---|---|---|
| **Public Web App** | Radar telemetry console, client-side AST scanner, timeline | Nuxt 3 static export served via Nginx with hardened security headers |
| **Tarball Relay** | Permissive streaming relay for GitHub public tarball trees | Cloudflare Worker (`relay/`) |
| **Backend Service** | GitHub App webhooks, REST API, asynchronous scan worker | Spring Boot 4 on Java 25 (`app/`) |
| **Database** | Persistent contract inventory, findings, and scan history | PostgreSQL with Flyway database migrations |
| **Ingress Tunnel** | Zero-trust HTTPS termination with no open inbound firewall ports | Cloudflare Tunnel (`cloudflared`) |

## Privacy and Execution Model

1. **Client-Side Scanner**: In the public web app, repository files are fetched into browser memory and parsed directly using Tree-sitter WebAssembly modules. Source code is never uploaded to any remote server or stored in any database.
2. **Server-Side App**: The Spring Boot service clones repositories into ephemeral local directories inside the container, performs AST matching against the knowledge base, stores findings and inventory in PostgreSQL, and immediately removes the cloned files.
3. **Automated Fix PRs**: Remediation workflows execute inside the user's own repository CI (e.g. GitHub Actions) using their own environment secrets, ensuring proprietary code never passes through third-party intermediaries.

## Operations and Runbooks

Detailed production deployment guides, Docker Compose configurations, and systemd maintenance timers are maintained in:
- [`deployment/DEPLOYMENT.md`](../deployment/DEPLOYMENT.md): Initial server setup, secret generation, and Cloudflare tunnel configuration.
- [`deployment/OPERATIONS.md`](../deployment/OPERATIONS.md): Upgrade runbooks, backup procedures, health monitoring, and rollback steps.
