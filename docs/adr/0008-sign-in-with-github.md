# ADR 0008: Sign in with GitHub, and what a signed-in person may see

Date: 2026-09-24
Status: accepted

## Context

The Teams page promises "every repository, one view": a dashboard across an organisation and the
blast radius of one shutdown. The app already computes both (`/api/orgs/{login}/...`), but only
behind one shared bearer token. That token belongs to the deployment's owner. It cannot be handed
to the people in a customer's organisation, and it sees every installation.

People need to sign in as themselves, and see only what they are entitled to. Findings carry
evidence: file paths and the matched source line. Showing a finding from a private repository to
someone who cannot open that repository leaks code, even when both are in the same organisation.

## Options

### Who says what a person may see

| Option | Problem |
|---|---|
| Organisation membership (`/user/memberships/orgs`) | Members of an organisation do not all see every repository. Too coarse; leaks evidence from private repositories. |
| Our own user and role tables | A second permission system to keep in step with GitHub, and an admin screen to manage it. |
| **GitHub's answer for this App** | `GET /user/installations` and `GET /user/installations/{id}/repositories` with the person's user token list exactly the installations of this App they can reach and, inside each, the repositories they can see, with their permission on each. |

### Where the session lives

| Option | For | Against |
|---|---|---|
| Signed stateless token in the cookie | No table | Cannot be revoked before expiry without a revocation list; a signing key to manage and rotate; the access snapshot (possibly thousands of repository ids) rides in every request. |
| **A row in Postgres, keyed by the hash of a random cookie value** | Signing out ends it at once; nothing to sign or rotate; the cookie is 43 characters; the app already runs one Postgres | One indexed lookup per API request |

## Decision

1. **The GitHub App's own OAuth client.** `/auth/github/login` redirects to GitHub with a random
   `state` and a PKCE `S256` challenge. Both the state and the verifier sit in a short-lived cookie
   (`__Host-docswatcher_oauth`, ten minutes). `/auth/github/callback` refuses any request whose
   state does not match that cookie, then exchanges the code, sending the verifier and the client
   secret. Every redirect goes to a fixed page (`{origin}/#/app`), never to a URL taken from the
   request.
2. **Access is GitHub's answer, taken once.** At sign-in the callback asks GitHub who the person
   is, which installations of this App they can reach, and which repositories inside each one.
   Installations this deployment has never received a webhook for are ignored. Within the rest,
   only stored repositories are kept, each with the person's highest permission (admin, maintain,
   write, triage or read). That snapshot is the session's authorisation.
3. **The user token is dropped.** It is used during the callback and never stored. The session
   lasts 8 hours (`docswatcher.github.session-ttl`), which matches GitHub's user-token lifetime.
   Access granted or revoked on GitHub takes effect at the next sign-in.
4. **Sessions live in Postgres** (`user_session`). The cookie `__Host-docswatcher_session` holds
   256 random bits. The table holds only their SHA-256, so a copy of the database contains no
   usable session. The cookie is HttpOnly, Secure and SameSite=Lax, and the `__Host-` prefix pins
   it to the exact host over HTTPS. Signing in again replaces any previous session. Signing out
   (`POST /auth/logout`) deletes the row. Expired rows are purged at each sign-in.
5. **Two principals, never mixed.** `ApiTokenFilter` treats the bearer token as the **owner**,
   which sees everything, as before. A request that presents the token is judged on the token
   alone. Without a token, a valid session makes the caller a **member**. Neither: 401.
6. **Per-organisation and per-repository checks after routing.** `AccessInterceptor` reads the
   path variables Spring resolved, so there is no second parser to disagree with the router. A
   member reaches only handlers marked `@MemberAccess`, which makes every new endpoint
   owner-only until someone decides otherwise. `{login}` must be an organisation the member can
   see, and `{repoId}` a repository they can read. Otherwise the answer is 404, which does not
   confirm the organisation or repository exists. Organisation-wide answers (overview, repos,
   map, horizon, blast radius) are computed from the member's repositories only.
7. **Acting needs write access.** Snooze, not-in-production, fix and rescan require write,
   maintain or admin on the repository. That is the same bar the issue-label path applies
   (`WebhookService`), because these actions change shared state or spend the installation's
   write authority. A member's state-changing request must also carry an `Origin` equal to
   `DOCSWATCHER_WEB_ORIGIN`, or `Sec-Fetch-Site: same-origin`. That check stands on its own and
   does not rely on SameSite.
8. **Same origin.** nginx routes `/auth/` and `/api/` on the site's host to the app, so the
   browser sends the cookie with no CORS credentials. The callback URL is
   `{DOCSWATCHER_WEB_ORIGIN}/auth/github/callback`.

## Consequences

- The owner's token and any automation built on it work unchanged.
- A person sees exactly what GitHub lets them see. DocsWatcher keeps no user or role tables.
- The access snapshot can be up to 8 hours out of date. That applies to repositories added to an
  installation, installations created, and access revoked. Signing in again refreshes it. Removing
  an installation deletes its repositories, so they disappear from a live session at once.
- Contract ids can contain a slash (`stripe:endpoint:POST /v1/sources`), which no path segment can
  carry through Tomcat. So the dashboard names findings in the body:
  `POST /api/repos/{repoId}/findings/{snooze|not-in-prod|fix}`. The three-segment routes remain.
- The owner must generate the App's client secret and register the callback URL. The static
  site, served anywhere without the app, still works. `/auth/me` is absent there, and the
  dashboard then offers the browser scan without a sign-in button.
