# DocsWatcher for coding agents

Written 2026-09-23, before the code. The test plan at the end is the definition of done.

## The problem

A coding agent writes the model ID it remembers. Its memory is its training data, and training
data is months older than the deprecation list. Ask one for "a quick OpenAI call" today and it may
reach for `gpt-4-turbo`, which shuts down on 2026-10-23. The code compiles, the tests pass against
their mocks, and the agent reports success.

Scanning in CI catches this after the fact. The cheaper place to catch it is the moment the agent
types the string.

## What ships

`docswatcher mcp` runs the CLI as a [Model Context Protocol](https://modelcontextprotocol.io)
server over stdio. Any agent that speaks MCP (Claude Code, Cursor, Windsurf, VS Code, Zed, Codex)
can ask it three questions before writing code:

| Tool | Question it answers | Typical call |
|---|---|---|
| `check_api` | Is this model ID, endpoint, API version, SDK or GraphQL field still safe to use? | `{"value": "gpt-4-turbo"}` |
| `upcoming_deprecations` | What shuts down soon, optionally for one provider? | `{"provider": "openai", "within_days": 60}` |
| `scan_repository` | Which calls in this directory have a shutdown date? | `{"path": "."}` |

The server also sends one sentence of instructions at startup, which MCP clients pass to the model:
check an identifier with `check_api` before writing it. That sentence is the feature. The tools
are how the agent follows it.

### Install

```bash
# Claude Code
claude mcp add docswatcher -- docswatcher mcp

# Anything that reads an mcpServers block (Cursor, Windsurf, Claude Desktop)
{
  "mcpServers": {
    "docswatcher": { "command": "docswatcher", "args": ["mcp"] }
  }
}
```

The binary is the same one the GitHub Action downloads. There is nothing else to install.

## How it works

### Protocol

JSON-RPC 2.0, one message per line on stdin and stdout, as the MCP stdio transport specifies.
Logs go to stderr only, because anything else on stdout corrupts the stream.

| Method | Response |
|---|---|
| `initialize` | Server info, `tools` capability, instructions. Echoes the client's protocol version when it is one we support, otherwise our latest |
| `notifications/initialized` | Nothing (it is a notification) |
| `ping` | Empty result |
| `tools/list` | The three tools with JSON Schema inputs |
| `tools/call` | `content` with one text block, plus `structuredContent` with the same data as JSON |
| anything else | JSON-RPC error `-32601` |

A tool that is called correctly but finds a problem is not an error. `check_api` on a retired model
returns a normal result that says it is retired. `isError: true` is reserved for bad input, such as a
path that does not exist, so the agent can tell "you asked wrong" from "the answer is bad news".

No MCP library. The protocol surface we need is five methods, the CLI already carries Jackson,
and a dependency would have to survive the GraalVM native build. The server is one class.

### Matching

`check_api` does not have its own idea of what matches. It builds the same `(kind, key)` pair the
scanner would have extracted from source and runs it through `Matcher`, so a model ID is retired
in the agent's answer exactly when the scanner would flag it in CI.

The value an agent passes is looser than a scanned key, so the tool normalises it first:

| Input | Treated as |
|---|---|
| `gpt-4-turbo` | model `gpt-4-turbo` |
| `openai/gpt-4-turbo` (router style) | model `gpt-4-turbo`, provider `openai` |
| `/v1/assistants` | endpoint `ANY /v1/assistants` |
| `POST /v1/assistants` | endpoint `POST /v1/assistants` |
| `https://api.openai.com/v1/assistants` | endpoint `ANY /v1/assistants`, provider from the base URL |
| `2024-04` | API version `2024-04` |
| anything else | tried against every kind |

When `kind` is given it wins. When nothing matches, the answer says so and says what "nothing"
means: no *known* deprecation in a knowledge base of N records, dated D. That sentence is
deliberate. It is the same honesty the README keeps, and an agent that repeats it to a person is
repeating something true.

### What it returns

For each matching change: provider, title, status, effective date, days remaining, the replacement,
the migration guide link and the source URL. The text block leads with a verdict an agent can act
on without parsing:

```
RETIRING: gpt-4-turbo stops working on 2026-10-23, in 30 days (OpenAI). Use gpt-5.6-sol instead.
Guide: https://developers.openai.com/api/docs/deprecations
```

The verdict words are fixed: `RETIRED`, `RETIRING`, `CHANGED` (a warning with no date), and
`NO KNOWN DEPRECATION`.

### Safety

Read-only. The server never writes a file, never makes a network call and never runs a process.
The knowledge base is compiled into the binary, so an agent's question does not leave the machine.
`scan_repository` reads the directory the agent names, which is the same access the agent already
has, and caps its text answer so a large repository cannot flood the agent's context.

## Try it in the browser

The Agents page lets a visitor see the difference with their own assistant: pick Claude, OpenAI
(GPT and Codex) or Gemini, paste an API key, and ask for some code. The page asks the same model
twice, with and without a `check_api` tool, and shows both answers side by side with every check
the model made.

- The browser calls the provider directly. The key never reaches DocsWatcher, and nothing runs on
  our side, so it costs us nothing. See `docs/adr/0006-try-it-with-your-own-key.md`.
- `check_api` is answered in the page by `web/engine/checkApi.ts`. It gives the same answers as
  the MCP server: both are tested against `cli/src/test/resources/check-api-cases.json`.
- The model list comes from the visitor's own key. Models DocsWatcher knows are retiring are
  marked, and the default is the newest stable model with no known deprecation.
- Tested in `web/tests/e2e/try-assistant.spec.ts` against mocked provider APIs, including that the
  key is sent to the chosen provider and nowhere else.

## Rejected alternatives

- **A hosted MCP server.** Costs money per call and sends the agent's code context to us.
- **An editor extension.** One per editor, each with its own release process. MCP reaches all of
  them with one binary.
- **A rule in the agent's instructions file ("never use deprecated models").** The agent cannot
  follow a rule about facts it does not have. The server gives it the facts.

## Test plan

| Test | Proves |
|---|---|
| `initialize` returns protocol version, tools capability and instructions | Clients can connect |
| Unsupported protocol version gets our latest back | Newer clients still connect |
| `tools/list` lists exactly three tools, each with an object input schema | Discovery works |
| `check_api` on a retired model says `RETIRED` with a replacement | The main path |
| `check_api` on a model retiring in the future says `RETIRING` with days remaining | Dates are computed from `--today` |
| `check_api` on `openai/gpt-4-turbo` and on a full API URL finds the same change | Normalisation |
| `check_api` on an unknown value says `NO KNOWN DEPRECATION` and names the KB size | Honest empty answer |
| `upcoming_deprecations` respects `provider` and `within_days` and is sorted by date | Filtering |
| `scan_repository` on a fixture returns that fixture's expected findings | Same answer as `match` |
| `scan_repository` on a missing path returns `isError: true` | Bad input is an error, bad news is not |
| Unknown method gets `-32601`; malformed JSON gets `-32700` | Protocol hygiene |
| Notifications get no response | Stream stays in step |
| Nothing but JSON-RPC is written to stdout | Transport is not corrupted |
| Release smoke test pipes `initialize` + `tools/call` into the native binary | The shipped artefact works, not just the JVM build |
| `check_api` answers every case in `check-api-cases.json` exactly, in Java and in TypeScript | The browser's Try it and the MCP server agree |
