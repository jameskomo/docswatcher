// "Try it with your own assistant" on the Agents page, against mocked provider APIs: no real key,
// no real credit. What matters is the loop (the model asks check_api, the page answers from the
// engine, the model finishes) and that the key goes to the provider and nowhere else.
import { test, expect, type Page, type Route } from "@playwright/test";

const KEY = "test-key-123";

/** Every request that carries the key, by host. The key must never reach any other host. */
function keyHosts(page: Page) {
  const hosts = new Set<string>();
  page.on("request", (r) => {
    const h = r.headers();
    const carries = [h["authorization"], h["x-api-key"], r.url(), r.postData() ?? ""].some((v) => v?.includes(KEY));
    if (carries) hosts.add(new URL(r.url()).host);
  });
  return hosts;
}

const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({ status, contentType: "application/json", headers: { "access-control-allow-origin": "*" }, body: JSON.stringify(body) });

test("Claude checks the model with DocsWatcher, and the bare answer sits beside it", async ({ page }) => {
  const hosts = keyHosts(page);
  const bodies: any[] = [];
  await page.route("https://api.anthropic.com/v1/models**", (r) =>
    json(r, { data: [{ id: "claude-3-opus-20240229" }, { id: "claude-sonnet-test" }] }));
  await page.route("https://api.anthropic.com/v1/messages", (r) => {
    const body = r.request().postDataJSON();
    bodies.push(body);
    expect(r.request().headers()["anthropic-dangerous-direct-browser-access"]).toBe("true");
    if (!body.tools) return json(r, { stop_reason: "end_turn", content: [{ type: "text", text: "Use gpt-4-turbo." }] });
    const answered = body.messages.some((m: any) => Array.isArray(m.content) && m.content.some((c: any) => c.type === "tool_result"));
    if (!answered) {
      return json(r, { stop_reason: "tool_use", content: [{ type: "tool_use", id: "t1", name: "check_api", input: { value: "gpt-4-turbo" } }] });
    }
    return json(r, { stop_reason: "end_turn", content: [{ type: "text", text: "gpt-4-turbo is retiring; use the replacement." }] });
  });

  await page.goto("./#/agents");
  await page.getByTestId("try-key").fill(KEY);
  await page.getByTestId("try-key").blur();
  // The retired model is marked in the list, and not chosen by default.
  await expect(page.getByTestId("try-model")).toHaveValue("claude-sonnet-test");
  await expect(page.getByTestId("try-model").locator("option", { hasText: "claude-3-opus-20240229 (retired" })).toHaveCount(1);

  await page.getByTestId("try-run").click();
  const withDw = page.getByTestId("try-with");
  await expect(withDw).toContainText("gpt-4-turbo is retiring");
  await expect(withDw.getByTestId("try-calls")).toContainText('check_api("gpt-4-turbo")');
  await expect(withDw.getByTestId("try-calls")).toContainText("Retiring");
  await expect(page.getByTestId("try-without")).toContainText("Use gpt-4-turbo.");

  // The tool result the model read back is the engine's answer, the same text the CLI gives.
  const toolResult = bodies.flatMap((b) => b.messages).flatMap((m: any) => (Array.isArray(m.content) ? m.content : []))
    .find((c: any) => c.type === "tool_result");
  expect(toolResult.content).toMatch(/^RETIRING in \d+ days \(2026-10-23\): gpt-4-turbo/);
  expect([...hosts]).toEqual(["api.anthropic.com"]);
});

test("OpenAI runs the tool loop through the Responses API", async ({ page }) => {
  const hosts = keyHosts(page);
  await page.route("https://api.openai.com/v1/models", (r) =>
    json(r, { data: [{ id: "gpt-4-turbo", created: 3 }, { id: "gpt-5-codex-test", created: 2 }, { id: "whisper-1", created: 1 }] }));
  await page.route("https://api.openai.com/v1/responses", (r) => {
    const body = r.request().postDataJSON();
    if (!body.tools) return json(r, { id: "b", output: [{ type: "message", content: [{ type: "output_text", text: "bare" }] }] });
    if (!body.previous_response_id) {
      return json(r, { id: "r1", output: [{ type: "function_call", call_id: "c1", name: "check_api", arguments: '{"value":"dall-e-2"}' }] });
    }
    expect(body.input[0]).toMatchObject({ type: "function_call_output", call_id: "c1" });
    expect(body.input[0].output).toMatch(/^RETIRED/);
    return json(r, { id: "r2", output: [{ type: "message", content: [{ type: "output_text", text: "dall-e-2 is retired." }] }] });
  });

  await page.goto("./#/agents");
  await page.getByTestId("try-provider-openai").click();
  await page.getByTestId("try-key").fill(KEY);
  await page.getByTestId("try-key").blur();
  // Chat models only, and the one shutting down is not the default.
  await expect(page.getByTestId("try-model")).toHaveValue("gpt-5-codex-test");
  await expect(page.getByTestId("try-model").locator("option")).toHaveCount(2);
  await page.getByTestId("try-run").click();
  await expect(page.getByTestId("try-with")).toContainText("dall-e-2 is retired.");
  await expect(page.getByTestId("try-with").getByTestId("try-calls")).toContainText("Retired");
  await expect(page.getByTestId("try-without")).toContainText("bare");
  expect([...hosts]).toEqual(["api.openai.com"]);
});

test("Gemini runs the tool loop through Google's OpenAI-compatible endpoint", async ({ page }) => {
  const hosts = keyHosts(page);
  const G = "https://generativelanguage.googleapis.com/v1beta/openai";
  await page.route(`${G}/models`, (r) => json(r, { data: [{ id: "models/gemini-test-flash" }, { id: "models/text-embedding-004" }] }));
  await page.route(`${G}/chat/completions`, (r) => {
    const body = r.request().postDataJSON();
    if (!body.tools) return json(r, { choices: [{ message: { role: "assistant", content: "bare" } }] });
    const tool = body.messages.find((m: any) => m.role === "tool");
    if (!tool) {
      return json(r, { choices: [{ message: { role: "assistant", content: null,
        tool_calls: [{ id: "g1", type: "function", function: { name: "check_api", arguments: '{"value":"gemini-2.0-flash"}' } }] } }] });
    }
    expect(tool.content).toMatch(/^RETIRED/);
    return json(r, { choices: [{ message: { role: "assistant", content: "Use a current Gemini model." } }] });
  });

  await page.goto("./#/agents");
  await page.getByTestId("try-provider-gemini").click();
  await page.getByTestId("try-key").fill(KEY);
  await page.getByTestId("try-key").blur();
  await expect(page.getByTestId("try-model")).toHaveValue("gemini-test-flash");
  await page.getByTestId("try-run").click();
  await expect(page.getByTestId("try-with")).toContainText("Use a current Gemini model.");
  await expect(page.getByTestId("try-with").getByTestId("try-calls")).toContainText('check_api("gemini-2.0-flash")');
  expect([...hosts]).toEqual(["generativelanguage.googleapis.com"]);
});

test("a rejected key says so, and nothing is remembered unless asked", async ({ page }) => {
  await page.route("https://api.anthropic.com/v1/models**", (r) =>
    json(r, { type: "error", error: { type: "authentication_error", message: "invalid x-api-key" } }, 401));
  await page.goto("./#/agents");
  await page.getByTestId("try-key").fill(KEY);
  await page.getByTestId("try-key").blur();
  await expect(page.getByTestId("try-models-error")).toContainText("Anthropic rejected the key. invalid x-api-key");
  await expect(page.getByTestId("try-run")).toBeDisabled();
  expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain(KEY);
});
