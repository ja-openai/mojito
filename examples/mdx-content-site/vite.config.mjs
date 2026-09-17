import mdx from "@mdx-js/rollup";
import react from "@vitejs/plugin-react";
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";

const root = fileURLToPath(new URL(".", import.meta.url));

export default defineConfig(({ isSsrBuild }) => {
  if (!existsSync(`${root}.generated/manifest.json`)) {
    throw new Error(
      "Prepare pulled MDX first with npm run content:prepare, or use npm run dev:sample.",
    );
  }
  return {
    base: "./",
    plugins: [
      { enforce: "pre", ...mdx({ providerImportSource: "@mdx-js/react" }) },
      react({ include: /\.(jsx|js|mdx|md|tsx|ts)$/ }),
      {
        name: "parser-free-mf2-runtime",
        generateBundle(_options, bundle) {
          const modules = Object.values(bundle)
            .filter((item) => item.type === "chunk")
            .flatMap((item) => item.moduleIds);
          if (
            modules.some((id) =>
              /(?:mf2\/javascript|@mojito-mf2\/core)\/src\/parser\.js/.test(id),
            )
          )
            this.error("MF2 parser must not be included in the runtime build");
          this.emitFile({
            type: "asset",
            fileName: "mf2-build-info.json",
            source: JSON.stringify({
              parserIncluded: false,
              resources: "precompiled models",
            }),
          });
        },
      },
    ],
    build: {
      emptyOutDir: true,
      rolldownOptions: isSsrBuild
        ? { output: { entryFileNames: "entry-server.mjs" } }
        : {
            input: [
              "index.html",
              "en/index.html",
              "en/guide.html",
              "fr/index.html",
              "fr/guide.html",
            ].map((file) => `${root}${file}`),
          },
    },
  };
});
