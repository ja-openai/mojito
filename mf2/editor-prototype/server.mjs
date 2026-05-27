import { createServer } from "node:http";
import { access, readFile } from "node:fs/promises";
import { dirname, extname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { handleApiRequest } from "./server-api.mjs";

const editorDir = dirname(fileURLToPath(import.meta.url));
const port = Number(process.env.PORT ?? process.argv.find((arg) => arg.startsWith("--port="))?.slice("--port=".length) ?? 8788);
const staticArg = process.env.STATIC_DIR ?? process.argv.find((arg) => arg.startsWith("--static="))?.slice("--static=".length) ?? "dist";
const staticDir = resolve(editorDir, staticArg);

const server = createServer(async (request, response) => {
  try {
    if (await handleApiRequest(request, response)) return;
    await serveStatic(request, response);
  } catch (error) {
    response.writeHead(500, { "content-type": "application/json; charset=utf-8" });
    response.end(JSON.stringify({ error: String(error?.message ?? error) }));
  }
});

server.listen(port, "127.0.0.1", () => {
  console.log(`MF2 editor prototype static server: http://127.0.0.1:${port}/`);
  console.log(`Static root: ${staticDir}`);
  console.log("Parser/runtime: JavaScript core; Rust comparison endpoint builds on first use");
});

async function serveStatic(request, response) {
  const url = new URL(request.url ?? "/", "http://127.0.0.1");
  const pathname = url.pathname === "/" ? "/index.html" : decodeURIComponent(url.pathname);
  const filePath = resolve(staticDir, `.${pathname}`);
  if (!filePath.startsWith(staticDir)) {
    response.writeHead(403);
    response.end("Forbidden");
    return;
  }
  try {
    const body = await readFile(filePath);
    response.writeHead(200, { "content-type": contentType(filePath) });
    response.end(body);
  } catch {
    const hasStaticRoot = await pathExists(staticDir);
    response.writeHead(hasStaticRoot ? 404 : 503, { "content-type": "text/plain; charset=utf-8" });
    response.end(hasStaticRoot ? "Not found" : "Static build missing. Run npm run build before npm run serve.");
  }
}

async function pathExists(path) {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
}

function contentType(filePath) {
  switch (extname(filePath)) {
    case ".html":
      return "text/html; charset=utf-8";
    case ".css":
      return "text/css; charset=utf-8";
    case ".js":
    case ".mjs":
      return "text/javascript; charset=utf-8";
    case ".svg":
      return "image/svg+xml";
    default:
      return "application/octet-stream";
  }
}
