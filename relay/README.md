# DocsWatcher relay

A Cloudflare Worker that streams a GitHub repository tarball to the browser with permissive CORS headers. It exists because `codeload.github.com` only allows cross-origin reads from GitHub's own renderer, so the browser scanner cannot fetch a tarball directly. See `docs/adr/0003-browser-scanning-and-repo-fetch.md`.

## Endpoints

| Path | Does |
|---|---|
| `GET /health` | Liveness |
| `GET /tarball/:owner/:repo` | Tarball of the default branch |
| `GET /tarball/:owner/:repo/:ref` | Tarball at a branch, tag, or SHA |

The response is `application/gzip`. Anonymous responses are cached for five minutes by URL. A client `Authorization` header is forwarded to GitHub as-is and never cached or stored, which is how private repositories work.

## Run

```
npm test              # unit tests against a fake fetch, no network
npx wrangler dev      # local worker on http://localhost:8787
npx wrangler deploy   # needs a Cloudflare account, free tier is enough
```

Optional: `npx wrangler secret put GITHUB_TOKEN` raises GitHub's outbound limit from 60 to 5000 requests per hour for anonymous scans. Set `ALLOWED_ORIGINS` in `wrangler.toml` to a comma-separated list to restrict callers; the default is `*`.

## Limits

Tarballs over 60 MB are refused with 413. GitHub's rate limit errors are passed through as JSON with the reset time.
