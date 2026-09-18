import knowledgeJson from "~~/generated/knowledge.json";
import samplesJson from "~~/generated/samples.json";
import type { ChangeRecord, InputFile, Knowledge, Provider } from "~~/engine/types";

export interface Sample { name: string; files: InputFile[]; expectedFindings: { contract: string; change: string }[]; }

const knowledge = knowledgeJson as unknown as Knowledge;
const samples = samplesJson as unknown as Sample[];
const changeIndex = new Map<string, ChangeRecord>();
const providerIndex = new Map<string, Provider>();
for (const p of knowledge.providers) {
  providerIndex.set(p.info.id, p);
  for (const c of p.changes) changeIndex.set(c.id, c);
}

const DEFAULT_SAMPLE = "openai-python-model-config";
const defaultSample = samples.find((s) => s.name === DEFAULT_SAMPLE) ?? samples[0];

export function useKnowledge() {
  return {
    knowledge,
    samples,
    defaultSample,
    change: (id: string) => changeIndex.get(id),
    provider: (id: string) => providerIndex.get(id),
    providerName: (id: string) => providerIndex.get(id)?.info.name ?? id,
  };
}
