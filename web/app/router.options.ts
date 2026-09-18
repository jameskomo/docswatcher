import type { RouterConfig } from "@nuxt/schema";

// Hash routing so the static export works at any subpath, including an artifact host.
export default {
  hashMode: true,
} satisfies RouterConfig;
