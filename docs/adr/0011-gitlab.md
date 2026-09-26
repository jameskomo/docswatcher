# ADR 0011: GitLab, beside the GitHub App

Date: 2026-09-26
Status: accepted

## Context

The site already scans GitLab projects in the browser, and a CI template runs the CLI in GitLab
pipelines. The server app did not know GitLab. A team on GitLab could not get what a GitHub team
gets from the App: a scan on every push, an issue per finding, a verdict on the commit, and the
organisation dashboard.

GitLab has no counterpart of a GitHub App. Nothing is "installed" on a group. Nothing hands out
short-lived per-installation tokens, and webhook bodies are not signed. So each part of the GitHub
design needs a GitLab counterpart:

| GitHub App | Needed on GitLab |
|---|---|
| Installation, and a token minted for it | Access to clone and post back |
| `X-Hub-Signature-256` over the body | A way to authenticate a webhook |
| Check run | A verdict on the commit |
| Sign-in with the App's OAuth client (ADR 0008) | Sign-in, and what a person may see |

## Options

### How DocsWatcher gets API access to a project

| Option | For | Against |
|---|---|---|
| The signing-in person's OAuth token, with its refresh token | No extra step for the maintainer | Stores a long-lived credential for every user. Every sign-in needs the `api` scope, which is full write access to everything the person can reach. Issues are authored by that person. The integration breaks when that person leaves or loses access. |
| A personal access token | Simple | Tied to one person, with the same problems as above |
| **A group or project access token, which a maintainer creates for DocsWatcher** | A bot user scoped to one namespace, with a role and an expiry the maintainer picks. GitLab can revoke it without touching anyone's account. Issues and statuses appear under the bot. Creating one needs the Maintainer role, so the token itself proves authority over the namespace. | The maintainer creates it and must replace it before it expires. Group access tokens need a paid tier on gitlab.com. On Free, a project access token per project, or a personal token. |

### How the token is stored

A token DocsWatcher only had to check could be stored as a hash. The access token is presented to
GitLab on every clone and API call, so it has to come back out. It is therefore encrypted:
AES-256-GCM, a fresh nonce per token, and the connection's id as associated data, so a
ciphertext copied onto another row does not decrypt. The key is 32 random bytes in the secret file
`docswatcher.gitlab.token-key`, which is never in the database. Without the key, no namespace can
be connected and no connected one can be scanned. The token is never stored in plain text.

The webhook token only has to be checked, so only its SHA-256 is stored.

### Authenticating webhooks

GitLab does not sign the body. It sends back the secret token the hook was configured with, in
`X-Gitlab-Token`. DocsWatcher generates a 256-bit token per connection and shows it once. It looks
the connection up by the token's SHA-256, then compares the stored hash again in constant time.

## Decision

1. **Connections.** A maintainer enters a namespace path (group, subgroup or project) and an
   access token on the dashboard (`POST /api/gitlab/connections`). DocsWatcher asks GitLab whose
   token it is (`GET /personal_access_tokens/self`). The token must be active and carry the `api`
   scope. GitLab must also say its user has Maintainer or above on the namespace
   (`/members/all/:id`). Otherwise the connection is refused. Connecting the same namespace again
   replaces the token and keeps the webhook. The answer to a new connection carries the webhook
   URL and secret token, once.
2. **One data model.** A connection is an `installation` row, and each project a `repo` row, both
   marked `provider = 'gitlab'`. Their ids come from `gitlab_id_seq`, which counts down from -1,
   so they never collide with GitHub's positive ids. Contracts, findings, scan runs, the
   reconciler, runtime rows and every dashboard query work unchanged. `gitlab_connection` holds the
   namespace, the encrypted token and the webhook token's hash. `gitlab_project` maps a GitLab
   project id to its repo, once: a project reachable through a group and one of its subgroups
   belongs to the connection that found it first. The dashboard groups a connection under its
   top-level group, the way GitLab does.
3. **A small Forge seam.** The scan used to call `GitHubClient` directly. It now calls a `Forge`,
   which does five things: clone source, report the scan, open an issue, close an issue, and give
   a blob address. `GitHubForge` passes each call to `GitHubClient` exactly as before.
   `GitLabForge` uses the connection's token. Scanning, the scan limits, the own-records
   behaviour, `FindingReconciler` and `IssueText` are shared. The issue body takes the forge's
   blob address and leaves out the fix pull request where there is no fix dispatch.
4. **The verdict is a commit status** named DocsWatcher (`POST /projects/:id/statuses/:sha`). It
   shows on the commit and on any merge request whose head it is. GitLab has no neutral state. So
   GitHub's neutral (open findings that are not breaking, or an incomplete scan) becomes `success`,
   and the description says what is open. A neutral check run does not block a GitHub merge
   either. `failure` becomes `failed`. The description is the check title, cut to GitLab's 255
   characters, and it links to the dashboard.
5. **Webhooks** (`POST /webhooks/gitlab`, Push events and Issues events). A token that matches no
   connection gets 401. The sending connection's namespace bounds everything the event touches. A
   stored project counts only if that connection holds it or its stored path lies in the
   namespace. The payload's path is never the test, because a payload can name any path. A push to
   the default branch queues a scan of that commit. A project the group gained after it was
   connected is looked up with the connection's token and adopted only if GitLab places it in the
   namespace. A rename the payload reports is taken from GitLab, not the payload. Retries are
   de-duplicated on `Idempotency-Key` (GitLab 17.4 and later), or else on `X-Gitlab-Event-UUID`.
   Issue close, reopen and the snooze, not-in-prod and not-affected labels work as on GitHub, for
   Developer and above. DocsWatcher's own closes arrive as the token's bot user and are ignored.
6. **Sign-in with GitLab.** A GitLab OAuth application (`docswatcher.gitlab.client-id`, with the
   secret in the file `docswatcher.gitlab.client-secret`) on the configured instance
   (`docswatcher.gitlab.base-url`, gitlab.com by default). The flow is ADR 0008's: state and a PKCE
   S256 verifier in a `__Host-` cookie of its own, a fixed redirect back, and the `read_api` scope
   only. At the callback, GitLab lists the person's projects at Reporter, Developer and Maintainer
   (`GET /projects?membership=true&min_access_level=…`). Among connected projects, those become
   read, write and maintain in the session. Guests are left out because they cannot read a
   private project's code, and a finding's evidence is that code. The OAuth token is then
   dropped, as on GitHub. The session records its provider.
7. **Removing a connection** needs the owner's token, or a GitLab session whose user GitLab names a
   Maintainer of the namespace, asked with the connection's own token. Anyone else gets 404.

## Consequences

- GitHub behaves exactly as before: its routes, sign-in, check runs, issues and fix dispatch are
  unchanged, and `RepoStore.findByFullName` without an installation still resolves GitHub
  repositories only.
- One GitLab instance per deployment: sign-in, clones and API calls all go to
  `docswatcher.gitlab.base-url`. Changing it strands existing connections.
- There is no fix pull request on GitLab yet. `repository_dispatch` has no counterpart; a pipeline
  trigger could be one. A fix request on a GitLab project answers 409.
- A group and a GitHub organisation with the same name share one organisation view for the owner.
  A member only ever sees the repositories their own sign-in lists.
- Default-branch scans have no merge request to comment on, so findings are issues, and the commit
  status is what merge requests show. The check-run summary is not posted anywhere on GitLab: the
  status description, the issues and the dashboard carry it.
- Access tokens expire. The dashboard shows each connection's expiry date. A scan with a dead
  token fails its run with GitLab's error, and connecting again with a new token repairs it.
- New projects in a connected group arrive with their first push, or the next time the group is
  connected. On GitLab Free, groups have no webhooks, so each project needs the hook.
- Runtime observations are still matched to GitHub repositories only.
