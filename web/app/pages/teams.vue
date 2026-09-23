<script setup lang="ts">
// Free for individuals, early access for teams. The form posts to the app server
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
  "What we'd use first (dashboard, alerts, private repos, runtime, our own APIs):",
  "",
].join("\n");
const mailto = `mailto:hello@vukisha.co.ke?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(bodyText)}`;

const interests = ["Organisation dashboard", "Alerts before the date", "Private repositories", "Runtime observation", "Our own APIs"];
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

const free = [
  { what: "Scan any repository in your browser", where: "/" },
  { what: "Fail CI on a dated shutdown", where: "/ci" },
  { what: "Your AI coding assistant checks before it writes", where: "/agents" },
  { what: "Every shutdown date in your calendar", where: "/calendar" },
  { what: "The open knowledge base, and the CLI", where: "https://github.com/jameskomo/docswatcher" },
];

const team = [
  { title: "Every repository, one view", text: "A dashboard across your organisation, and the blast radius of a single shutdown: which repositories break, and where." },
  { title: "Warned before the date", text: "Email or Slack when a repository calls something due to shut down, 30 and 7 days ahead, not the morning it fails." },
  { title: "Private repositories", text: "The GitHub App watches private code on every push and opens an issue per finding, with a fix pull request on request." },
  { title: "What actually runs", text: "Runtime observation from your existing OpenTelemetry: which deprecated calls production really makes, and how often." },
  { title: "Your own APIs", text: "Add your internal services' deprecations, so the teams that call them hear about it the same way." },
];

useHead({
  title: "DocsWatcher for teams",
  meta: [{ name: "description", content: "DocsWatcher is free for developers. Teams get an organisation dashboard, alerts before shutdown dates, private repositories and runtime observation." }],
});
</script>

<template>
  <article class="teams">
    <section class="page">
      <div class="section-head">
        <h1>Free for developers. Built for teams too.</h1>
        <p class="lede">
          Everything that finds a dead API call stays free. Teams that need it across many
          repositories, with warnings before the date, can join early access now.
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

      <h2>For teams</h2>
      <div class="grid" data-testid="team-features">
        <div v-for="t in team" :key="t.title" class="card">
          <strong>{{ t.title }}</strong>
          <p class="t2 ink-soft">{{ t.text }}</p>
        </div>
      </div>

      <section class="early" id="early-access" data-testid="early-access">
        <h2>Request early access</h2>
        <p class="ink-soft">
          The first teams get a founding price and a direct line to shape what gets built first.
          We only use this to reply to you.
        </p>

        <div v-if="state === 'sent'" class="callout done" role="status" data-testid="early-access-sent">
          <strong>Thank you.</strong> We'll be in touch at {{ form.email }}.
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
.teams { max-width: 760px; }
.teams .prose, .teams .lede { max-width: none; }
.teams h1 { font-size: clamp(1.75rem, 3.4vw, 2.5rem); line-height: 1.15; text-wrap: balance; }
.teams .lede { margin-top: var(--s3); }
.teams .prose { margin-top: var(--s5); }
.free { display: grid; gap: var(--s2); }
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr)); gap: var(--s3); margin-top: var(--s3); }
.card { border: 1px solid var(--hair); border-radius: var(--radius-sm); padding: var(--s3); display: grid; gap: 6px; }
.early { margin-top: var(--s6); }
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
