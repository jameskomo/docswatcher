# Runtime observation

Static scanning tells you an endpoint is referenced in your code. Runtime
observation tells you it is being called, how often, and that the provider is
already sending a notice about it.

Verified on 2026-09-19. Ingest tokens, the dashboard screen and the setup guide added 2026-09-26.

## Why it matters

A finding sourced from code alone leaves two questions open. Is this call path
actually live, or is it dead code nobody deleted? And has the provider started
warning about it yet?

Providers increasingly answer the second question in every response. The
`Deprecation` and `Sunset` headers are a standard way to say an endpoint is
going away and when. Reading them turns a static finding into a proven one:

```
2. OpenAI · endpoint POST /v1/assistants · sunset 26 Aug 2026
   Referenced in: examples/assistant-basic/assistant.py:11
   Also seen at runtime: 1,204 calls/day, last seen 2 hours ago
   Provider sent: Sunset: Wed, 26 Aug 2026 00:00:00 GMT
```

It also ranks work honestly. An endpoint called twelve hundred times a day
outranks one referenced in a file that has not executed since last year.

## What an ingest token is

An ingest token is a password for one direction only: sending call data *into*
DocsWatcher. Your OpenTelemetry Collector puts it in the `Authorization` header
of every export, and DocsWatcher uses it to answer two questions: is this data
from someone allowed to send it, and which repository does it belong to.

- **Upload only.** It is accepted on one route, the OTLP ingest. It cannot read
  the dashboard, see findings, list repositories or change any setting.
- **One repository.** It is made for one repository and can only add
  observations to that one.
- **Shown once.** Only a SHA-256 fingerprint is kept, so DocsWatcher cannot show
  it again. Lose it and make another.
- **Revocable.** Revoke it on the dashboard and the next export is refused.
- **Optional.** You need one only if the repository runs as a service in
  production and you want DocsWatcher to see what it actually calls. Static
  scanning, alerts and fixes work without it.

What travels with it is described under [What is sent](#what-is-sent): the
method, host and path of outgoing calls and two response headers, never bodies
or credentials.

## What a customer changes

Three things. There is no DocsWatcher library to install, no agent, and no code
change beyond naming two headers. You already run OpenTelemetry; this reuses it.
The same guide, with copy buttons, is on the teams page and in the dashboard's
*What actually runs* section.

### 1. Make an ingest token

On the dashboard, under *What actually runs*, pick the repository and create an
ingest token. Anyone with write access to the repository can; anyone who can
read it sees which tokens exist, who made them and when each was last used,
never the tokens themselves. The token is shown once. Revoking it refuses the
next export.

A token belongs to one repository and names it. An export sent with it needs no
`docswatcher.repo` attribute, and cannot write anywhere else: a resource naming
another repository is skipped, and a `?repo=` naming another one is refused
with 403. Only its SHA-256 is stored. A repository can hold 20 live tokens, one
per collector or environment.

The deployment owner's API token still works, and then the repository is named
by the payload, as below.

### 2. Capture the two headers

Response headers are not captured by default. Name the two you want.

| Runtime | Setting |
|---|---|
| Java agent | `OTEL_INSTRUMENTATION_HTTP_CLIENT_CAPTURE_RESPONSE_HEADERS=deprecation,sunset` |
| Python | `OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_CLIENT_RESPONSE=deprecation,sunset` |
| Node | Code level, not an environment variable. Pass `headersToSpanAttributes: { client: { responseHeaders: ["deprecation", "sunset"] } }` to the HTTP instrumentation, and `headersToSpanAttributes: { responseHeaders: [...] }` to the undici one for `fetch`. |

The Java property form is `otel.instrumentation.http.client.capture-response-headers`.

Captured headers arrive as the span attribute `http.response.header.<name>`,
lowercased, with hyphens replaced by underscores, and the value is an array
because a header may repeat.

### 3. Send the spans through a Collector

Only the OTLP JSON encoding is accepted, uncompressed. The Java and Python SDKs
cannot send JSON (they speak gRPC and `http/protobuf`), and the Node SDK can but
would send every span it has. So the services send to an OpenTelemetry
Collector, as they may already do, and the Collector forwards a reduced copy
here:

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

processors:
  # Outbound HTTP calls only: client spans that carry an HTTP method.
  filter/docswatcher:
    error_mode: ignore
    traces:
      span:
        - kind != SPAN_KIND_CLIENT
        - attributes["http.request.method"] == nil and attributes["http.method"] == nil
  # Everything DocsWatcher does not read is removed before it leaves your network.
  transform/docswatcher:
    error_mode: ignore
    trace_statements:
      - context: resource
        statements:
          - keep_keys(attributes, ["service.name"])
      - context: span
        statements:
          - keep_keys(attributes, ["http.request.method", "http.method", "server.address", "net.peer.name", "http.host", "url.full", "http.url", "url.path", "http.target", "http.response.status_code", "http.status_code", "http.response.header.deprecation", "http.response.header.sunset"])
          - replace_pattern(attributes["url.full"], "\\?.*", "")
          - replace_pattern(attributes["http.url"], "\\?.*", "")
          - replace_pattern(attributes["http.target"], "\\?.*", "")
  batch/docswatcher:
    send_batch_size: 256
    send_batch_max_size: 512
    timeout: 10s

exporters:
  otlphttp/docswatcher:
    traces_endpoint: https://docswatcher.vukisha.co.ke/api/runtime/otlp/v1/traces
    encoding: json
    compression: none
    headers:
      Authorization: Bearer ${env:DOCSWATCHER_INGEST_TOKEN}

service:
  pipelines:
    traces/docswatcher:
      receivers: [otlp]
      processors: [filter/docswatcher, transform/docswatcher, batch/docswatcher]
      exporters: [otlphttp/docswatcher]
```

The token goes in the Collector's environment as `DOCSWATCHER_INGEST_TOKEN`, so
the file can be committed. `compression: none` matters: the `otlphttp` exporter
gzips by default, and the endpoint reads plain JSON. The small batch keeps each
request well under a proxy's body limit.

Each service then points its SDK at the Collector:

```
OTEL_SERVICE_NAME=checkout
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
```

If DocsWatcher is not your only telemetry destination, add these pieces to the
Collector you already run as a second pipeline rather than redirecting the
existing one.

A Collector that serves services from several repositories needs one exporter
and token per repository, chosen by the `routing` connector on a resource
attribute. Organisation-wide tokens would remove that step; they do not exist
yet.

#### Without a Collector, with the owner token

A Node service can send JSON directly, and the owner token names the repository
in the payload:

```
OTEL_EXPORTER_OTLP_TRACES_PROTOCOL=http/json
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=https://<your-docswatcher>/api/runtime/otlp/v1/traces
OTEL_EXPORTER_OTLP_TRACES_HEADERS=Authorization=Bearer%20<token>
OTEL_RESOURCE_ATTRIBUTES=docswatcher.repo=owner/name
```

With an ingest token the last line is unnecessary. Every span the SDK has then
travels, not only outbound HTTP calls; DocsWatcher discards what it does not
read, but the Collector route keeps it inside your network in the first place.

`docswatcher.repo` is how a service says which repository it is when the token
does not. Without either, we cannot attach an observation to anything, so the
export is rejected rather than guessed at. A `?repo=owner/name` query parameter
does the same job when you cannot set a resource attribute.

## What is sent

With the Collector config above, per outbound HTTP call: method, host, path,
status code, the `Deprecation` and `Sunset` headers when the provider sent
them, and the service name. Query strings are removed by the Collector and
again on arrival. Span names, timings and trace ids travel because the OTLP
format carries them, and are discarded here.

Never sent: request or response bodies, any other header (the Authorization
header your service sent to the provider included), inbound requests, database
calls, logs and metrics.

## What is stored

One row per repository, per day, per endpoint.

| Stored | Not stored |
|---|---|
| Host, method, path | Query strings, stripped before anything is written |
| The `Deprecation` and `Sunset` header values | Any other request or response header |
| A daily call count | Request bodies, response bodies |
| First and last seen timestamps | Per-call records, trace ids, span ids, user identifiers |

Nothing arrives per call. Repeated reports of the same endpoint on the same day
increment a counter on one row. Storing the minimum is not only a privacy
posture, it is what keeps the write path cheap enough to sit in front of
production telemetry.

A span is kept in exactly two cases:

1. **It carries one of the two headers.** The provider has said something, so
   it is recorded whether or not we recognise the host.
2. **It matches a contract the scanner already found.** The call count is worth
   having for an endpoint we are already tracking.

Anything else is counted as skipped and discarded. Without that rule the table
would become a log of every outbound request the customer makes, which is
neither wanted nor ours to hold.

## How attribution works

Two steps, both best effort.

**Host to provider.** Each provider profile in the knowledge base declares
`base_urls`. Those are matched against the observed host, longest pattern
first, so a specific subdomain beats a bare apex. A base URL with a path only
claims calls under that path, which is what stops `slack.com/api` claiming
every request to `slack.com`. A templated host such as
`https://{shop}.myshopify.com/admin/api` matches any shop.

Adding a provider therefore teaches detection and attribution at once.

**Provider to contract.** The observed method and path are turned into
candidate contract ids and checked against the contracts the last scan actually
produced. Endpoint keys are not uniform: most providers key on the URL path,
but Twilio splits one product per subdomain while reusing paths, so its keys
carry the subdomain. Rather than special casing providers, every plausible
spelling is offered and whichever the scanner found is kept.

### When attribution fails

This is the weakest part of the feature and it is worth being plain about.

- **A gateway or proxy defeats it.** If your services call
  `api-gateway.internal` and it forwards to Stripe, we see your hostname, not
  Stripe's. The observation is stored with no provider and no contract.
- **A host we do not know attributes to nothing.** That is recoverable: an
  unattributed observation carrying a `Sunset` header is still shown, and it
  tells us which provider to add next.
- **A path with an embedded identifier will not match a contract.** The
  scanner produces `POST /v1/charges`; telemetry reports
  `/v1/charges/ch_3abc`. The provider still attributes, the contract does not.
- **Attribution needs a recent scan.** Contracts are matched against the most
  recent scan of that repository, so a repository that has never been scanned
  collects observations with no contract attached.

A hostname is weaker evidence than a parsed call site. Runtime observation
confirms and ranks findings; it does not replace them.

## A worked example

The exporter sends this, trimmed to one span:

```json
{
  "resourceSpans": [{
    "resource": { "attributes": [
      { "key": "service.name", "value": { "stringValue": "checkout" } },
      { "key": "docswatcher.repo", "value": { "stringValue": "acme/checkout" } }
    ]},
    "scopeSpans": [{
      "spans": [{
        "name": "POST", "kind": 3,
        "attributes": [
          { "key": "http.request.method", "value": { "stringValue": "post" } },
          { "key": "url.full", "value": { "stringValue": "https://api.openai.com/v1/assistants?verbose=true" } },
          { "key": "server.address", "value": { "stringValue": "api.openai.com" } },
          { "key": "url.path", "value": { "stringValue": "/v1/assistants" } },
          { "key": "http.response.header.sunset",
            "value": { "arrayValue": { "values": [ { "stringValue": "Wed, 26 Aug 2026 00:00:00 GMT" } ] } } },
          { "key": "http.response.header.deprecation",
            "value": { "arrayValue": { "values": [ { "stringValue": "true" } ] } } }
        ]
      }]
    }]
  }]
}
```

The response says what was done with it:

```json
{ "spans": 1, "recorded": 1, "skipped": 0, "unattributed": 0, "repo": "acme/checkout" }
```

And the stored row:

| Column | Value |
|---|---|
| repo_id | the id of `acme/checkout` |
| observed_date | 2026-09-19 |
| host | `api.openai.com` |
| method | `POST` |
| path | `/v1/assistants` |
| provider | `openai` |
| contract_id | `openai:endpoint:ANY /v1/assistants` |
| sunset_header | `Wed, 26 Aug 2026 00:00:00 GMT` |
| deprecation_header | `true` |
| call_count | 1 |

Note the query string is gone, the method is uppercased, and the contract id is
the one the scanner produced rather than one invented here.

## Reading it back

| Endpoint | Returns |
|---|---|
| `GET /api/repos/{id}/runtime` | Every observation for a repository, newest day first |
| `GET /api/repos/{id}/runtime/summary` | The dashboard's view: deprecated calls production made, and findings never observed |
| `GET /api/repos/{id}/findings` | Findings, each with an optional `runtime` block |

The `runtime` block is absent when nothing has been observed, which is the
normal case because this is opt in. When present it carries calls per day,
total calls, days observed, last seen, and the header values.

The summary groups a repository's days by endpoint. `deprecated` holds the
calls that confirm a finding or carried a provider header, most called first;
`alsoObserved` the tracked endpoints nothing has deprecated; `notObserved` the
open or snoozed findings on endpoints production has never been seen calling.
Findings on a model or SDK version go in `notObservable` as a count, because an
HTTP span cannot show a model name, which lives in the request body, and "not
seen" would be untrue. `lastReportAt` is absent until telemetry has arrived;
until then the dashboard says nothing about what production does not call.

Calls per day is the mean over the days actually observed rather than over the
calendar, so a job that only runs on weekdays does not read as quieter than it
is.

## Failure behaviour

An export is never failed because of our own problems. A span that cannot be
read is skipped and counted, not thrown. The counts come back in the response,
so a misconfiguration is visible on the first export rather than discovered
later as silence.

The one case that does return an error is an export naming no repository,
because nothing could be stored and silence would look like success.

## What this does not do yet

- **No aggregation before us.** Every span is sent and counted here. A
  high-traffic service will send a lot for a small result. A collector-side
  processor that pre-aggregates would be the next thing to build.
- **No retention policy.** Rows accumulate per day forever. A repository
  reporting continuously will grow the table without bound.
- **No organisation-wide token.** One token per repository. A shared Collector
  for many repositories routes to one exporter per repository.
- **JSON only, uncompressed.** No protobuf and no gzip, so SDKs other than Node
  go through a Collector, and its exporter must set `compression: none`.
- **No model observation.** GenAI instrumentations record `gen_ai.request.model`
  on their spans, which would confirm model findings the way paths confirm
  endpoint findings. Not read yet.
- **No alert on first sighting.** A `Sunset` header appearing on an endpoint
  nobody knew about is exactly the moment worth a notification, and nothing
  sends one.
- **No path templating.** `/v1/charges/ch_3abc` is stored as written, so a
  parameterised endpoint fragments across many rows instead of collapsing to
  one.
