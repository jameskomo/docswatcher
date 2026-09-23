import { test } from "node:test";
import assert from "node:assert/strict";
import { compose } from "../src/message.js";

const opts = { from: "notify@example.org", to: "owner@example.net", now: new Date(0), id: "fixed" };
const decode = (raw) => {
  const [head, body] = raw.split("\r\n\r\n");
  return { head, body: Buffer.from(body.replace(/\r\n/g, ""), "base64").toString("utf8") };
};

test("the owner can reply straight to the lead, and sees every field", () => {
  const { head, body } = decode(compose(
    { email: "lead@acme.io", company: "Acme", repositories: "1 to 10", interest: "Alerts", message: "Hi\nthere", requests: 1 },
    opts));
  assert.match(head, /^From: DocsWatcher <notify@example.org>\r\nTo: <owner@example.net>\r\nReply-To: <lead@acme.io>\r\n/);
  assert.match(head, /Message-ID: <fixed@example.org>/);
  assert.match(body, /Company: Acme\nRepositories: 1 to 10\nProviders: -\nWould use first: Alerts/);
  assert.match(body, /Message:\nHi\nthere/);
});

test("a subject in any script is encoded", () => {
  const { head } = decode(compose({ email: "a@b.co", company: "Ñandú Ltd" }, opts));
  const subject = head.match(/Subject: =\?UTF-8\?B\?(.+)\?=/)[1];
  assert.equal(Buffer.from(subject, "base64").toString("utf8"), "Early access: Ñandú Ltd");
});

test("nothing a visitor types can add a header", () => {
  const { head } = decode(compose({ email: "a@b.co\r\nBcc: x@evil.test", company: "X\r\nBcc: y@evil.test" }, opts));
  assert.doesNotMatch(head, /^Bcc:/m);
  assert.doesNotMatch(head, /Reply-To/, "an address that is not a plain address is not used to reply to");
});
