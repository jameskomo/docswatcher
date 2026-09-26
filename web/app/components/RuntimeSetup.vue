<script setup lang="ts">
import { HOSTED_INGEST } from "~/utils/runtime";

/**
 * How to send runtime telemetry, copy and paste: a Collector that keeps only outbound HTTP calls
 * and strips them to what DocsWatcher reads, and the settings each SDK needs to capture the two
 * headers and send its spans to that Collector. docs/13-runtime-observation.md.
 *
 * The same guide sits on the teams page, pointed at the hosted service, and in the dashboard,
 * pointed at whichever deployment serves it. The token is never written into it: it goes in an
 * environment variable, so the config can be committed.
 */
const props = withDefaults(defineProps<{ endpoint?: string; repo?: string }>(), { endpoint: HOSTED_INGEST });

const collector = computed(() => `# DocsWatcher as one more destination. Merge into your Collector's
# config; the pipelines you already have are not touched.
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
          - replace_pattern(attributes["url.full"], "\\\\?.*", "")
          - replace_pattern(attributes["http.url"], "\\\\?.*", "")
          - replace_pattern(attributes["http.target"], "\\\\?.*", "")
  batch/docswatcher:
    send_batch_size: 256
    send_batch_max_size: 512
    timeout: 10s

exporters:
  otlphttp/docswatcher:
    traces_endpoint: ${props.endpoint}
    # DocsWatcher reads OTLP as JSON, uncompressed.
    encoding: json
    compression: none
    headers:
      Authorization: Bearer \${env:DOCSWATCHER_INGEST_TOKEN}

service:
  pipelines:
    traces/docswatcher:
      receivers: [otlp]
      processors: [filter/docswatcher, transform/docswatcher, batch/docswatcher]
      exporters: [otlphttp/docswatcher]`);

const collectorEnv = `DOCSWATCHER_INGEST_TOKEN=dwi_...   # the token, from the dashboard`;

const java = `# Java agent: -javaagent:opentelemetry-javaagent.jar
OTEL_SERVICE_NAME=checkout
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_INSTRUMENTATION_HTTP_CLIENT_CAPTURE_RESPONSE_HEADERS=deprecation,sunset`;

const python = `# Python: pip install opentelemetry-distro opentelemetry-exporter-otlp,
# then run your app under opentelemetry-instrument
OTEL_SERVICE_NAME=checkout
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_INSTRUMENTATION_HTTP_CAPTURE_HEADERS_CLIENT_RESPONSE=deprecation,sunset`;

const nodeEnv = `# Node: start with node --require ./tracing.js app.js
OTEL_SERVICE_NAME=checkout
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`;

const nodeCode = `// tracing.js: Node names captured headers in code, not in an environment variable.
const { NodeSDK } = require("@opentelemetry/sdk-node");
const { getNodeAutoInstrumentations } = require("@opentelemetry/auto-instrumentations-node");

new NodeSDK({
  instrumentations: [getNodeAutoInstrumentations({
    "@opentelemetry/instrumentation-http": {
      headersToSpanAttributes: { client: { responseHeaders: ["deprecation", "sunset"] } },
    },
    "@opentelemetry/instrumentation-undici": {
      headersToSpanAttributes: { responseHeaders: ["deprecation", "sunset"] },
    },
  })],
}).start();`;
</script>

<template>
  <div class="runtime-setup" data-testid="runtime-setup">
    <ol class="steps">
      <li>
        <strong>Make an ingest token.</strong>
        <span v-if="repo">
          One per repository: create it under <em>Ingest tokens</em> for <span class="mono">{{ repo }}</span>.
        </span>
        <span v-else>
          Sign in on the dashboard, pick the repository under <em>What actually runs</em>, and
          create a token. Anyone with write access to the repository can.
        </span>
        The token says which repository the calls belong to, so nothing in your telemetry has to.
      </li>
      <li>
        <strong>Add DocsWatcher to your OpenTelemetry Collector.</strong>
        It keeps only outbound HTTP calls, removes everything else from them, and sends the rest
        as JSON.
        <Snippet :code="collector" testid="runtime-collector-config" />
        Give the Collector the token as an environment variable:
        <Snippet :code="collectorEnv" />
      </li>
      <li>
        <strong>Capture the two headers in each service.</strong>
        Providers announce a shutdown in the <span class="mono">Deprecation</span> and
        <span class="mono">Sunset</span> response headers, which OpenTelemetry does not record
        unless you name them. The services then send to your Collector as they may already do.
        <div class="sdks">
          <div><h4>Java</h4><Snippet :code="java" testid="runtime-sdk-java" /></div>
          <div><h4>Python</h4><Snippet :code="python" testid="runtime-sdk-python" /></div>
          <div><h4>Node</h4><Snippet :code="nodeEnv" testid="runtime-sdk-node" /><Snippet :code="nodeCode" /></div>
        </div>
      </li>
    </ol>

    <h4>What is sent, and what is kept</h4>
    <ul class="privacy" data-testid="runtime-privacy">
      <li>
        <strong>Sent:</strong> outbound HTTP client spans only, each reduced to method, host, path,
        status code and, when the provider sent them, the Deprecation and Sunset headers, plus the
        service name. The Collector removes query strings before anything leaves.
      </li>
      <li>
        <strong>Never sent:</strong> request or response bodies, any other header (your
        Authorization header included), inbound requests, database calls, logs and metrics.
        Span names, timings and trace ids travel with the OTLP format and are discarded on arrival.
      </li>
      <li>
        <strong>Kept:</strong> one row per repository, day and endpoint, with a call count, and only
        for endpoints DocsWatcher already found in your code or that carried one of the two headers.
        Nothing per call, no trace ids, no user identifiers.
      </li>
      <li>
        <strong>Stopping:</strong> revoke the token and the next export is refused.
      </li>
    </ul>
  </div>
</template>

<style scoped>
.runtime-setup { display: grid; gap: var(--s3); min-width: 0; }
/* Grid items grow to their widest line unless told not to; a long config line must scroll inside its block. */
.runtime-setup > *, .steps > li > *, .sdks > div > * { min-width: 0; }
.steps { display: grid; gap: var(--s4); padding-left: var(--s4); margin: 0; }
/* Block, not grid: a step is running text with inline code, then its snippets. */
.steps li { min-width: 0; }
.steps li > .snippet, .steps li > .sdks { margin-block: var(--s2); }
.sdks { display: grid; gap: var(--s3); min-width: 0; }
.sdks > div { display: grid; gap: var(--s2); min-width: 0; }
.sdks h4, .runtime-setup > h4 { margin: 0; }
.privacy { display: grid; gap: var(--s2); padding-left: var(--s4); margin: 0; }
</style>
