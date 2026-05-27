import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

import { handleApiRequest } from "./server-api.mjs";

const root = dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  root,
  server: {
    host: "127.0.0.1",
    port: Number(process.env.PORT ?? 8788),
  },
  build: {
    outDir: "dist",
    emptyOutDir: true,
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      input: {
        main: resolve(root, "index.html"),
        libLab: resolve(root, "lib-lab.html"),
        reviewDemo: resolve(root, "review-demo.html"),
        workbenchDemo: resolve(root, "workbench-demo.html"),
      },
    },
  },
  plugins: [
    react(),
    {
      name: "mf2-editor-api",
      configureServer(server) {
        server.middlewares.use(async (request, response, next) => {
          try {
            if (await handleApiRequest(request, response)) return;
            next();
          } catch (error) {
            response.writeHead(500, { "content-type": "application/json; charset=utf-8" });
            response.end(JSON.stringify({ error: String(error?.message ?? error) }));
          }
        });
      },
    },
  ],
});
