import { spawn, spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdir, rm, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";

const editorDir = dirname(fileURLToPath(import.meta.url));
const mf2Dir = resolve(editorDir, "..");
const rustDir = resolve(mf2Dir, "rust", "mf2-prototype");
const binaryName = process.platform === "win32" ? "mf2-prototype.exe" : "mf2-prototype";
const rustBinary = resolve(rustDir, "target", "debug", binaryName);
let rustBinaryReady = false;
let rustBuildFailure = null;

export async function handleApiRequest(request, response) {
  const url = new URL(request.url ?? "/", "http://127.0.0.1");
  if (request.method === "POST" && url.pathname === "/api/format") {
    await handleFormat(request, response);
    return true;
  }
  if (request.method === "GET" && url.pathname === "/api/plurals") {
    await handlePluralMetadata(url, response);
    return true;
  }
  return false;
}

async function handleFormat(request, response) {
  const body = await readBody(request);
  const tempDir = join(tmpdir(), `mf2-editor-${randomUUID()}`);
  const requestPath = join(tempDir, "request.json");
  await mkdir(tempDir, { recursive: true });
  try {
    await writeFile(requestPath, body, "utf8");
    const result = await runRust(["editor-json", requestPath]);
    response.writeHead(statusCodeForRustResult(result), {
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
  response.writeHead(statusCodeForRustResult(result), {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
  });
  response.end(result.stdout || JSON.stringify({ error: result.stderr || "Rust plural metadata failed" }));
}

function ensureRustBinary() {
  if (rustBinaryReady) return { ok: true };
  if (rustBuildFailure) return rustBuildFailure;
  const result = spawnSync("cargo", ["build", "--quiet"], {
    cwd: rustDir,
    encoding: "utf8",
  });
  if (result.status !== 0) {
    rustBuildFailure = {
      ok: false,
      status: result.status ?? 1,
      stderr: result.stderr || result.stdout || result.error?.message || "Rust build failed",
    };
    return rustBuildFailure;
  }
  rustBinaryReady = true;
  return { ok: true };
}

function statusCodeForRustResult(result) {
  if (result.status === 0) return 200;
  return result.unavailable ? 503 : 422;
}

function runRust(args) {
  const build = ensureRustBinary();
  if (!build.ok) {
    return Promise.resolve({
      status: build.status,
      stdout: "",
      stderr: build.stderr,
      unavailable: true,
    });
  }
  return new Promise((resolveRun) => {
    const child = spawn(rustBinary, args, { cwd: rustDir });
    let stdout = "";
    let stderr = "";
    let settled = false;
    const resolveOnce = (result) => {
      if (settled) return;
      settled = true;
      resolveRun(result);
    };
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.on("error", (error) => {
      resolveOnce({ status: 1, stdout, stderr: error.message || stderr });
    });
    child.on("close", (status) => {
      resolveOnce({ status, stdout, stderr });
    });
  });
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
