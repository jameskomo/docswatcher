<script setup lang="ts">
// Free for individuals; the team features are built, and early access is help and pricing. The form posts to the app server
// (docs/adr/0005-early-access-requests.md); if that fails, the visitor can still send an email.
const subject = "DocsWatcher for teams: early access";
const bodyText = [
  "Hi,",
  "",
  "We'd like early access to DocsWatcher for teams.",
  "",
  "Company:",
  "How many repositories:",
  "Which providers we depend on most (OpenAI, Stripe, ...):",
  "What we'd use first (dashboard, alerts, GitHub App, GitLab, runtime, our own APIs):",
  "",
].join("\n");
const inbox = "hello@vukisha.co.ke";
const mailto = `mailto:${inbox}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(bodyText)}`;

const interests = ["Organisation dashboard", "Alerts before the date", "GitHub App", "GitLab", "Runtime observation", "Our own APIs", "Help getting started"];
const form = reactive({ email: "", company: "", repositories: "", providers: "", interest: [] as string[], message: "", website: "" });
const state = ref<"idle" | "sending" | "sent" | "error">("idle");
const errorText = ref("");

async function submit() {
  state.value = "sending";
  errorText.value = "";
  try {
    // Relative to wherever the site is served, so it works under any host or subpath.
    const url = new URL("early-access", location.href.split("#")[0]).toString();
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...form, interest: form.interest.join(", ") }),
    });
    const data = await res.json().catch(() => ({}));
    if (res.ok && data.ok) { state.value = "sent"; return; }
    errorText.value = data.error ?? "That did not go through.";
  } catch {
    errorText.value = "That did not go through.";
  }
  state.value = "error";
}

const REPO = "https://github.com/jameskomo/docswatcher";
const DOCS = `${REPO}/blob/main/docs`;
type Where = { label: string; to?: string; href?: string };

const free: Array<{ what: string; where: string }> = [
  { what: "Scan a GitHub repository, a GitLab project or a local folder in your browser", where: "/" },
  { what: "Fail CI on a dated shutdown: GitHub Actions, GitLab CI, or any shell", where: "/ci" },
  { what: "Your AI coding assistant checks before it writes", where: "/agents" },
  { what: "Every shutdown date in your calendar, or by email", where: "/calendar" },
  { what: "The open knowledge base, and the CLI", where: REPO },
  { what: "Your own APIs' deprecations, found the same way", where: `${DOCS}/19-your-own-apis.md` },
];

// What teams get. Every card here is built; each says where it lives.
const team: Array<{ id: string; icon: "dashboard" | "bell" | "chart" | "sync" | "code"; title: string; text: string; links: Where[] }> = [
  {
    id: "dashboard", icon: "dashboard", title: "Every repository, one view",
    text: "Sign in with GitHub or GitLab. Findings across your organisation's repositories, and the blast radius of one shutdown: which repositories it breaks, and where. Snooze a finding, mark it as not running in production, or request a fix pull request (GitHub).",
    links: [{ label: "Open the dashboard", to: "/app" }],
  },
  {
    id: "alerts", icon: "bell", title: "Warned before the date",
    text: "Email or Slack when a repository calls something due to shut down, 30 and 7 days ahead by default, not the morning it fails. Set on the dashboard, with a test send.",
    links: [{ label: "Alert settings", to: "/app" }, { label: "How alerts work", href: `${DOCS}/adr/0010-alerts-before-the-date.md` }],
  },
  {
    id: "forges", icon: "sync", title: "GitHub App and GitLab",
    text: "Private code watched on every push. The GitHub App posts a check run and opens an issue per finding; a connected GitLab group gets the same issues and a commit status. Labels on the issue snooze a finding, or mark it not in production or not affected. Each scan runs in a process of its own, within file, size and time limits.",
    links: [{ label: "Connect a GitLab group", href: `${DOCS}/07-getting-started.md#connecting-gitlab` }, { label: "Label commands", href: `${DOCS}/08-features.md#label-commands` }],
  },
  {
    id: "runtime", icon: "chart", title: "What actually runs",
    text: "From the OpenTelemetry you already run: which deprecated calls production really makes, how often, and which findings it has never been seen making. Each repository has its own ingest tokens, made on the dashboard.",
    links: [{ label: "Set it up", to: "/teams#runtime" }, { label: "Runtime observation", href: `${DOCS}/13-runtime-observation.md` }],
  },
  {
    id: "own", icon: "code", title: "Your own APIs",
    text: "Record your internal services' deprecations once, in your organisation's .docswatcher repository, and every repository that calls them hears about it: in issues and check runs, CI and the assistant.",
    links: [{ label: "How to write them", href: `${DOCS}/19-your-own-apis.md` }],
  },
];

useHead({
  title: "DocsWatcher for teams",
  meta: [{ name: "description", content: "DocsWatcher is free for developers. Teams sign in with GitHub or GitLab for an organisation dashboard, alerts before shutdown dates, private repositories and runtime observation." }],
});
</script>

<template>
  <article class="teams">
    <section class="page">
      <div class="section-head">
        <h1>Free for developers. Built for teams too.</h1>
        <p class="lede">
          Everything that finds a dead API call stays free. The team features below are built: sign in
          on the dashboard with GitHub or GitLab, once your organisation has the GitHub App installed
          or a GitLab group connected. Early access is for teams that want a hand getting started, or
          to talk about pricing.
        </p>
      </div>
    </section>

    <div class="prose">
      <h2 style="margin-top: 0">Free, and staying free</h2>
      <ul class="free" data-testid="free-list">
        <li v-for="f in free" :key="f.what">
          <NuxtLink v-if="f.where.startsWith('/')" :to="f.where">{{ f.what }}</NuxtLink>
          <a v-else :href="f.where" target="_blank" rel="noopener">{{ f.what }}</a>
        </li>
      </ul>

      <h2>For teams, built today</h2>
      <div class="caps team-caps" data-testid="team-features">
        <div v-for="t in team" :key="t.id" class="cap" :data-testid="`team-${t.id}`">
          <h3><Icon :name="t.icon" :size="18" />{{ t.title }}</h3>
          <p>{{ t.text }}</p>
          <div class="cap-links">
            <template v-for="l in t.links" :key="l.label">
              <NuxtLink v-if="l.to" :to="l.to">{{ l.label }}</NuxtLink>
              <a v-else :href="l.href" target="_blank" rel="noopener">{{ l.label }}</a>
            </template>
          </div>
        </div>
      </div>

      <section class="start" id="start" data-testid="how-to-start">
        <h2>How to start</h2>
        <ol>
          <li>
            <strong>GitHub.</strong> An organisation owner installs the DocsWatcher GitHub App on the
            repositories to watch. If you do not have its install link, ask below and we will send it.
            To run your own, <a :href="`${REPO}/blob/main/app/README.md`" target="_blank" rel="noopener">app/README.md</a> has the steps.
          </li>
          <li>
            <strong>GitLab.</strong> Sign in with GitLab on the <NuxtLink to="/app">dashboard</NuxtLink>.
            A maintainer enters the group or project path and an access token, and adds the webhook it
            shows once. <a :href="`${DOCS}/07-getting-started.md#connecting-gitlab`" target="_blank" rel="noopener">Connecting GitLab</a>
          </li>
          <li>
            <strong>Sign in.</strong> The <NuxtLink to="/app">dashboard</NuxtLink> shows every repository
            you can see in that organisation. Set alerts there, and make ingest tokens for runtime
            observation. Where this deployment has no sign-in configured, the dashboard shows the last
            scan run in your browser instead.
          </li>
        </ol>
      </section>

      <section class="runtime" id="runtime" data-testid="runtime-guide">
        <h2>What actually runs: setting it up</h2>
        <p>
          Runtime observation reuses the OpenTelemetry you already run. There is no DocsWatcher
          library and no code change beyond naming two response headers: add one exporter to your
          Collector, and the dashboard shows which deprecated calls production really makes, how
          often, and which findings it has never been seen making.
        </p>
        <RuntimeSetup />
      </section>

      <section class="early" id="early-access" data-testid="early-access">
        <h2>Early access: a hand getting started, and pricing</h2>
        <p class="ink-soft">
          Tell us about your repositories and we will help you set up, send the GitHub App's install
          link, and talk about pricing for your team. The first teams get a founding price and a direct
          line to shape what gets built next. We only use this to reply to you.
        </p>

        <div v-if="state === 'sent'" class="callout done" role="status" data-testid="early-access-sent">
          <strong>Thank you.</strong> We'll reply from {{ inbox }}, so keep an eye out for it.
        </div>

        <form v-else class="form" @submit.prevent="submit" data-testid="early-access-form">
          <label>
            <span>Work email <em>required</em></span>
            <input class="input" type="email" v-model="form.email" required maxlength="254" autocomplete="email" name="email" />
          </label>
          <div class="pair">
            <label>
              <span>Company</span>
              <input class="input" v-model="form.company" maxlength="200" autocomplete="organization" name="company" />
            </label>
            <label>
              <span>Repositories</span>
              <select class="select" v-model="form.repositories" name="repositories">
                <option value="">Choose</option>
                <option>1 to 10</option>
                <option>10 to 50</option>
                <option>50 to 200</option>
                <option>More than 200</option>
              </select>
            </label>
          </div>
          <label>
            <span>Providers you depend on most</span>
            <input class="input" v-model="form.providers" maxlength="200" placeholder="OpenAI, Stripe, Shopify" name="providers" />
          </label>
          <fieldset>
            <legend>What would you use first?</legend>
            <label v-for="i in interests" :key="i" class="check">
              <input type="checkbox" :value="i" v-model="form.interest" /> {{ i }}
            </label>
          </fieldset>
          <label>
            <span>Anything else</span>
            <textarea class="input" v-model="form.message" maxlength="2000" rows="3" name="message"></textarea>
          </label>
          <!-- A field people never see. Bots fill it in; the server then stores nothing. -->
          <label class="trap" aria-hidden="true">
            Website <input v-model="form.website" tabindex="-1" autocomplete="off" name="website" />
          </label>

          <p v-if="state === 'error'" class="notice bad" role="alert" data-testid="early-access-error">
            {{ errorText }} You can also <a :href="mailto">email us directly</a>.
          </p>
          <div>
            <button class="btn solid" type="submit" :disabled="state === 'sending'" data-testid="early-access-submit">
              {{ state === "sending" ? "Sending" : "Request early access" }}
            </button>
          </div>
        </form>
      </section>
    </div>
  </article>
</template>

<style scoped>
/* Full width, like every page; running text keeps its measure from .prose. */
.teams h1 { font-size: clamp(1.75rem, 3.4vw, 2.5rem); line-height: 1.15; text-wrap: balance; }
.teams .lede { margin-top: var(--s3); }
.teams .prose { margin-top: var(--s5); }
.free { display: grid; gap: var(--s2); }
.team-caps { margin-top: var(--s3); }
.start ol { margin: var(--s3) 0 0; padding-left: 1.25em; max-width: var(--measure); }
.start li + li { margin-top: var(--s2); }
.runtime { margin-top: var(--s6); display: grid; gap: var(--s3); }
.runtime > * { min-width: 0; }
.runtime > p { max-width: var(--measure); }
.early { margin-top: var(--s6); }
.early > p { max-width: var(--measure); }
.form { display: grid; gap: var(--s3); margin-top: var(--s3); max-width: 560px; }
.form label, .form fieldset { display: grid; gap: 6px; }
.form label span, .form legend { font-size: var(--t2); font-weight: 600; color: var(--ink-max); }
.form em { font-style: normal; font-weight: 400; color: var(--ink-faint); margin-left: 6px; }
.form .input, .form .select { width: 100%; min-width: 0; }
.form textarea { resize: vertical; font: inherit; }
.form fieldset { border: 0; padding: 0; margin: 0; }
.form .check { display: flex; align-items: center; gap: 8px; font-size: var(--t2); color: var(--ink); }
.pair { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 200px), 1fr)); gap: var(--s3); }
.trap { position: absolute; left: -10000px; width: 1px; height: 1px; overflow: hidden; }
.done { margin-top: var(--s3); }
</style>
