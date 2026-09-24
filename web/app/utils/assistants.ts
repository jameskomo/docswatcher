// Bring your own key: the browser talks to the AI provider directly, so the key goes from the
// visitor's browser to Anthropic, OpenAI or Google and nowhere else. The check_api tool the model
// can call is answered here, in the page, by the same engine the CLI's MCP server uses.
import { checkApi, type CheckResult } from "~~/engine/checkApi";
import type { Knowledge } from "~~/engine/types";

export type AssistantId = "anthropic" | "openai" | "gemini";

export interface ToolCall {
  value: string;
  result?: CheckResult;
  error?: string;
}

export interface Answer {
  text: string;
  calls: ToolCall[];
}

export interface RunOptions {
  key: string;
  model: string;
  prompt: string;
  /** Give the model DocsWatcher's check_api tool, or ask it bare for comparison. */
  withDocsWatcher: boolean;
  knowledge: Knowledge;
  signal?: AbortSignal;
}

export interface Assistant {
  id: AssistantId;
  name: string;
  /** What the key looks like, for the input's placeholder. */
  keyHint: string;
  keyUrl: string;
  /** Model IDs the key can use, newest or most suitable first. */
  models(key: string, signal?: AbortSignal): Promise<string[]>;
  run(opts: RunOptions): Promise<Answer>;
  /** Tried in order when choosing the default model. */
  prefer: RegExp[];
}

/** A tool round trip is a request each; a runaway loop must not spend the visitor's credit. */
const MAX_ROUNDS = 6;
const MAX_TOKENS = 2048;

const TOOL = {
  name: "check_api",
  description:
    "Is this model ID, API endpoint, API version or SDK still safe to use? Returns RETIRED, RETIRING (with the " +
    "date and days left), CHANGED, or NO KNOWN DEPRECATION, plus the provider's replacement. Accepts loose input: " +
    "'gpt-4-turbo', 'openai/gpt-4-turbo', 'POST /v1/assistants', a full API URL, '2024-04', or 'openai==0.28'.",
  parameters: {
    type: "object",
    properties: { value: { type: "string", description: "The identifier as it would appear in code." } },
    required: ["value"],
  },
};

function instructions(knowledge: Knowledge): string {
  const names = knowledge.providers.map((p) => p.info.name).join(", ");
  return "DocsWatcher knows which third-party AI models, API endpoints, API versions and SDKs have a published " +
    "shutdown date. Your training data is older than that list. Before you write, suggest or keep a model ID, " +
    `endpoint, pinned API version or SDK for ${names}, call check_api with it. If the answer is RETIRED or ` +
    "RETIRING, use the replacement it gives and tell the user why.";
}

/** Runs one check_api call from a model; what the model reads back is the CLI's answer text. */
function answerTool(rawArgs: unknown, knowledge: Knowledge, calls: ToolCall[]): string {
  let value = "";
  try {
    const args = typeof rawArgs === "string" ? JSON.parse(rawArgs) : rawArgs;
    value = String((args as { value?: unknown })?.value ?? "");
    const result = checkApi(knowledge, { value }, new Date());
    calls.push({ value, result });
    return result.answer;
  } catch (e) {
    const error = e instanceof Error ? e.message : String(e);
    calls.push({ value, error });
    return `Error: ${error}`;
  }
}

async function post(url: string, headers: Record<string, string>, body: unknown, provider: string, signal?: AbortSignal) {
  return request(url, { method: "POST", headers: { "content-type": "application/json", ...headers }, body: JSON.stringify(body), signal }, provider);
}

async function request(url: string, init: RequestInit, provider: string): Promise<any> {
  let res: Response;
  try {
    res = await fetch(url, init);
  } catch (e) {
    if ((e as Error)?.name === "AbortError") throw e;
    throw new Error(`Could not reach ${provider} from this browser. Check your connection and try again.`);
  }
  const data = await res.json().catch(() => null);
  if (res.ok) return data;
  const detail = (Array.isArray(data) ? data[0] : data)?.error?.message;
  if (res.status === 401 || res.status === 403) throw new Error(`${provider} rejected the key.${detail ? ` ${detail}` : ""}`);
  throw new Error(`${provider} answered ${res.status}.${detail ? ` ${detail}` : ""}`);
}

function anthropicHeaders(key: string): Record<string, string> {
  return {
    "x-api-key": key,
    "anthropic-version": "2023-06-01",
    // Anthropic's opt-in for calls straight from a browser, which is the point here.
    "anthropic-dangerous-direct-browser-access": "true",
  };
}

const anthropic: Assistant = {
  id: "anthropic",
  name: "Claude",
  keyHint: "sk-ant-…",
  keyUrl: "https://console.anthropic.com/settings/keys",
  prefer: [/sonnet/, /opus/, /haiku/],
  async models(key, signal) {
    const data = await request("https://api.anthropic.com/v1/models?limit=100", { headers: anthropicHeaders(key), signal }, "Anthropic");
    return (data?.data ?? []).map((m: { id: string }) => m.id);
  },
  async run({ key, model, prompt, withDocsWatcher, knowledge, signal }) {
    const calls: ToolCall[] = [];
    const messages: any[] = [{ role: "user", content: prompt }];
    const extra = withDocsWatcher
      ? { system: instructions(knowledge), tools: [{ name: TOOL.name, description: TOOL.description, input_schema: TOOL.parameters }] }
      : {};
    for (let round = 0; round < MAX_ROUNDS; round++) {
      const res = await post("https://api.anthropic.com/v1/messages", anthropicHeaders(key),
        { model, max_tokens: MAX_TOKENS, messages, ...extra }, "Anthropic", signal);
      const uses = (res.content ?? []).filter((b: any) => b.type === "tool_use");
      if (res.stop_reason !== "tool_use" || uses.length === 0) {
        return { text: (res.content ?? []).filter((b: any) => b.type === "text").map((b: any) => b.text).join("\n\n"), calls };
      }
      messages.push({ role: "assistant", content: res.content });
      messages.push({
        role: "user",
        content: uses.map((u: any) => ({ type: "tool_result", tool_use_id: u.id, content: answerTool(u.input, knowledge, calls) })),
      });
    }
    throw new Error("Claude kept calling the tool without answering.");
  },
};

/** OpenAI's Responses API: the one that serves the Codex models as well as GPT. */
const openai: Assistant = {
  id: "openai",
  name: "OpenAI (GPT, Codex)",
  keyHint: "sk-…",
  keyUrl: "https://platform.openai.com/api-keys",
  prefer: [/codex/, /^gpt-5/, /^gpt-4\.1/, /^o\d/],
  async models(key, signal) {
    const data = await request("https://api.openai.com/v1/models", { headers: { authorization: `Bearer ${key}` }, signal }, "OpenAI");
    return (data?.data ?? [])
      .filter((m: { id: string }) => /^(gpt-|o\d|codex|chatgpt)/.test(m.id)
        && !/(audio|realtime|tts|transcribe|image|embedding|search|moderation|instruct)/.test(m.id))
      .sort((a: { created: number }, b: { created: number }) => b.created - a.created)
      .map((m: { id: string }) => m.id);
  },
  async run({ key, model, prompt, withDocsWatcher, knowledge, signal }) {
    const calls: ToolCall[] = [];
    const auth = { authorization: `Bearer ${key}` };
    const tools = withDocsWatcher ? { instructions: instructions(knowledge), tools: [{ type: "function", ...TOOL }] } : {};
    let res = await post("https://api.openai.com/v1/responses", auth,
      { model, input: prompt, max_output_tokens: MAX_TOKENS, ...tools }, "OpenAI", signal);
    for (let round = 0; round < MAX_ROUNDS; round++) {
      const fns = (res.output ?? []).filter((o: any) => o.type === "function_call");
      if (fns.length === 0) {
        const text = (res.output ?? []).filter((o: any) => o.type === "message")
          .flatMap((o: any) => o.content ?? []).filter((c: any) => c.type === "output_text").map((c: any) => c.text).join("\n\n");
        return { text, calls };
      }
      res = await post("https://api.openai.com/v1/responses", auth, {
        model, previous_response_id: res.id, max_output_tokens: MAX_TOKENS, ...tools,
        input: fns.map((f: any) => ({ type: "function_call_output", call_id: f.call_id, output: answerTool(f.arguments, knowledge, calls) })),
      }, "OpenAI", signal);
    }
    throw new Error("The model kept calling the tool without answering.");
  },
};

/** Gemini through Google's OpenAI-compatible endpoint, which browsers may call. */
const GEMINI = "https://generativelanguage.googleapis.com/v1beta/openai";
const gemini: Assistant = {
  id: "gemini",
  name: "Gemini",
  keyHint: "AIza…",
  keyUrl: "https://aistudio.google.com/app/apikey",
  prefer: [/flash(?!-lite)/, /pro/, /flash/],
  async models(key, signal) {
    const data = await request(`${GEMINI}/models`, { headers: { authorization: `Bearer ${key}` }, signal }, "Google");
    return (data?.data ?? [])
      .map((m: { id: string }) => m.id.replace(/^models\//, ""))
      .filter((id: string) => /^gemini/.test(id) && !/(embedding|image|tts|audio|live|vision)/.test(id));
  },
  async run({ key, model, prompt, withDocsWatcher, knowledge, signal }) {
    const calls: ToolCall[] = [];
    const auth = { authorization: `Bearer ${key}` };
    const messages: any[] = withDocsWatcher
      ? [{ role: "system", content: instructions(knowledge) }, { role: "user", content: prompt }]
      : [{ role: "user", content: prompt }];
    const tools = withDocsWatcher ? { tools: [{ type: "function", function: TOOL }] } : {};
    for (let round = 0; round < MAX_ROUNDS; round++) {
      const res = await post(`${GEMINI}/chat/completions`, auth, { model, messages, max_tokens: MAX_TOKENS, ...tools }, "Google", signal);
      const message = res.choices?.[0]?.message ?? {};
      const toolCalls = message.tool_calls ?? [];
      if (toolCalls.length === 0) return { text: message.content ?? "", calls };
      messages.push(message);
      for (const t of toolCalls) {
        messages.push({ role: "tool", tool_call_id: t.id, content: answerTool(t.function?.arguments, knowledge, calls) });
      }
    }
    throw new Error("Gemini kept calling the tool without answering.");
  },
};

export const ASSISTANTS: Assistant[] = [anthropic, openai, gemini];

/** The default model: the first preferred one DocsWatcher does not know to be going away. */
export function defaultModel(assistant: Assistant, models: string[], knowledge: Knowledge): string {
  const safe = models.filter((m) => checkApi(knowledge, { value: m, kind: "model" }, new Date()).verdict === "NO_KNOWN_DEPRECATION");
  for (const re of assistant.prefer) {
    const hit = safe.find((m) => re.test(m));
    if (hit) return hit;
  }
  return safe[0] ?? models[0] ?? "";
}
