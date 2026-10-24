// Specs whose assertions depend on the knowledge base's dates run with the browser clock pinned to
// one day. Without it a test that expects "gpt-4-turbo shut down" among the upcoming shutdowns
// starts failing the morning after that date, with no change to the code: nightly CI was red
// from 2026-10-17 for exactly that. Specs that build their own data relative to now do not need it.
import { test as base, expect } from "@playwright/test";

export const PINNED_TODAY = new Date("2026-10-01T09:00:00Z");

export const test = base.extend({
  page: async ({ page }, use) => {
    await page.clock.setFixedTime(PINNED_TODAY);
    await use(page);
  },
});

export { expect };
export type { Page, Route } from "@playwright/test";
