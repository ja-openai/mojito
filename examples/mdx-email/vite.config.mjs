import { defineConfig } from "vite";
import mdx from "@mdx-js/rollup";
import react from "@vitejs/plugin-react";

export default defineConfig(({ isPreview }) => ({
  plugins: [
    { enforce: "pre", ...mdx({ providerImportSource: "@mdx-js/react" }) },
    react({ include: /\.(jsx|mdx)$/ }),
  ],
  build: {
    ssr: "render.jsx",
    outDir: isPreview ? "dist" : ".generated/renderer",
    emptyOutDir: true,
    rolldownOptions: { output: { entryFileNames: "render.mjs" } },
  },
}));
