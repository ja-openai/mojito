import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import {
  mkdtempSync,
  readFileSync,
  readdirSync,
  writeFileSync,
  symlinkSync,
  linkSync,
  rmSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const cli = fileURLToPath(new URL("../src/cli.js", import.meta.url));
const run = (...args) =>
  spawnSync(process.execPath, [cli, ...args], { encoding: "utf8" });

test("CLI check writes nothing; compilation and typegen are deterministic", () => {
  const folder = mkdtempSync(join(tmpdir(), "mf2-tooling-"));
  try {
    const source = join(folder, "en.mf2.json");
    const target = join(folder, "fr.mf2.json");
    const output = join(folder, "compiled.json");
    const types = join(folder, "messages.ts");
    writeFileSync(source, JSON.stringify({ hello: "Hello {$name}" }));
    writeFileSync(target, JSON.stringify({ hello: "Bonjour {$name}" }));
    const before = readdirSync(folder);
    const checked = run("check", source, target, "--locale", "fr");
    assert.equal(checked.status, 0, checked.stderr);
    assert.deepEqual(readdirSync(folder), before);
    assert.equal(run("compile", source, "--out", output).status, 0);
    const first = readFileSync(output, "utf8");
    assert.equal(JSON.parse(first).version, 1);
    assert.equal(run("compile", source, "--out", output).status, 0);
    assert.equal(readFileSync(output, "utf8"), first);
    assert.equal(
      run("types", source, "--resource", "messages.mf2.json", "--out", types)
        .status,
      0,
    );
    assert.match(readFileSync(types, "utf8"), /createMessages<Messages>/);
    assert.equal(run("check", source, target, "--out", output).status, 2);
    writeFileSync(target, JSON.stringify({ hello: "Bonjour {$other}" }));
    assert.equal(run("check", source, target).status, 1);
  } finally {
    rmSync(folder, { recursive: true, force: true });
  }
});

test("CLI protects source files, symlinks, hard links and existing output on errors", () => {
  const folder = mkdtempSync(join(tmpdir(), "mf2-tooling-"));
  try {
    const source = join(folder, "source.mf2.json");
    const output = join(folder, "output.json");
    const link = join(folder, "link.json");
    const hard = join(folder, "hard.json");
    const original = JSON.stringify({ hello: "Hello" });
    writeFileSync(source, original);
    symlinkSync(source, link);
    linkSync(source, hard);
    for (const path of [source, link, hard])
      assert.equal(run("compile", source, "--out", path).status, 2);
    assert.equal(readFileSync(source, "utf8"), original);
    writeFileSync(output, "previous output");
    writeFileSync(source, JSON.stringify({ hello: "Hello", bad: "{" }));
    const failed = run("compile", source, "--out", output);
    assert.equal(failed.status, 1);
    assert.ok(
      JSON.parse(failed.stderr).diagnostics.some((d) =>
        d.code.startsWith("parse/"),
      ),
    );
    assert.equal(readFileSync(output, "utf8"), "previous output");
    assert.ok(!readdirSync(folder).some((name) => name.endsWith(".tmp")));
    assert.equal(run("check", "missing.json", source).status, 2);
  } finally {
    rmSync(folder, { recursive: true, force: true });
  }
});

test("types are generated reproducibly from example source", () => {
  const generated = fileURLToPath(
    new URL("../examples/messages.ts", import.meta.url),
  );
  const before = readFileSync(generated, "utf8");
  execFileSync(process.execPath, [
    fileURLToPath(new URL("../examples/build.js", import.meta.url)),
  ]);
  assert.equal(readFileSync(generated, "utf8"), before);
});
