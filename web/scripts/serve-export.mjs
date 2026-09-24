// Serves .output/public under an arbitrary prefix to prove the export is subpath-safe.
// Usage: node scripts/serve-export.mjs [port] [prefix]   e.g. node scripts/serve-export.mjs 8080 /some/deep/prefix
import { createServer } from "node:http";
import { readFileSync, existsSync, statSync } from "node:fs";
import { join, dirname, extname } from "node:path";
import { fileURLToPath } from "node:url";

const port = Number(process.argv[2] ?? 8080);
const prefix = (process.argv[3] ?? "/some/deep/prefix").replace(/\/$/, "");
const root = join(dirname(fileURLToPath(import.meta.url)), "..", ".output", "public");
// The production Content-Security-Policy, when the private deployment checkout is present (it is
// on the machine that deploys, not in public CI). With it, the e2e tests fail where the live site
// would block a request: "Try it" once shipped with its AI hosts missing from this policy.
// DOCSWATCHER_SECURITY_HEADERS names another copy of that file, to try a policy change first.
const headersConf = process.env.DOCSWATCHER_SECURITY_HEADERS
  || join(dirname(fileURLToPath(import.meta.url)), "..", "..", "deployment", "security-headers.conf");
const csp = existsSync(headersConf)
  ? /add_header Content-Security-Policy "([^"]+)"/.exec(readFileSync(headersConf, "utf8"))?.[1]
  : undefined;
if (csp) {
  console.log(`applying the production Content-Security-Policy from ${headersConf}`);
  // Scanning a gitlab.com project needs its API. Say so up front rather than leave the GitLab
  // tests to fail on a bare "Failed to fetch".
  const connect = /(?:^|;)\s*connect-src([^;]*)/.exec(csp)?.[1].trim().split(/\s+/) ?? [];
  if (!connect.includes("https://gitlab.com")) console.warn("the policy's connect-src does not allow https://gitlab.com, so GitLab scans (tests/e2e/gitlab.spec.ts) will be refused");
}

const types = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".mjs": "text/javascript", ".css": "text/css", ".json": "application/json", ".ics": "text/calendar; charset=utf-8", ".atom": "application/atom+xml", ".wasm": "application/wasm", ".svg": "image/svg+xml", ".ico": "image/x-icon", ".png": "image/png", ".woff2": "font/woff2" };

createServer((req, res) => {
  const url = new URL(req.url, "http://x");
  if (!url.pathname.startsWith(prefix + "/") && url.pathname !== prefix) { res.writeHead(404); res.end("outside prefix"); return; }
  let rel = decodeURIComponent(url.pathname.slice(prefix.length)) || "/";
  if (rel.endsWith("/")) rel += "index.html";
  const file = join(root, rel);
  if (!existsSync(file) || statSync(file).isDirectory()) { res.writeHead(404); res.end("not found: " + rel); return; }
  res.writeHead(200, { "content-type": types[extname(file)] ?? "application/octet-stream", ...(csp ? { "content-security-policy": csp } : {}) });
  res.end(readFileSync(file));
}).listen(port, () => console.log(`serving ${root} at http://localhost:${port}${prefix}/`));
