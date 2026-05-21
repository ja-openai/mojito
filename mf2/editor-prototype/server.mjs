import { createServer } from "node:http";
import { randomUUID } from "node:crypto";
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, extname, join, resolve } from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";

const editorDir = dirname(fileURLToPath(import.meta.url));
const mf2Dir = resolve(editorDir, "..");
const rustDir = resolve(mf2Dir, "rust", "mf2-prototype");
const binaryName = process.platform === "win32" ? "mf2-prototype.exe" : "mf2-prototype";
const rustBinary = resolve(rustDir, "target", "debug", binaryName);
const port = Number(process.env.PORT ?? process.argv.find((arg) => arg.startsWith("--port="))?.slice("--port=".length) ?? 8788);

buildRustBinary();

const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url ?? "/", "http://127.0.0.1");
    if (request.method === "POST" && url.pathname === "/api/format") {
      await handleFormat(request, response);
      return;
    }
    if (request.method === "GET" && url.pathname === "/api/plurals") {
      await handlePluralMetadata(url, response);
      return;
    }
    await serveStatic(request, response);
  } catch (error) {
    response.writeHead(500, { "content-type": "application/json; charset=utf-8" });
    response.end(JSON.stringify({ error: String(error?.message ?? error) }));
  }
});

server.listen(port, "127.0.0.1", () => {
  console.log(`MF2 editor prototype: http://127.0.0.1:${port}/`);
  console.log("Parser/runtime: Rust mf2-prototype");
});

function buildRustBinary() {
  const result = spawnSync("cargo", ["build", "--quiet"], {
    cwd: rustDir,
    encoding: "utf8",
  });
  if (result.status !== 0) {
    process.stderr.write(result.stderr || result.stdout);
    process.exit(result.status ?? 1);
  }
}

async function handleFormat(request, response) {
  const body = await readBody(request);
  const tempDir = join(tmpdir(), `mf2-editor-${randomUUID()}`);
  const requestPath = join(tempDir, "request.json");
  await mkdir(tempDir, { recursive: true });
  try {
    await writeFile(requestPath, body, "utf8");
    const result = await runRust(["editor-json", requestPath]);
    response.writeHead(result.status === 0 ? 200 : 422, {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    });
    response.end(result.stdout || JSON.stringify({ error: result.stderr || "Rust parser failed" }));
  } finally {
    await rm(tempDir, { recursive: true, force: true });
  }
}

async function handlePluralMetadata(url, response) {
  const locale = url.searchParams.get("locale") || "en";
  const result = await runRust(["plural-json", locale]);
  response.writeHead(result.status === 0 ? 200 : 422, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
  });
  response.end(result.stdout || JSON.stringify({ error: result.stderr || "Rust plural metadata failed" }));
}

function runRust(args) {
  return new Promise((resolveRun) => {
    const child = spawn(rustBinary, args, { cwd: rustDir });
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.on("close", (status) => {
      resolveRun({ status, stdout, stderr });
    });
  });
}

async function serveStatic(request, response) {
  const url = new URL(request.url ?? "/", "http://127.0.0.1");
  const pathname = url.pathname === "/" ? "/index.html" : decodeURIComponent(url.pathname);
  const filePath = resolve(editorDir, `.${pathname}`);
  if (!filePath.startsWith(editorDir)) {
    response.writeHead(403);
    response.end("Forbidden");
    return;
  }
  try {
    const body = await readFile(filePath);
    response.writeHead(200, { "content-type": contentType(filePath) });
    response.end(body);
  } catch {
    response.writeHead(404);
    response.end("Not found");
  }
}

function readBody(request) {
  return new Promise((resolveRead, reject) => {
    let body = "";
    request.setEncoding("utf8");
    request.on("data", (chunk) => {
      body += chunk;
      if (body.length > 1024 * 1024) {
        request.destroy(new Error("Request body too large"));
      }
    });
    request.on("end", () => resolveRead(body));
    request.on("error", reject);
  });
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
    default:
      return "application/octet-stream";
  }
}
