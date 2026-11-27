// Assets live under a directory unique to each build.
//
// Nuxt content-hashes chunk filenames, but /nuxt/builds/latest.json keeps one URL
// forever while its contents change every build. Cached at the edge it pinned the
// client to a previous build's manifest, which loaded that build's stylesheet and
// silently reverted the design. A per-build directory means a stale URL is simply
// never requested again, so the site self-heals without a cache purge.
const BUILD = process.env.DOCSWATCHER_BUILD_ID ?? Date.now().toString(36);

export default defineNuxtConfig({
  compatibilityDate: "2026-09-01",
  ssr: false,
  devtools: { enabled: false },
  telemetry: false,
  css: ["~/assets/main.css"],
  app: {
    // Kept absolute for dev; scripts/relativize.mjs rewrites the export to relative URLs so it works at any subpath.
    baseURL: "/",
    // Not "_nuxt": artifact hosts reserve published paths that start with an underscore.
    buildAssetsDir: `nuxt-${BUILD}/`,
    head: {
      // The theme is chosen before the first paint: the visitor's saved choice, else their device's
      // setting. app.vue keeps it in step after that. Inline is allowed by the CSP (script-src
      // 'unsafe-inline', which Nuxt's own bootstrap needs).
      script: [{
        tagPosition: "head",
        innerHTML: "(function(){var t='light';try{var s=localStorage.getItem('docswatcher.theme');t=s==='dark'||s==='light'?s:(matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light')}catch(e){}document.documentElement.setAttribute('data-theme',t)})()",
      }],
      title: "DocsWatcher",
      meta: [
        { name: "viewport", content: "width=device-width, initial-scale=1, viewport-fit=cover" },
        { name: "description", content: "Scan a repository for every external API contract it depends on and see which ones have an expiry date." },
        { name: "color-scheme", content: "light dark" },
      ],
      link: [
        // scripts/relativize.mjs rewrites this to ./favicon.svg so it resolves at any subpath.
        { rel: "icon", type: "image/svg+xml", href: "/favicon.svg" },
        { rel: "preconnect", href: "https://fonts.googleapis.com" },
        { rel: "preconnect", href: "https://fonts.gstatic.com", crossorigin: "" },
        { rel: "stylesheet", href: "https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500;700;800&family=Martian+Mono:wdth,wght@75..112.5,400..800&display=swap" },
      ],
    },
  },
  runtimeConfig: {
    public: {
      relayUrl: "",
    },
  },
  vite: {
    optimizeDeps: { exclude: ["web-tree-sitter"] },
    build: { target: "es2022" },
  },
  nitro: {
    prerender: { crawlLinks: false, routes: ["/"] },
    // `npm run dev` only: the app on :8080 answers the dashboard API and sign-in, same-origin,
    // as nginx does in production. The static export carries no proxy.
    devProxy: {
      "/api": { target: "http://localhost:8080/api", changeOrigin: false },
      "/auth": { target: "http://localhost:8080/auth", changeOrigin: false },
      "/subscribe": { target: "http://localhost:8080/subscribe", changeOrigin: false },
      "/unsubscribe": { target: "http://localhost:8080/unsubscribe", changeOrigin: false },
    },
  },
  typescript: { strict: true, typeCheck: false },
});
