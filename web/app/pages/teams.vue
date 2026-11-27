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
  { what: "Scan a repository or folder in your browser", where: "/" },
  { what: "Fail CI on a dated shutdown", where: "/ci" },
  { what: "Your AI assistant checks before it writes", where: "/agents" },
  { what: "Every date in your calendar, or by email", where: "/calendar" },
  { what: "The open knowledge base and the CLI", where: REPO },
  { what: "Your own APIs, found the same way", where: `${DOCS}/19-your-own-apis.md` },
];

// What teams get. Every card here is built; each says where it lives.
const team: Array<{ id: string; icon: "dashboard" | "bell" | "chart" | "sync" | "code"; title: string; text: string; links: Where[] }> = [
  {
    id: "dashboard", icon: "dashboard", title: "Every repository, one view",
    text: "Sign in with GitHub or GitLab. Every repository's findings, and the blast radius of one shutdown: what it breaks, and where. Snooze, mark not in production, or ask for a fix pull request (GitHub).",
    links: [{ label: "Open the dashboard", to: "/app" }],
  },
  {
    id: "alerts", icon: "bell", title: "Warned before the date",
    text: "Email or Slack 30 and 7 days before a repository's call stops working, not the morning it fails. Set on the dashboard, with a test send.",
    links: [{ label: "Alert settings", to: "/app" }, { label: "How alerts work", href: `${DOCS}/adr/0010-alerts-before-the-date.md` }],
  },
  {
    id: "forges", icon: "sync", title: "GitHub App and GitLab",
    text: "Private code watched on every push: an issue per finding, plus a check run on GitHub or a commit status on GitLab. Labels on the issue snooze or dismiss it.",
    links: [{ label: "Connect a GitLab group", href: `${DOCS}/07-getting-started.md#connecting-gitlab` }, { label: "Label commands", href: `${DOCS}/08-features.md#label-commands` }],
  },
  {
    id: "runtime", icon: "chart", title: "What actually runs",
    text: "From the OpenTelemetry you already run: which deprecated calls production really makes, and how often. Your Collector sends with an upload-only token that cannot read anything back.",
    links: [{ label: "Set it up", to: "/teams#runtime" }, { label: "Runtime observation", href: `${DOCS}/13-runtime-observation.md` }],
  },
  {
    id: "own", icon: "code", title: "Your own APIs",
    text: "Record your internal APIs' deprecations once; every repository that calls them hears about it, in issues, CI and the assistant.",
    links: [{ label: "How to write them", href: `${DOCS}/19-your-own-apis.md` }],
  },
];

useHead({
  title: "DocsWatcher for teams",
  meta: [{ name: "description", content: "DocsWatcher is free for developers. Teams sign in with GitHub or GitLab for an organisation dashboard, alerts before shutdown dates, private repositories and runtime observation." }],
});

/** The router owns the URL hash, so in-page buttons scroll by hand instead of navigating. */
function go(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
}
</script>

<template>
  <article class="teams">
    <section class="doc-opening">
      <h1>Free for developers. Built for teams too.</h1>
      <p class="lede">
        Finding a dead API call stays free. Teams add <strong>one view across every repository</strong>, alerts
        before the date, and a scan on every push. All of it is built and running today.
      </p>
      <div class="doc-actions">
        <a class="btn solid" href="https://github.com/apps/docswatcher/installations/new" target="_blank" rel="noopener">Install the GitHub App</a>
        <button type="button" class="btn" @click="go('early-access')">Request early access</button>
      </div>
    </section>

    <div class="prose doc-body">
      <section class="doc-chapter">
        <div class="split">
          <div class="free-col">
            <h2>Free, always</h2>
            <ul class="free" data-testid="free-list">
              <li v-for="f in free" :key="f.what">
                <NuxtLink v-if="f.where.startsWith('/')" :to="f.where"><Icon name="check" :size="16" class="free-icon" />{{ f.what }}</NuxtLink>
                <a v-else :href="f.where" target="_blank" rel="noopener"><Icon name="check" :size="16" class="free-icon" />{{ f.what }}</a>
              </li>
            </ul>
          </div>
          <div class="team-col">
            <h2>For teams, built today</h2>
            <div class="caps team-caps" data-testid="team-features">
              <div v-for="t in team" :key="t.id" class="cap" :data-testid="`team-${t.id}`">
                <h3>{{ t.title }}</h3>
                <p>{{ t.text }}</p>
                <div class="cap-links">
                  <template v-for="l in t.links" :key="l.label">
                    <NuxtLink v-if="l.to" :to="l.to">{{ l.label }}</NuxtLink>
                    <a v-else :href="l.href" target="_blank" rel="noopener">{{ l.label }}</a>
                  </template>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section class="doc-chapter" id="start" data-testid="how-to-start">
        <h2>How to start</h2>
        <ol class="doc-steps">
          <li>
            <span class="step" aria-hidden="true">1</span>
            <div class="step-body">
              <h3>Connect your code</h3>
              <p>
                <strong>GitHub:</strong> an organisation owner
                <a href="https://github.com/apps/docswatcher/installations/new" target="_blank" rel="noopener" data-testid="install-app">installs the DocsWatcher GitHub App</a>
                on the repositories to watch.
              </p>
              <p>
                <strong>GitLab:</strong> sign in with GitLab on the <NuxtLink to="/app">dashboard</NuxtLink>, then a
                maintainer enters the group path and an access token and adds the webhook it shows once.
                <a :href="`${DOCS}/07-getting-started.md#connecting-gitlab`" target="_blank" rel="noopener">Connecting GitLab</a>
              </p>
            </div>
          </li>
          <li>
            <span class="step" aria-hidden="true">2</span>
            <div class="step-body">
              <h3>Sign in</h3>
              <p>
                The <NuxtLink to="/app">dashboard</NuxtLink> shows every repository you can see in the organisation.
                Set alerts there, and make ingest tokens for runtime observation.
              </p>
              <p class="doc-hint">
                Running your own? <a :href="`${REPO}/blob/main/app/README.md`" target="_blank" rel="noopener">app/README.md</a> has the steps.
                Without sign-in configured, the dashboard shows the last scan run in your browser.
              </p>
            </div>
          </li>
        </ol>
      </section>

      <section class="doc-chapter runtime" id="runtime" data-testid="runtime-guide">
        <h2>What actually runs: setting it up</h2>
        <p>
          It reuses the OpenTelemetry you already run. <strong>No DocsWatcher library, no code change</strong> beyond
          naming two response headers: add one exporter to your Collector and the dashboard shows which deprecated
          calls production really makes, and how often.
        </p>
        <RuntimeSetup />
      </section>

      <section class="doc-chapter" id="early-access" data-testid="early-access">
        <div class="early">
          <div class="early-pitch">
            <h2>Early access: a hand getting started, and pricing</h2>
            <p>
              Tell us about your repositories. We'll help you set up and talk about pricing for your team.
            </p>
            <ul class="facts">
              <li><strong>A founding price</strong> for the first teams.</li>
              <li><strong>A direct line</strong> to shape what gets built next.</li>
              <li>We only use this to reply to you.</li>
            </ul>
          </div>

          <div class="early-form">
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
                <div class="checks">
                  <label v-for="i in interests" :key="i" class="check">
                    <input type="checkbox" :value="i" v-model="form.interest" /> {{ i }}
                  </label>
                </div>
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
          </div>
        </div>
      </section>
    </div>
  </article>
</template>

<style scoped>
/* Layout comes from the shared doc-* classes in main.css; what is here is this page's own. */
.split { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 2.4fr); gap: 32px 56px; align-items: start; }
.split h2 { font-size: clamp(1.375rem, 2.2vw, 1.75rem); margin: 0 0 16px; }
.free { list-style: none; margin: 0; padding: 0; border-top: 1.5px solid var(--rule); }
.free li { margin: 0; border-bottom: 1px solid var(--hair); }
.free a { display: flex; align-items: flex-start; gap: 10px; padding: 12px 0; text-decoration: none; }
.free-icon { flex: none; margin-top: 0.2em; color: var(--ok); }
.free a:hover { text-decoration: underline; }
.team-caps { grid-template-columns: repeat(auto-fit, minmax(min(100%, 280px), 1fr)); }
.team-caps .cap h3 { font-size: 1.25rem; }
.facts { list-style: none; padding: 0; margin: 0; display: grid; gap: 6px; }
.facts li { position: relative; padding-left: 20px; margin: 0; }
/* A drawn marker, not a glyph standing in for an icon. */
.facts li::before { content: ""; position: absolute; left: 2px; top: 0.7em; width: 6px; height: 6px; background: var(--ink-faint); }

.early { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.3fr); gap: 32px 64px; align-items: start; border-top: 1.5px solid var(--rule); padding-top: 28px; }
.early-pitch { display: flex; flex-direction: column; gap: 16px; }
.early-pitch h2 { margin: 0; font-size: clamp(1.625rem, 2.6vw, 2rem); line-height: 1.15; }
.early-pitch p { margin: 0; color: var(--ink-soft); line-height: 1.65; }
.form { display: grid; gap: 14px; }
.form label, .form fieldset { display: grid; gap: 6px; }
.form label span, .form legend { font-size: var(--t2); font-weight: 700; color: var(--ink-max); }
.form em { font-style: normal; font-weight: 400; color: var(--ink-faint); margin-left: 6px; }
.form .input, .form .select { width: 100%; min-width: 0; }
.form textarea { resize: vertical; font: inherit; }
.form fieldset { border: 0; padding: 0; margin: 0; }
.checks { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 200px), 1fr)); gap: 6px 16px; margin-top: 6px; }
.form .check { display: flex; align-items: center; gap: 8px; font-size: var(--t2); color: var(--ink); }
.pair { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 200px), 1fr)); gap: 14px; }
.trap { position: absolute; left: -10000px; width: 1px; height: 1px; overflow: hidden; }

@media (max-width: 860px) {
  .split, .early { grid-template-columns: minmax(0, 1fr); }
}
</style>
