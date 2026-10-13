import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

const root = fileURLToPath(new URL("..", import.meta.url));
const loader = fileURLToPath(
  new URL("parser-guard-loader.mjs", import.meta.url),
);
function run(script, forbidParser) {
  return spawnSync(
    process.execPath,
    [
      "--no-warnings",
      "--loader",
      loader,
      "--input-type=module",
      "--eval",
      script,
    ],
    {
      cwd: root,
      encoding: "utf8",
      env: { ...process.env, MF2_FORBID_PARSER: forbidParser ? "1" : "0" },
    },
  );
}

test("production package and markup exports import only formatter modules without a parser", () => {
  const result = run(
    `
    import { createMF2, MF2, MF2Provider, useMF2, renderMF2Parts } from "@mojito-mf2/react";
    import { createMessages } from "@mojito-mf2/react/bindings";
    import { validateMessageMarkup } from "@mojito-mf2/react/markup";
    const message = { type: "message", declarations: [], pattern: ["Parsed catalog"] };
    validateMessageMarkup(message);
    const formatter = createMF2({ resources: { en: { guide: { hello: message } } }, locale: "en" });
    if (formatter.format("guide", "hello") !== "Parsed catalog") throw new Error("Formatting failed");
    if (createMessages("guide").bind(formatter).format("hello") !== "Parsed catalog") throw new Error("Binding failed");
    if ([MF2, MF2Provider, useMF2, renderMF2Parts].some(value => typeof value !== "function")) throw new Error("Missing public export");
  `,
    true,
  );
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stderr, /MF2_LOADED .*\/formatter\.js/);
  assert.match(result.stderr, /MF2_LOADED .*\/intl_functions\.js/);
  assert.doesNotMatch(result.stderr, /MF2_LOADED .*\/(?:parser|index)\.js/);
});

test("parser export explicitly loads the parser and returns existing serializable models", () => {
  const script = `
    import { parseMessage } from "@mojito-mf2/react/parser";
    const model = parseMessage("Hello {$name}");
    if (JSON.parse(JSON.stringify(model)).type !== "message") throw new Error("Invalid model");
  `;
  const result = run(script, false);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stderr, /MF2_LOADED .*\/parser\.js/);
  const guarded = run(script, true);
  assert.notEqual(guarded.status, 0);
  assert.match(guarded.stderr, /Production entrypoint loaded parser/);
});

test("the package includes each declared runtime/type entrypoint and excludes development tests", () => {
  const command = process.env.npm_execpath ? process.execPath : "npm";
  const args = [
    ...(process.env.npm_execpath ? [process.env.npm_execpath] : []),
    "pack",
    "--dry-run",
    "--json",
    "--ignore-scripts",
  ];
  const result = spawnSync(command, args, { cwd: root, encoding: "utf8" });
  assert.equal(result.status, 0, result.stderr);
  const files = new Set(
    JSON.parse(result.stdout)[0].files.map(({ path }) => path),
  );
  const manifest = JSON.parse(
    readFileSync(new URL("../package.json", import.meta.url), "utf8"),
  );
  for (const entry of Object.values(manifest.exports)) {
    assert.ok(
      files.has(entry.types.replace(/^\.\//, "")),
      `Missing type declaration ${entry.types}`,
    );
    assert.ok(
      files.has(entry.default.replace(/^\.\//, "")),
      `Missing runtime entrypoint ${entry.default}`,
    );
  }
  assert.ok(files.has("README.md"));
  assert.ok(files.has("LICENSE"));
  assert.ok(
    [...files].every(
      (path) => !path.startsWith("tests/") && !path.includes("node_modules"),
    ),
  );
});
