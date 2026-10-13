#!/usr/bin/env node
import {
  readFile,
  stat,
  realpath,
  mkdir,
  writeFile,
  rename,
  unlink,
} from "node:fs/promises";
import { dirname, resolve, basename, join } from "node:path";
import { randomUUID } from "node:crypto";
import { checkCatalog, compileCatalog, generateTypes } from "./index.js";

const usage =
  "mf2-catalog check SOURCE TARGET [--locale LOCALE] [--profile react|model]\n" +
  "mf2-catalog compile INPUT --out OUTPUT [--profile react|model]\n" +
  "mf2-catalog types INPUT --resource RESOURCE --out OUTPUT\n" +
  "All commands accept --functions FILE for explicit function capability signatures.";
const diagnostics = (code, message) => [{ code, severity: "error", message }];

async function readJSON(path) {
  const info = await stat(path);
  if (!info.isFile() || info.size > 20_000_000)
    throw new Error("Input must be a regular JSON file no larger than 20 MB.");
  return JSON.parse(await readFile(path, "utf8"));
}

async function writeOutput(output, value, inputs) {
  const destination = resolve(output);
  const sourcePaths = await Promise.all(inputs.map((input) => realpath(input)));
  let existing;
  try {
    existing = await stat(destination);
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  if (existing) {
    if (sourcePaths.includes(await realpath(destination)))
      throw new Error("Output must not overwrite an input file.");
    for (const input of inputs) {
      const info = await stat(input);
      if (info.dev === existing.dev && info.ino === existing.ino)
        throw new Error("Output aliases an input file.");
    }
  }
  await mkdir(dirname(destination), { recursive: true });
  const temporary = join(
    dirname(destination),
    `.${basename(destination)}.${randomUUID()}.tmp`,
  );
  try {
    await writeFile(temporary, value, { flag: "wx" });
    await rename(temporary, destination);
  } finally {
    await unlink(temporary).catch((error) => {
      if (error.code !== "ENOENT") throw error;
    });
  }
}

async function main() {
  const [command, ...argv] = process.argv.slice(2);
  if (command === "--help") {
    console.log(usage);
    return;
  }
  const positional = [];
  const flags = Object.create(null);
  for (let index = 0; index < argv.length; index++) {
    const token = argv[index];
    if (!token.startsWith("--")) {
      positional.push(token);
      continue;
    }
    if (
      !["--out", "--profile", "--resource", "--locale", "--functions"].includes(
        token,
      ) ||
      Object.hasOwn(flags, token) ||
      !argv[index + 1] ||
      argv[index + 1].startsWith("--")
    ) {
      console.error(
        JSON.stringify({
          ok: false,
          diagnostics: diagnostics("cli-usage", usage),
        }),
      );
      process.exitCode = 2;
      return;
    }
    flags[token] = argv[++index];
  }
  const valid =
    command === "check"
      ? positional.length === 2 && !flags["--out"] && !flags["--resource"]
      : ["compile", "types"].includes(command) &&
        positional.length === 1 &&
        flags["--out"] &&
        (command !== "types" || flags["--resource"]);
  if (
    !valid ||
    (flags["--profile"] && !["react", "model"].includes(flags["--profile"]))
  ) {
    console.error(
      JSON.stringify({
        ok: false,
        diagnostics: diagnostics("cli-usage", usage),
      }),
    );
    process.exitCode = 2;
    return;
  }
  const options = {
    profile: flags["--profile"],
    targetLocale: flags["--locale"],
    functions: flags["--functions"]
      ? await readJSON(flags["--functions"])
      : undefined,
  };
  const input = await readJSON(positional[0]);
  const result =
    command === "check"
      ? checkCatalog(input, await readJSON(positional[1]), options)
      : command === "compile"
        ? compileCatalog(input, options)
        : generateTypes(input, { ...options, resource: flags["--resource"] });
  if (!result.ok) {
    console.error(
      JSON.stringify({ ok: false, diagnostics: result.diagnostics }),
    );
    process.exitCode = 1;
    return;
  }
  if (command !== "check")
    await writeOutput(
      flags["--out"],
      command === "compile"
        ? JSON.stringify(result.artifact, null, 2) + "\n"
        : result.code,
      [...positional, ...(flags["--functions"] ? [flags["--functions"]] : [])],
    );
  console.log(JSON.stringify({ ok: true, diagnostics: result.diagnostics }));
}

main().catch((error) => {
  console.error(
    JSON.stringify({
      ok: false,
      diagnostics: diagnostics("cli-io", error.message),
    }),
  );
  process.exitCode = 2;
});
