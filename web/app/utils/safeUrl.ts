/**
 * Knowledge-base URLs come from a public, community-editable repository: `migration.guide`,
 * `sources[].url` and a provider's `info.changelog` are free text that a merged pull request
 * can set to anything. Binding them straight to `:href` means a record could carry a
 * `javascript:` URL into the page, and the deployed CSP does not stop one because `script-src`
 * carries 'unsafe-inline' for Nuxt's own bootstrap.
 *
 * Only http and https survive this. Anything else, including a URL that will not parse,
 * becomes null so the caller renders plain text instead of a link.
 */
export function safeUrl(raw: string | null | undefined): string | null {
  if (!raw) return null;
  try {
    const u = new URL(raw, "https://docswatcher.invalid");
    return u.protocol === "http:" || u.protocol === "https:" ? u.href : null;
  } catch {
    return null;
  }
}
