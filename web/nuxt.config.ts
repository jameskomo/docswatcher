export default defineNuxtConfig({
  compatibilityDate: "2026-09-01",
  ssr: false,
  devtools: { enabled: false },
  css: ["~/assets/main.css"],
  app: {
    // Kept absolute for dev; scripts/relativize.mjs rewrites the export to relative URLs so it works at any subpath.
    baseURL: "/",
    // Not "_nuxt": artifact hosts reserve published paths that start with an underscore.
    buildAssetsDir: "nuxt/",
    head: {
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
        { rel: "stylesheet", href: "https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500&display=swap" },
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
  },
  typescript: { strict: true, typeCheck: false },
});
