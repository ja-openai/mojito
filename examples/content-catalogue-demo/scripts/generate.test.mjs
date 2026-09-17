import assert from "node:assert/strict";
import { mkdtemp, readFile, readdir, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { createCatalogue, generate, inspectCatalogue } from "./generate.mjs";

test("catalogue fits preview limits with real reuse, varied paths, and aligned French fixtures", () => {
  const entries = createCatalogue();
  assert.deepEqual(inspectCatalogue(entries), {
    assets: 180,
    pages: 120,
    modules: 60,
    directories: 66,
    sourceStrings: 720,
    frenchAssets: 72,
    frenchStrings: 180,
    scenarioPages: 24,
    maximumIncludeDepth: 3,
  });
  const pageIntros = entries
    .filter((entry) => entry.role === "page")
    .map(
      (entry) =>
        entry.source.match(/mojito-id: [^ ]+\.intro \*\/\}\n([^\n]+)/)[1],
    );
  assert.equal(
    new Set(pageIntros).size,
    120,
    "Pages must have distinct authored introductions",
  );
});

test("generation is reproducible and replaces stale generated files", async (t) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "mojito-catalogue-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const generated = path.join(directory, "output");
  await generate(generated);
  const first = await readFile(path.join(generated, "manifest.json"), "utf8");
  const { writeFile } = await import("node:fs/promises");
  await writeFile(
    path.join(generated, "content", "stale.mdx"),
    "# Old content",
  );
  await generate(generated);
  assert.equal(
    await readFile(path.join(generated, "manifest.json"), "utf8"),
    first,
  );
  assert.ok(
    !(await readdir(path.join(generated, "content"))).includes("stale.mdx"),
  );
  const french = await readFile(
    path.join(generated, "translations/modules/shared/audience/Team_fr.mdx"),
    "utf8",
  );
  assert.match(french, /# En équipe/);
  assert.match(french, /from '\.\.\/patterns\/ReviewTogether\.mdx'/);
  const seededSource = await readFile(
    path.join(generated, "seed-content/modules/shared/audience/Team.mdx"),
    "utf8",
  );
  assert.match(seededSource, /Working with a team/);
  await assert.rejects(
    readFile(
      path.join(
        generated,
        "seed-content/pages/design/guides/design-error-recovery.mdx",
      ),
    ),
    { code: "ENOENT" },
  );
});

test("rejects unresolved imports and recursion before writing or pushing", () => {
  const entries = createCatalogue();
  const note = entries.find(
    (entry) => entry.path === "modules/shared/notes/Context.mdx",
  );
  note.source =
    "import Broken from './Missing.mdx';\n<Broken />\n" + note.source;
  assert.throws(() => inspectCatalogue(entries), /Unresolved import/);
  note.source = note.source.replaceAll("./Missing.mdx", "./Context.mdx");
  assert.throws(() => inspectCatalogue(entries), /Recursive include/);
});
