# DocsWatcher notify

A Cloudflare Worker that emails the owner when a team requests early access on the Teams page.
See `docs/adr/0005-early-access-requests.md`.

The app stores each request, then `POST`s it here with a shared bearer token. The Worker sends one
plain-text email through Cloudflare Email Routing's `send_email` binding, with the lead's address
as `Reply-To`. Email Routing only sends to verified destination addresses, which is all this
needs, and costs nothing on the free plan.

## Run

```
npm test                      # the message builder, no network
npx wrangler deploy           # needs Email Routing on the sending domain
npx wrangler secret put TOKEN # the same value the app has as docswatcher.notify.token
npx wrangler secret put FROM  # an address on the Email Routing domain
npx wrangler secret put TO    # a verified destination address
```

The app finds the Worker through `DOCSWATCHER_NOTIFY_URL`. With that or the token unset, requests
are stored and not emailed.
