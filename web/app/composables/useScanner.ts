import type { Finding, InputFile, Inventory, RepoRef } from "~~/engine/types";

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

export interface ScanResult { inventory: Inventory; findings: Finding[]; }

export function useScanner() {
  const { knowledge } = useKnowledge();
  const progress = ref<{ done: number; total: number; path: string } | null>(null);
  const phase = ref<string>("");

  async function run(files: InputFile[], repo: RepoRef, opts: { includeLow?: boolean } = {}): Promise<ScanResult> {
    phase.value = "Loading parsers";
    const g = await grammars();
    const { scan, match } = await import("~~/engine");
    phase.value = "Scanning";
    let last = 0;
    const inventory = await scan(files, {
      repo, knowledge, grammars: g,
      onProgress: (done, total, path) => {
        const now = performance.now();
        if (now - last > 40 || done === total) { progress.value = { done, total, path }; last = now; }
      },
    });
    phase.value = "Matching";
    const findings = match(inventory, knowledge, { today: new Date(), includeLow: opts.includeLow });
    phase.value = "";
    progress.value = null;
    return { inventory, findings };
  }
  return { run, progress, phase };
}
