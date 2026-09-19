# ADR 0003: Public scans run in the browser, with a free relay for fetching the repo

Date: 2026-09-18
Status: accepted

## Context

The public site lets anyone paste a GitHub URL and see the inventory. For complete privacy and low-latency feedback, the scan runs locally in the browser. The browser must therefore fetch the repo contents itself.

Checked on 2026-09-18 with cross-origin request headers:

| Endpoint | Cross-origin readable | Limit |
|---|---|---|
| `api.github.com/repos/{o}/{r}/tarball` | Yes, but it redirects | 60 requests per hour per IP unauthenticated |
| `codeload.github.com/...tar.gz` (redirect target) | No, allows only GitHub's own renderer origin | n/a |
| `api.github.com/repos/{o}/{r}/git/trees/{ref}?recursive=1` | Yes | 60 per hour, one call per tree |
| Blob contents per file | Yes | 60 per hour, one call per file, so unusable for whole repos |

A browser cannot read the tarball directly, and per-file fetching exhausts the limit on any real repo.

## Decision

- A Cloudflare Worker relays the tarball: it fetches `codeload.github.com` server-side and streams the bytes back with permissive cross-origin headers. It caches by commit SHA. It holds no state and no secrets for public repos.
- The browser decompresses the tarball, builds the file tree, and runs the TypeScript engine locally. No file contents leave the browser.
- For private repos, the user signs in with GitHub and the relay forwards their token to fetch the tarball. Still no server-side scanning, and the token is never stored.
- The tree API is used for one thing only: to detect languages present before deciding which tree-sitter grammars to load.

## Reasons

- Scalable, low-overhead edge distribution with no central backend bottleneck.
- Keeps the privacy guarantee absolute: the relay sees the tarball bytes in transit and stores nothing.
- Keeps scans instant. One request for the tarball, one for the tree, then everything runs client-side.

## Rejected alternatives

- **Scan on the server.** Costs money per public scan and weakens the privacy claim.
- **Per-file fetch through the API.** Rate-limited to uselessness.
- **Ask every visitor to sign in first.** Kills the top of the funnel.

## Consequences

- The relay is a fourth deployable, tiny, in its own directory. It has its own smoke test in CI.
- GitHub's own rate limit still applies to the relay's outbound calls. Authenticated relay calls with an app token raise it to five thousand per hour, which is the first thing to do if the public scanner gets popular.
- Both `Deprecation` and `Sunset` are in the headers GitHub exposes cross-origin. The runtime layer, when built, can observe them from the browser too.
