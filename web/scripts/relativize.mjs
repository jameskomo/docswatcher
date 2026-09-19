// Rewrites the static export so every asset URL is relative. The site then works at any subpath.
import { readFileSync, writeFileSync, readdirSync, statSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const out = join(dirname(fileURLToPath(import.meta.url)), "..", ".output", "public");
if (!existsSync(out)) { console.error("no export at " + out); process.exit(1); }

function walk(dir) {
  const r = [];
  for (const n of readdirSync(dir)) { const p = join(dir, n); if (statSync(p).isDirectory()) r.push(...walk(p)); else r.push(p); }
  return r;
}
let touched = 0;
for (const file of walk(out)) {
  if (!/\.(html|js|mjs|css|json)$/.test(file)) continue;
  const before = readFileSync(file, "utf8");
  let after = before;
  if (file.endsWith(".html")) {
    after = after
      .replace(/"\/(nuxt-[a-z0-9]+)\//g, '"./$1/')          // href/src attributes and the import map
      .replace(/(href|src)="\/(grammars|favicon)/g, '$1="./$2')
      .replace(/cdnURL:""/g, 'cdnURL:"."')                 // runtime public asset URLs become ./nuxt/...
      .replace(/<base href="\/">/g, "");
  } else if (/\.(js|mjs)$/.test(file)) {
    // Nuxt's runtime reads app.baseURL / buildAssetsDir for dynamic chunk URLs; make them page-relative.
    after = after
      .replace(/"\/(nuxt-[a-z0-9]+)\/"/g, '"./$1/"')
      .replace(/baseURL:"\/"/g, 'baseURL:"./"');
  }
  if (after !== before) { writeFileSync(file, after); touched++; }
}
// Hash routing: every route is served by index.html; drop stale per-route folders if any.
console.log(`relativized ${touched} file(s) in ${out}`);
