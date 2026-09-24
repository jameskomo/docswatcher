// Loads ../knowledge/** the way every build step reads it. One loader, so the site bundle, the
// feeds and the knowledge watcher can never disagree about what a record says.
import { readFileSync, readdirSync, statSync, existsSync } from "node:fs";
import { join } from "node:path";
import YAML from "yaml";

const iso = (d) => (d instanceof Date ? d.toISOString().slice(0, 10) : d);

export function loadKnowledge(knowledgeDir) {
  const versionFile = join(knowledgeDir, "VERSION");
  // VERSION is the one knowledge base version; the Java engine and the CLI read the same file.
  const version = (existsSync(versionFile) ? readFileSync(versionFile, "utf8").trim() : "") || "unversioned";

  const providers = [];
  for (const id of readdirSync(join(knowledgeDir, "providers")).sort()) {
    const dir = join(knowledgeDir, "providers", id);
    if (!statSync(dir).isDirectory()) continue;
    const info = YAML.parse(readFileSync(join(dir, "provider.yaml"), "utf8"));
    const detectors = YAML.parse(readFileSync(join(dir, "detectors.yaml"), "utf8"));
    detectors.manifests ??= []; detectors.literals ??= []; detectors.callsites ??= [];
    const changes = [];
    const changesDir = join(dir, "changes");
    if (existsSync(changesDir)) {
      for (const f of readdirSync(changesDir).sort()) {
        if (!f.endsWith(".yaml")) continue;
        const rec = YAML.parse(readFileSync(join(changesDir, f), "utf8"));
        for (const k of ["announced", "effective"]) rec[k] = iso(rec[k]);
        for (const s of rec.sources ?? []) s.observed = iso(s.observed);
        if (rec.summary) rec.summary = String(rec.summary).trim();
        if (rec.migration?.notes) rec.migration.notes = String(rec.migration.notes).trim();
        changes.push(rec);
      }
    }
    providers.push({ info, detectors, changes });
  }
  return { version, providers };
}

/** Records that produce findings: the same rule the engines apply. Drafts and withdrawn records are not news. */
export const isLive = (c) => c.status === "active" || c.status === "expired";

/** The newest date a person confirmed any source. What "known" means everywhere this is shown. */
export function newestObserved(knowledge) {
  let newest = "";
  for (const p of knowledge.providers) {
    for (const c of p.changes) for (const s of c.sources ?? []) if (s.observed && s.observed > newest) newest = s.observed;
  }
  return newest || "1970-01-01";
}
