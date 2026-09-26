import type { Finding, Severity } from "~~/engine/types";
import { normaliseRuntimeSummary, type CreatedToken, type IngestToken, type RuntimeSummary } from "./runtime";

/**
 * The organisation dashboard's view of the app's API. docs/adr/0008-sign-in-with-github.md and
 * docs/adr/0012-gitlab.md.
 *
 * Every call is same-origin and resolved against the page's own address, like the early-access
 * form, so the static site works wherever it is served. Authentication is the HttpOnly session
 * cookie the app set at sign-in; page script never sees it, only sends it.
 */

export type Provider = "github" | "gitlab";

export interface Me {
  /** Some sign-in exists on this deployment. */
  enabled: boolean;
  /** Which sign-ins exist. An app from before GitLab leaves it out, and then enabled means GitHub. */
  providers?: Partial<Record<Provider, boolean>>;
  signedIn: boolean;
  /** What the person signed in with. */
  provider?: Provider;
  user?: { login: string; name: string | null; avatarUrl: string | null };
  orgs?: { login: string; installationId: number; repos: number }[];
  expiresAt?: string;
}

export interface Overview {
  login: string;
  repos: number;
  contracts: number;
  findingsBySeverity: Partial<Record<Severity, number>>;
  nearestEffective: string | null;
  knowledgeVersion: string | null;
}

export interface RepoSummary {
  id: number;
  fullName: string;
  defaultBranch: string;
  lastScannedSha: string | null;
  production: boolean;
  openFindings: number;
  provider?: Provider;
}

export interface MapNode {
  provider: string;
  contracts: number;
  evidence: number;
  worstSeverity: Severity | "healthy";
  openFindings: number;
}

/** A finding as the server sends it: absent fields are omitted, not null. */
export type ServerFinding = Partial<Finding> & Pick<Finding, "id" | "contract" | "change" | "severity">;

export interface RepoFinding {
  repoId: number;
  repoFullName: string;
  finding: Finding;
  changeTitle: string;
}

export interface HorizonMonth { month: string; findings: RepoFinding[] }

export interface BlastRadius {
  changeId: string;
  title: string;
  effective: string | null;
  repos: number;
  findings: RepoFinding[];
}

export type FindingAction = "snooze" | "not-in-prod" | "fix";

export interface Ack { status: string; detail: unknown }

/** A connected GitLab group or project. webhookToken is sent once, when the connection is created. */
export interface GitLabConnection {
  id: number;
  login: string;
  kind: "group" | "project";
  namespace: string;
  projects: number;
  tokenExpiresAt: string | null;
  connectedBy: string;
  createdAt: string;
  webhookUrl: string;
  webhookToken?: string | null;
}

/** The sign-ins this deployment offers, in the order the site shows them. */
export function signInProviders(me: Me | null): Provider[] {
  if (!me?.enabled) return [];
  if (!me.providers) return ["github"];
  return (["github", "gitlab"] as const).filter((p) => me.providers![p]);
}

export const PROVIDER_NAME: Record<Provider, string> = { github: "GitHub", gitlab: "GitLab" };

/**
 * Raised for any non-2xx answer, carrying the status so the page can say what it means, and the
 * server's own explanation when it sent one ({"error": "..."}), which is shown as text.
 */
export class ApiError extends Error {
  constructor(public status: number, message: string, public detail: string | null = null) {
    super(message);
  }
}

/** An organisation's alert settings (docs/adr/0010-alerts-before-the-date.md). The Slack webhook itself is never sent back. */
export interface AlertSettings {
  login: string;
  enabled: boolean;
  emails: string[];
  slack: { configured: boolean; hint?: string | null };
  thresholds: number[];
  /** Whether this person may change them: write access to one of the organisation's repositories. */
  canEdit: boolean;
  /** Whether this deployment can send email at all. */
  emailAvailable: boolean;
  updatedBy?: string | null;
  updatedAt?: string | null;
}

/** What saving sends. `slackWebhook`: left out keeps the current one, "" removes it. */
export interface AlertUpdate {
  enabled: boolean;
  emails: string[];
  thresholds: number[];
  slackWebhook?: string;
}

export interface AlertTestResult { emails: number; slackMessages: number; failures: number }

/** Addresses typed one per line or separated by commas or spaces. */
export function splitEmails(text: string): string[] {
  return [...new Set(text.split(/[\s,;]+/).map((e) => e.trim()).filter(Boolean))];
}

/** "30, 7" to [30, 7]; anything that is not a whole number is dropped, and the server checks the rest. */
export function parseThresholds(text: string): number[] {
  return text.split(/[\s,;]+/).filter(Boolean).map(Number).filter((n) => Number.isInteger(n));
}

/** Fills in what the server leaves out, so components can rely on the engine's Finding shape. */
export function normaliseFinding(f: ServerFinding): Finding {
  return {
    id: f.id,
    contract: f.contract,
    change: f.change,
    severity: f.severity,
    effective: f.effective ?? null,
    daysRemaining: f.daysRemaining ?? null,
    evidence: f.evidence ?? [],
    status: f.status ?? "open",
    snoozedUntil: f.snoozedUntil ?? null,
    fixPr: f.fixPr ?? null,
  };
}

function normaliseRepoFinding(r: RepoFinding & { finding: ServerFinding }): RepoFinding {
  return { repoId: r.repoId, repoFullName: r.repoFullName, finding: normaliseFinding(r.finding), changeTitle: r.changeTitle };
}

/**
 * The same finding exists once per repository, with the same id in each. Views that key on id
 * (the horizon, lists) get an id unique across the organisation.
 */
export function orgFinding(r: RepoFinding): Finding {
  return { ...r.finding, id: `${r.repoId}:${r.finding.id}` };
}

/** What is still open across the horizon, in its order. */
export function openFindings(months: HorizonMonth[]): RepoFinding[] {
  return months.flatMap((m) => m.findings).filter((r) => r.finding.status === "open");
}

/** The changes that touch this organisation, nearest first, for the blast-radius picker. */
export function changesIn(months: HorizonMonth[]): { id: string; title: string; effective: string | null; repos: number }[] {
  const byId = new Map<string, { id: string; title: string; effective: string | null; repos: Set<number> }>();
  for (const r of months.flatMap((m) => m.findings)) {
    let c = byId.get(r.finding.change);
    if (!c) {
      c = { id: r.finding.change, title: r.changeTitle, effective: r.finding.effective, repos: new Set() };
      byId.set(c.id, c);
    }
    c.repos.add(r.repoId);
  }
  return [...byId.values()]
    .map((c) => ({ id: c.id, title: c.title, effective: c.effective, repos: c.repos.size }))
    .sort((a, b) => (a.effective ?? "9999") < (b.effective ?? "9999") ? -1 : (a.effective ?? "9999") > (b.effective ?? "9999") ? 1 : a.title.localeCompare(b.title));
}

export type Health = Severity | "healthy";

export interface ProviderRow {
  id: string;
  name: string;
  contracts: number;
  callSites: number;
  health: Health;
  split: Record<Health, number>;
}

const RANK: Record<Health, number> = { healthy: 0, info: 1, warning: 2, breaking: 3 };

/**
 * The provider map's rows for a whole organisation. The server gives per-provider totals and the
 * worst open severity; the split comes from the open findings, counting each repository's
 * contract once at its worst severity, and every other contract is healthy.
 */
export function providerRows(nodes: MapNode[], open: RepoFinding[], name: (id: string) => string = (id) => id): ProviderRow[] {
  const worst = new Map<string, Severity>();
  for (const r of open) {
    const key = `${r.repoId}|${r.finding.contract}`;
    const prev = worst.get(key);
    if (!prev || RANK[r.finding.severity] > RANK[prev]) worst.set(key, r.finding.severity);
  }
  const split = new Map<string, Record<Health, number>>();
  for (const [key, sev] of worst) {
    const contract = key.slice(key.indexOf("|") + 1);
    const provider = contract.slice(0, contract.indexOf(":"));
    const s = split.get(provider) ?? { breaking: 0, warning: 0, info: 0, healthy: 0 };
    s[sev]++;
    split.set(provider, s);
  }
  return nodes
    .map((n) => {
      const s = split.get(n.provider) ?? { breaking: 0, warning: 0, info: 0, healthy: 0 };
      s.healthy = Math.max(0, n.contracts - s.breaking - s.warning - s.info);
      return { id: n.provider, name: name(n.provider), contracts: n.contracts, callSites: n.evidence, health: n.worstSeverity, split: s };
    })
    .sort((a, b) => RANK[b.health] - RANK[a.health] || b.callSites - a.callSites || a.name.localeCompare(b.name));
}

/** Where the app lives: the directory the page was served from, without the hash route. */
export function siteBase(href: string): string {
  return href.split("#")[0].replace(/[^/]*$/, "");
}

export function createOrgApi(base: string, fetchImpl: typeof fetch = fetch) {
  async function call<T>(path: string, init?: RequestInit): Promise<T> {
    const res = await fetchImpl(new URL(path, base).toString(), {
      credentials: "same-origin",
      ...init,
      headers: { Accept: "application/json", ...(init?.body ? { "Content-Type": "application/json" } : {}), ...(init?.headers ?? {}) },
    });
    if (!res.ok) {
      // The app explains a refusal as {"error": ...} or {"message": ...}; either is shown as text.
      const body = await res.json().catch(() => null);
      const said = body && typeof body.error === "string" && body.error ? body.error
        : body && typeof body.message === "string" && body.message ? body.message : null;
      throw new ApiError(res.status, said ?? `${res.status} ${res.statusText}`.trim(), said);
    }
    if (res.status === 204) return undefined as T;
    return (await res.json()) as T;
  }
  const org = (login: string, rest: string) => `api/orgs/${encodeURIComponent(login)}/${rest}`;

  return {
    /** Signed out, or no app behind the site at all: both read as signed out, never as an error. */
    async me(): Promise<Me> {
      try {
        const me = await call<Me>("auth/me");
        return me && typeof me.signedIn === "boolean" ? me : { enabled: false, signedIn: false };
      } catch {
        return { enabled: false, signedIn: false };
      }
    },
    loginUrl: (provider: Provider = "github") => new URL(`auth/${provider}/login`, base).toString(),
    logout: () => call<void>("auth/logout", { method: "POST" }),
    overview: (login: string) => call<Overview>(org(login, "overview")),
    repos: (login: string) => call<RepoSummary[]>(org(login, "repos")),
    map: (login: string) => call<MapNode[]>(org(login, "map")),
    async horizon(login: string): Promise<HorizonMonth[]> {
      const months = await call<{ month: string; findings: (RepoFinding & { finding: ServerFinding })[] }[]>(org(login, "horizon"));
      return months.map((m) => ({ month: m.month, findings: m.findings.map(normaliseRepoFinding) }));
    },
    async blastRadius(login: string, changeId: string): Promise<BlastRadius> {
      const b = await call<BlastRadius & { findings: (RepoFinding & { finding: ServerFinding })[] }>(org(login, `blast-radius/${encodeURIComponent(changeId)}`));
      return { ...b, effective: b.effective ?? null, findings: b.findings.map(normaliseRepoFinding) };
    },
    async repoFindings(repoId: number): Promise<RepoFinding[]> {
      const list = await call<(RepoFinding & { finding: ServerFinding })[]>(`api/repos/${repoId}/findings`);
      return list.map(normaliseRepoFinding);
    },
    alerts: (login: string) => call<AlertSettings>(org(login, "alerts")),
    saveAlerts: (login: string, update: AlertUpdate) =>
      call<AlertSettings>(org(login, "alerts"), { method: "POST", body: JSON.stringify(update) }),
    testAlerts: (login: string) => call<AlertTestResult>(org(login, "alerts/test"), { method: "POST" }),
    /* Runtime observation: what production calls, and the tokens its telemetry is sent with. */
    runtimeSummary: async (repoId: number): Promise<RuntimeSummary> =>
      normaliseRuntimeSummary(await call(`api/repos/${repoId}/runtime/summary`)),
    ingestTokens: (repoId: number) => call<IngestToken[]>(`api/repos/${repoId}/runtime/tokens`),
    createIngestToken: (repoId: number, label: string) =>
      call<CreatedToken>(`api/repos/${repoId}/runtime/tokens`, { method: "POST", body: JSON.stringify({ label }) }),
    revokeIngestToken: (repoId: number, tokenId: number) =>
      call<void>(`api/repos/${repoId}/runtime/tokens/${tokenId}/revoke`, { method: "POST" }),
    /** Findings are named in the body: a contract id can hold a slash no path segment carries. */
    act: (repoId: number, action: FindingAction, f: Pick<Finding, "contract" | "change">, days?: number) =>
      call<Ack>(`api/repos/${repoId}/findings/${action}`, {
        method: "POST",
        body: JSON.stringify({ contract: f.contract, change: f.change, ...(days ? { days } : {}) }),
      }),
    gitlabConnections: () => call<GitLabConnection[]>("api/gitlab/connections"),
    /** The token travels once, in the body, to the app on the same origin; the page keeps no copy. */
    connectGitLab: (namespace: string, token: string) =>
      call<GitLabConnection>("api/gitlab/connections", { method: "POST", body: JSON.stringify({ namespace, token }) }),
    disconnectGitLab: (id: number) => call<void>(`api/gitlab/connections/${id}/disconnect`, { method: "POST" }),
  };
}

export type OrgApi = ReturnType<typeof createOrgApi>;
