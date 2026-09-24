import type { Evidence, RepoRef } from "~~/engine/types";
import { parseGitHubUrl, type RepoTarget } from "./fetchRepo";
import { GITLAB_HOST, parseGitLabUrl, type GitLabTarget } from "./gitlab";

/** What the URL field names: a GitHub repository or a GitLab project. */
export type ScanTarget = { host: "github"; repo: RepoTarget } | { host: "gitlab"; project: GitLabTarget };

/**
 * GitHub keeps the forms it always accepted. A first segment with a dot is a host, never a GitHub
 * owner (GitHub names cannot contain one), so gitlab.com/group/project is GitLab.
 */
export function parseScanInput(input: string): ScanTarget | null {
  const gh = parseGitHubUrl(input);
  if (gh && !gh.owner.includes(".")) return { host: "github", repo: gh };
  const gl = parseGitLabUrl(input);
  return gl ? { host: "gitlab", project: gl } : null;
}

/** How a target is named on screen: owner/name on GitHub, host and full path on GitLab. */
export function targetLabel(t: ScanTarget): string {
  return t.host === "github" ? `${t.repo.owner}/${t.repo.name}` : `${new URL(t.project.origin).host}/${t.project.path}`;
}

/**
 * A scanned GitLab project carries its instance's host name (gitlab.com, or a self-managed one)
 * in RepoRef.host, where a GitHub scan carries "github" and a sample or folder carries a word
 * without a dot.
 */
export function isGitLabRepo(r: RepoRef | null | undefined): r is RepoRef {
  return !!r && r.host !== "github" && GITLAB_HOST.test(r.host) && !!r.owner && !!r.name;
}

/**
 * The ?repo= value of a live scan link, or "" when the scan cannot be re-run from one. GitHub
 * links stay owner/name, exactly as before; GitLab links lead with the host, so
 * ?repo=gitlab.com/group/sub/project names both the instance and the nested path.
 */
export function shareParam(r: RepoRef | null | undefined): string {
  if (r && r.host === "github" && r.owner && r.name) return `${r.owner}/${r.name}`;
  if (isGitLabRepo(r)) return `${r.host}/${r.owner}/${r.name}`;
  return "";
}

const GITHUB_PARAM = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/;
const GITLAB_PARAM = /^[A-Za-z0-9.:-]+(?:\/[A-Za-z0-9_.][A-Za-z0-9_.-]*){2,}$/;

/**
 * Reads a ?repo= value. It arrives in a link anyone can craft, so only plain names are accepted:
 * no scheme, no query, no ref. A GitLab link always means https.
 */
export function parseShareParam(value: string): ScanTarget | null {
  const [owner = "", name = ""] = value.split("/");
  if (GITHUB_PARAM.test(value) && !owner.includes(".")) return { host: "github", repo: { owner, name, ref: null } };
  if (!GITLAB_PARAM.test(value)) return null;
  return parseScanInput(`https://${value}`);
}

/**
 * A permanent link to the exact line, or null when there is nothing to link to. Only a scan of
 * a public repository has one: a local folder or a bundled fixture does not, and linking those
 * would point at a repository the visitor never scanned. The sha pins the line to the commit
 * that was actually read, so the link cannot drift as the branch moves.
 */
export function evidenceUrl(r: RepoRef | null | undefined, e: Evidence): string | null {
  if (!r || !r.owner || !r.name) return null;
  const at = r.sha || r.ref?.replace(/^refs\/heads\//, "");
  if (!at) return null;
  const path = e.path.split("/").map(encodeURIComponent).join("/");
  if (r.host === "github") {
    return `https://github.com/${encodeURIComponent(r.owner)}/${encodeURIComponent(r.name)}/blob/${encodeURIComponent(at)}/${path}#L${e.line}`;
  }
  if (isGitLabRepo(r)) {
    const project = `${r.owner}/${r.name}`.split("/").map(encodeURIComponent).join("/");
    return `https://${r.host}/${project}/-/blob/${encodeURIComponent(at)}/${path}#L${e.line}`;
  }
  return null;
}
