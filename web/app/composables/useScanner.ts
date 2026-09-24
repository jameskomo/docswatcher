import type { Finding, InputFile, Inventory, Provider, RepoRef } from "~~/engine/types";

if (typeof globalThis !== "undefined" && (globalThis as any).process && !(globalThis as any).process.versions) {
  (globalThis as any).process.versions = {};
}

let grammarsPromise: Promise<any> | null = null;

async function grammars() {
  if (!grammarsPromise) {
    grammarsPromise = (async () => {
      const { createTreeSitter } = await import("~~/engine");
      // Relative to the page so the export works at any subpath.
      return createTreeSitter((file) => new URL(`./grammars/${file}`, document.baseURI).href);
    })();
  }
  return grammarsPromise;
}

/**
 * The repository's own API records (docs/19-your-own-apis.md): what was read from its
 * .docswatcher/, and every error that kept them out of the scan.
 */
export interface OwnRecords { summary: string; providers: Provider[]; errors: string[]; warnings: string[]; }

export interface ScanResult { inventory: Inventory; findings: Finding[]; own: OwnRecords | null; }

export function useScanner() {
  const { knowledge } = useKnowledge();
  const progress = ref<{ done: number; total: number; path: string } | null>(null);
  const phase = ref<string>("");

  async function run(files: InputFile[], repo: RepoRef, opts: { includeLow?: boolean } = {}): Promise<ScanResult> {
    phase.value = "Loading parsers";
    const g = await grammars();
    const { scan, match, mergeOwn, ownQueryErrors, ownSummary } = await import("~~/engine");
    const today = new Date();
    // Records that fail validation are left out and reported; the scan still runs on the bundled
    // knowledge, as the GitHub App does.
    let own = mergeOwn(knowledge, files, today);
    if (own.ok && own.providers.length) {
      const queryErrors = await ownQueryErrors(own.providers, g.loader, g.api);
      if (queryErrors.length) own = { ...own, knowledge, errors: queryErrors, ok: false };
    }
    phase.value = "Scanning";
    let last = 0;
    const inventory = await scan(files, {
      repo, knowledge: own.knowledge, grammars: g,
      onProgress: (done, total, path) => {
        const now = performance.now();
        if (now - last > 40 || done === total) { progress.value = { done, total, path }; last = now; }
      },
    });
    phase.value = "Matching";
    const findings = match(inventory, own.knowledge, { today, includeLow: opts.includeLow });
    phase.value = "";
    progress.value = null;
    const read = own.providers.length > 0 || own.errors.length > 0;
    return {
      inventory, findings,
      own: read ? { summary: ownSummary(own), providers: own.ok ? own.providers : [], errors: own.errors, warnings: own.warnings } : null,
    };
  }
  return { run, progress, phase };
}
