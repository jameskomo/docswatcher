import knowledgeJson from "~~/generated/knowledge.json";
import samplesJson from "~~/generated/samples.json";
import type { ChangeRecord, InputFile, Knowledge, Provider } from "~~/engine/types";
import { useScanStore } from "./useScanStore";

export interface Sample {
  name: string;
  files: InputFile[];
  expectedFindings: { contract: string; change: string }[] | null;
  /** True for a vendored copy of a real public repository, false for a knowledge base fixture. */
  real?: boolean;
  sha?: string;
  note?: string;
}

const knowledge = knowledgeJson as unknown as Knowledge;
const samples = samplesJson as unknown as Sample[];
const changeIndex = new Map<string, ChangeRecord>();
const providerIndex = new Map<string, Provider>();
for (const p of knowledge.providers) {
  providerIndex.set(p.info.id, p);
  for (const c of p.changes) changeIndex.set(c.id, c);
}

// Prefer a real repository: a genuine public project with genuine findings is the
// more convincing first impression than a hand-built fixture.
const DEFAULT_SAMPLE = "openai/openai-quickstart-python";
const defaultSample =
  samples.find((s) => s.name === DEFAULT_SAMPLE) ?? samples.find((s) => s.real) ?? samples[0];
const realSamples = samples.filter((s) => s.real);
const fixtureSamples = samples.filter((s) => !s.real);

/**
 * The providers of the scan on screen's own API records (docs/19-your-own-apis.md), which the
 * bundled knowledge does not have. Read through the store, so a finding from them keeps its title.
 */
function ownProviders(): Provider[] {
  return useScanStore().current.value?.own?.providers ?? [];
}
const ownProvider = (id: string) => ownProviders().find((p) => p.info.id === id);
const ownChange = (id: string) => {
  for (const p of ownProviders()) for (const c of p.changes) if (c.id === id) return c;
  return undefined;
};

export function useKnowledge() {
  return {
    knowledge,
    samples,
    realSamples,
    fixtureSamples,
    defaultSample,
    change: (id: string) => changeIndex.get(id) ?? ownChange(id),
    provider: (id: string) => providerIndex.get(id) ?? ownProvider(id),
    providerName: (id: string) => (providerIndex.get(id) ?? ownProvider(id))?.info.name ?? id,
  };
}
