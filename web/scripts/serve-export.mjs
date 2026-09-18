// Serves .output/public under an arbitrary prefix to prove the export is subpath-safe.
// Usage: node scripts/serve-export.mjs [port] [prefix]   e.g. node scripts/serve-export.mjs 8080 /some/deep/prefix
import { createServer } from "node:http";
import { readFileSync, existsSync, statSync } from "node:fs";
import { join, dirname, extname } from "node:path";
import { fileURLToPath } from "node:url";

const port = Number(process.argv[2] ?? 8080);
const prefix = (process.argv[3] ?? "/some/deep/prefix").replace(/\/$/, "");
const root = join(dirname(fileURLToPath(import.meta.url)), "..", ".output", "public");
const types = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".mjs": "text/javascript", ".css": "text/css", ".json": "application/json", ".wasm": "application/wasm", ".svg": "image/svg+xml", ".ico": "image/x-icon", ".png": "image/png", ".woff2": "font/woff2" };

createServer((req, res) => {
  const url = new URL(req.url, "http://x");
  if (!url.pathname.startsWith(prefix + "/") && url.pathname !== prefix) { res.writeHead(404); res.end("outside prefix"); return; }
  let rel = decodeURIComponent(url.pathname.slice(prefix.length)) || "/";
  if (rel.endsWith("/")) rel += "index.html";
  const file = join(root, rel);
  if (!existsSync(file) || statSync(file).isDirectory()) { res.writeHead(404); res.end("not found: " + rel); return; }
  res.writeHead(200, { "content-type": types[extname(file)] ?? "application/octet-stream" });
  res.end(readFileSync(file));
}).listen(port, () => console.log(`serving ${root} at http://localhost:${port}${prefix}/`));
