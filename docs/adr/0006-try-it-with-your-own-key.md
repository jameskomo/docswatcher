# ADR 0006: "Try it" calls the AI provider from the browser, with the visitor's key

Date: 2026-09-24
Status: accepted

## Context

The Agents page shows a recorded exchange: an assistant asks DocsWatcher about a model and warns
the user. Visitors should be able to see that happen with their own assistant, on their own
prompt, next to the same model's answer without DocsWatcher.

That needs a model, and so a key. DocsWatcher earns nothing yet and must not pay for, or hold,
anyone's AI usage.

## Options

| Option | Cost to us | Key custody | Problem |
|---|---|---|---|
| Our key, a free demo | Every visitor's tokens | Ours | Unbounded cost, and abuse |
| The visitor's key, through our server | Nothing | Passes through us | We become responsible for keys in transit, and need a server path for it |
| **The visitor's key, browser to provider** | Nothing | Never reaches us | Only providers that allow browser calls |

## Decision

The browser calls the provider directly with the visitor's key. `check_api` is answered in the
page by `web/engine/checkApi.ts`, which gives the same answers as the CLI's MCP server; both are
held to `cli/src/test/resources/check-api-cases.json`.

Three providers, one adapter each (`web/app/utils/assistants.ts`):

- Claude: Anthropic Messages, with Anthropic's `anthropic-dangerous-direct-browser-access` opt-in.
- OpenAI: the Responses API, which serves the Codex models as well as GPT.
- Gemini: Google's OpenAI-compatible endpoint.

All three answered a CORS preflight from the site's origin on 2026-09-24. Others were left out to
keep the code small; Moonshot (Kimi) also refuses browser calls.

- The key is held in memory. It is stored in `localStorage`, per provider, only when the visitor
  ticks "Remember the key on this device".
- The Content-Security-Policy `connect-src` names the three API hosts and nothing new besides.
- Model output is rendered with text interpolation only, never as HTML.
- A run stops after six tool rounds, so a looping model cannot spend the visitor's credit.
- The model list is the key's own list, marked with DocsWatcher's verdict on each model; the
  default is never one DocsWatcher knows is going away.

## Consequences

- No server, no cost, no key custody. The site stays a static export.
- A cross-site script on this origin could read a key typed into the page. The site loads no
  third-party scripts and its policy allows scripts from itself only; that stays a rule.
- Comparing asks the model twice, billed to the visitor; the page says so.
