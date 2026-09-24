// Types mirror docs/02-schemas.md exactly. Key order in these interfaces is the serialization order.

export type ContractKind =
  | "sdk_package" | "sdk_method" | "endpoint" | "model" | "api_version" | "graphql_operation" | "webhook";
export type Confidence = "high" | "medium" | "low";
export type Layer = "manifest" | "literal" | "callsite";
export type Severity = "breaking" | "warning" | "info";
export type ChangeStatus = "draft" | "active" | "withdrawn" | "expired";
export type Ecosystem = "npm" | "pypi" | "maven" | "go" | "rubygems" | "packagist" | "nuget";
export type Language = "java" | "python" | "typescript" | "javascript" | "go" | "ruby" | "php" | "csharp";

export interface InputFile { path: string; text: string; }

export interface RepoRef { host: string; owner: string; name: string; ref: string; sha: string; }

export interface Evidence {
  path: string; line: number; column: number; snippet: string; detector: string; layer: Layer;
}

export interface SdkContext { ecosystem: Ecosystem; package: string; version: string | null; }
export interface ContractContext { sdk?: SdkContext; apiVersion?: string; }

export interface Contract {
  id: string; provider: string; kind: ContractKind; key: string; confidence: Confidence;
  evidence: Evidence[]; context?: ContractContext;
}

export interface Inventory {
  schemaVersion: "1";
  repo: RepoRef;
  scannedAt: string;
  engine: { name: string; version: string; knowledgeVersion: string };
  stats: { filesScanned: number; filesSkipped: number; durationMs: number; layers: Layer[] };
  contracts: Contract[];
}

export interface ManifestRule { ecosystem: Ecosystem; package: string; }
export interface LiteralRule {
  id: string; kind: ContractKind; files: string[]; exclude?: string[]; pattern: string; key: string; confidence: Confidence;
}
export interface CallsiteRule {
  id: string; language: Language; kind: ContractKind; requires: string; query: string; key: string;
  maps_to?: { kind: ContractKind; key: string }; confidence: Confidence;
}
export interface DetectorTable { provider: string; manifests: ManifestRule[]; literals: LiteralRule[]; callsites: CallsiteRule[]; }

export interface Affects { kind: ContractKind; match: string; }
export interface ChangeSource { kind: string; url?: string; observed: string; note?: string; }
export interface Migration { replacement: string | null; guide: string | null; effort: "small" | "medium" | "large"; notes?: string; }
export interface ChangeRecord {
  id: string; provider: string; kind: string; severity: Severity; title: string; summary: string;
  affects: Affects[]; announced: string; effective: string | null; sources: ChangeSource[];
  migration?: Migration; status: ChangeStatus;
}

export interface ProviderInfo {
  id: string; name: string; homepage?: string; docs?: string; changelog?: string; base_urls?: string[];
  sdk_languages?: string[]; deprecation_policy?: string;
}

export interface Provider { info: ProviderInfo; detectors: DetectorTable; changes: ChangeRecord[]; }
export interface Knowledge { version: string; providers: Provider[]; }

export interface Finding {
  id: string; contract: string; change: string; severity: Severity; effective: string | null;
  daysRemaining: number | null; evidence: Evidence[]; status: "open" | "snoozed" | "not_in_prod" | "not_affected" | "fixed";
  snoozedUntil: string | null; fixPr: string | null;
}

/** Loads a tree-sitter Language for a language slug. Injected so Node and the browser locate wasm differently. */
export type GrammarLoader = (language: Language | "tsx") => Promise<unknown>;
