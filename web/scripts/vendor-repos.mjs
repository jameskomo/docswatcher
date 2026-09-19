// Vendors a few small public repositories into web/vendor/ so the site can scan real
// code with no network at all. Some hosts, including sandboxed embeds, block every
// outbound request; bundled real repos keep the demo honest there.
//
// Sources are pinned to a commit so a vendored copy is reproducible, and fetched
// through jsDelivr, which serves any public repo with no per-hour rate limit.
//
//   node scripts/vendor-repos.mjs            verify the vendored copies exist
//   node scripts/vendor-repos.mjs --refresh  re-download them
import { mkdirSync, writeFileSync, rmSync, existsSync, readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const vendorDir = join(here, "..", "vendor");

export const REPOS = [
  {
    name: "openai/openai-quickstart-python",
    sha: "ec8890d",
    note: "Calls the Assistants API, which OpenAI shut down, and pins a retired model.",
  },
  {
    name: "Shopify/shopify-app-template-node",
    sha: "4e73e21",
    note: "Shopify's own app template, pinned to an Admin API version that left support in 2025.",
  },
];

const SKIP = /(^|\/)(\.git|node_modules|dist|build|target|vendor|\.venv)(\/|$)/;
const BINARY = /\.(png|jpe?g|gif|svg|ico|woff2?|ttf|eot|pdf|mp4|zip|gz|jar|class|so|dylib|dll)$/i;

async function vendor(repo) {
  const [owner, name] = repo.name.split("/");
  const list = await fetch(`https://data.jsdelivr.com/v1/packages/gh/${owner}/${name}@${repo.sha}?structure=flat`);
  if (!list.ok) throw new Error(`listing ${repo.name}@${repo.sha}: HTTP ${list.status}`);
  const data = await list.json();

  const paths = (data.files ?? [])
    .map((f) => String(f.name ?? "").replace(/^\//, ""))
    .filter((p) => p && !SKIP.test(p) && !BINARY.test(p));

  const files = [];
  for (const path of paths) {
    const r = await fetch(`https://cdn.jsdelivr.net/gh/${owner}/${name}@${repo.sha}/${path.split("/").map(encodeURIComponent).join("/")}`);
    if (!r.ok) continue;
    const buf = Buffer.from(await r.arrayBuffer());
    if (buf.byteLength > 256 * 1024) continue;
    if (buf.includes(0)) continue;
    files.push({ path, text: buf.toString("utf8") });
  }
  files.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));

  const bytes = files.reduce((n, f) => n + Buffer.byteLength(f.text), 0);
  const out = { repo: repo.name, sha: repo.sha, note: repo.note, files };
  const target = join(vendorDir, `${owner}__${name}.json`);
  mkdirSync(vendorDir, { recursive: true });
  writeFileSync(target, JSON.stringify(out) + "\n");
  console.log(`vendored ${repo.name}@${repo.sha}: ${files.length} files, ${(bytes / 1024).toFixed(0)} KB`);
}

export function vendorPath(name) {
  return join(vendorDir, `${name.replace("/", "__")}.json`);
}

if (process.argv[1] && process.argv[1].endsWith("vendor-repos.mjs")) {
  const refresh = process.argv.includes("--refresh");
  const missing = REPOS.filter((r) => !existsSync(vendorPath(r.name)));
  if (!refresh && missing.length === 0) {
    for (const r of REPOS) {
      const d = JSON.parse(readFileSync(vendorPath(r.name), "utf8"));
      console.log(`have ${d.repo}@${d.sha}: ${d.files.length} files`);
    }
  } else {
    if (refresh) rmSync(vendorDir, { recursive: true, force: true });
    for (const r of REPOS) await vendor(r);
  }
}
