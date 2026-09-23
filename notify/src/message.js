// Builds the raw email for one early-access request. Kept apart from the worker so it can be
// tested without the Cloudflare runtime.

const EMAIL = /^[^\s@<>",;]{1,64}@[^\s@<>",;]{1,188}\.[^\s@<>",;]{2,63}$/;
const FIELDS = [
  ["Company", "company"],
  ["Repositories", "repositories"],
  ["Providers", "providers"],
  ["Would use first", "interest"],
  ["Requests from this address", "requests"],
];

/** One line, no control characters: nothing a visitor typed can start a new header. */
const line = (v, max = 200) => String(v ?? "").replace(/[\u0000-\u001f\u007f]+/g, " ").trim().slice(0, max);

/** UTF-8 to base64 with web APIs only, so it runs the same in Workers and in Node. */
const base64 = (text) => btoa(Array.from(new TextEncoder().encode(text), (b) => String.fromCharCode(b)).join(""));

/** RFC 2047, so a company name in any script survives the Subject header. */
const encodeHeader = (text) => `=?UTF-8?B?${base64(text)}?=`;

export function compose(lead, { from, to, now = new Date(), id = crypto.randomUUID() }) {
  const email = line(lead.email, 254);
  const company = line(lead.company);
  const subject = `Early access: ${company || email || "new request"}`;
  const body = [
    `${email || "(no email)"} asked for early access to DocsWatcher for teams.`,
    "",
    ...FIELDS.map(([label, key]) => `${label}: ${line(lead[key]) || "-"}`),
    "",
    "Message:",
    String(lead.message ?? "").replace(/\r\n?/g, "\n").slice(0, 2000) || "-",
    "",
    "Reply to this email to answer them.",
  ].join("\n");

  const headers = [
    `From: DocsWatcher <${from}>`,
    `To: <${to}>`,
    ...(EMAIL.test(email) ? [`Reply-To: <${email}>`] : []),
    `Subject: ${encodeHeader(subject)}`,
    `Date: ${now.toUTCString()}`,
    `Message-ID: <${id}@${from.split("@")[1]}>`,
    "MIME-Version: 1.0",
    'Content-Type: text/plain; charset="utf-8"',
    "Content-Transfer-Encoding: base64",
  ];
  const encoded = base64(body).replace(/.{76}/g, "$&\r\n");
  return `${headers.join("\r\n")}\r\n\r\n${encoded}\r\n`;
}
