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
    <ol class="doc-steps">
      <li>
        <span class="step" aria-hidden="true">1</span>
        <div class="step-body">
          <h3>Make an ingest token</h3>
          <p>
            The password your Collector sends with its data. It can <strong>only upload</strong> call data for one
            repository: it cannot read the dashboard or change anything.
          </p>
          <p class="doc-hint">
            <template v-if="repo">One per repository: create it under <em>Ingest tokens</em> for <code>{{ repo }}</code>.</template>
            <template v-else>Sign in on the dashboard, pick the repository under <em>What actually runs</em>, and create one. Anyone with write access can.</template>
            The token names the repository, so nothing in your telemetry has to.
          </p>
        </div>
      </li>
      <li>
        <span class="step" aria-hidden="true">2</span>
        <div class="step-body">
          <h3>Add DocsWatcher to your Collector</h3>
          <p>It keeps only outbound HTTP calls, strips everything else from them, and sends the rest as JSON.</p>
          <Snippet :code="collector" lang="yaml" testid="runtime-collector-config" />
          <p class="doc-label">The token, as an environment variable</p>
          <Snippet :code="collectorEnv" lang="dotenv" />
        </div>
      </li>
      <li>
        <span class="step" aria-hidden="true">3</span>
        <div class="step-body">
          <h3>Capture two headers in each service</h3>
          <p>
            Providers announce a shutdown in the <code>Deprecation</code> and <code>Sunset</code> response headers,
            which OpenTelemetry records only if you name them.
          </p>
          <div class="sdks">
            <div><p class="doc-label">Java</p><Snippet :code="java" lang="dotenv" testid="runtime-sdk-java" /></div>
            <div><p class="doc-label">Python</p><Snippet :code="python" lang="dotenv" testid="runtime-sdk-python" /></div>
            <div class="sdk-node"><p class="doc-label">Node</p><Snippet :code="nodeEnv" lang="dotenv" testid="runtime-sdk-node" /><Snippet :code="nodeCode" lang="javascript" /></div>
          </div>
        </div>
      </li>
    </ol>

    <h3 class="privacy-title">What is sent, and what is kept</h3>
    <div class="doc-table-scroll">
      <table class="doc-table privacy" data-testid="runtime-privacy">
        <tbody>
          <tr>
            <th scope="row">Sent</th>
            <td>Outbound HTTP client spans only, each reduced to method, host, path and status code, plus the Deprecation and Sunset headers when the provider sent them, and the service name. Query strings are removed before anything leaves.</td>
          </tr>
          <tr>
            <th scope="row">Never sent</th>
            <td>No request or response bodies, no other header (Authorization included), no inbound requests, database calls, logs or metrics. Span names, timings and trace ids arrive with the OTLP format and are discarded.</td>
          </tr>
          <tr>
            <th scope="row">Kept</th>
            <td>One row per repository, day and endpoint with a call count, only for endpoints DocsWatcher already found in your code or that carried one of the two headers. Nothing per call, no trace ids, no user identifiers.</td>
          </tr>
          <tr>
            <th scope="row">Stopping</th>
            <td>Revoke the token; the next export is refused.</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.runtime-setup { min-width: 0; display: flex; flex-direction: column; gap: 18px; }
/* One per row: the variable names are long, and two columns clip them under the Copy button. */
.sdks { display: grid; grid-template-columns: minmax(0, 1fr); gap: 16px; min-width: 0; }
.sdks > div { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.privacy-title { margin: 12px 0 0; font-size: 1.375rem; }
.privacy th { width: 9rem; font-family: var(--face); font-size: var(--t2); font-weight: 700; color: var(--ink-max); text-transform: none; letter-spacing: 0; border-bottom: 1px solid var(--hair); }
.privacy td { line-height: 1.6; }
</style>
