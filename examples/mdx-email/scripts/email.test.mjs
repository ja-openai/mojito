import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { cp, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import test from "node:test";
import { build } from "vite";
import { prepare, plainField } from "./prepare.mjs";

const root = fileURLToPath(new URL("..", import.meta.url));
async function fixture(t) {
  const dir = await mkdtemp(path.join(root, ".test-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  for (const name of [
    "content",
    "translations",
    "email.config.json",
    "vite.config.mjs",
    "render.jsx",
    "scripts",
  ])
    await cp(path.join(root, name), path.join(dir, name), { recursive: true });
  return dir;
}
test("renders every locale into script-free HTML and independent plain-text parts", async (t) => {
  const dir = await fixture(t);
  await prepare(dir, true);
  await build({
    root: dir,
    configFile: path.join(dir, "vite.config.mjs"),
    logLevel: "silent",
  });
  const { renderEmail, manifest } = await import(
    pathToFileURL(path.join(dir, ".generated/renderer/render.mjs"))
  );
  assert.equal(manifest.locales.length, 6);
  execFileSync(process.execPath, [path.join(dir, "scripts/export.mjs")], {
    cwd: dir,
  });
  for (const locale of manifest.locales)
    for (const email of manifest.emails) {
      const message = renderEmail(locale, email);
      for (const [file, value] of Object.entries({
        "subject.txt": message.subject,
        "preheader.txt": message.preheader,
        "body.html": message.html,
        "body.txt": message.text,
      })) {
        assert.equal(
          await readFile(path.join(dir, "dist", locale, email, file), "utf8"),
          value + "\n",
        );
      }
      assert.match(
        await readFile(
          path.join(dir, "dist", locale, email, "preview.html"),
          "utf8",
        ),
        /Sample translations/,
      );
      assert.ok(message.subject.length > 0);
      assert.ok(message.preheader.length > 0);
      assert.match(message.html, new RegExp(`lang="${locale}"`));
      assert.match(message.html, /role="presentation"/);
      assert.doesNotMatch(message.html, /<script|stylesheet|onClick=/i);
      assert.match(message.html, /href="https:\/\/example.org\/preferences"/);
      assert.doesNotMatch(message.text, /<p|mojito-id|<Footer/);
    }
  assert.match(renderEmail("ar", "welcome").html, /dir="rtl"/);
  assert.equal(
    renderEmail("fr", "welcome").subject,
    "Bienvenue chez Commonplace",
  );
});
test("a pulled shared correction appears in both email bodies", async (t) => {
  const dir = await fixture(t);
  await cp(path.join(dir, "translations"), path.join(dir, "localized"), {
    recursive: true,
  });
  await writeFile(
    path.join(dir, "localized/modules/Footer_fr.mdx"),
    "{/* mojito-id: footer.note */}\nUne correction partagée.\n",
  );
  await prepare(dir);
  await build({
    root: dir,
    configFile: path.join(dir, "vite.config.mjs"),
    logLevel: "silent",
  });
  const { renderEmail } = await import(
    pathToFileURL(path.join(dir, ".generated/renderer/render.mjs"))
  );
  for (const email of ["welcome", "digest"]) {
    assert.match(renderEmail("fr", email).html, /Une correction partagée/);
    assert.match(renderEmail("fr", email).text, /Une correction partagée/);
  }
});
test("normal build does not silently use fixtures or allow multiline header fields", async (t) => {
  const dir = await fixture(t);
  await assert.rejects(prepare(dir), /Missing fr email content/);
  await rm(path.join(dir, "translations/welcome_subject_fr.mdx"));
  await assert.rejects(prepare(dir, true), /Missing fr email content/);
  assert.throws(() => plainField("Subject\nBcc: someone"), /single line/);
  assert.throws(() => plainField("# Heading"), /plain text/);
});

test("rejects executable translations and changes to Git-owned composition before compiling", async (t) => {
  const dir = await fixture(t);
  const source = await readFile(
    path.join(dir, "content/welcome_body.mdx"),
    "utf8",
  );
  const target = path.join(dir, "translations/welcome_body_fr.mdx");
  for (const addition of [
    "{globalThis.process.exit(1)}",
    "export const surprise = globalThis.process.exit(1);",
    "import Surprise from 'node:fs';",
    "<Header onClick={globalThis.process.exit(1)} />",
    "<script>alert(1)</script>",
    "[Click](javascript:alert%281%29)",
  ]) {
    await writeFile(target, `${source}\n${addition}\n`);
    await assert.rejects(
      prepare(dir, true),
      /not supported|Only |Invalid module|Unsupported link/,
    );
  }
  await writeFile(target, source.replace("<Footer />", ""));
  await assert.rejects(prepare(dir, true), /module structure changed/);
});

test("rejects recursive module imports before compiling", async (t) => {
  const dir = await fixture(t);
  await writeFile(
    path.join(dir, "content/modules/Header.mdx"),
    "import Self from './Header.mdx';\n\n<Self />\n",
  );
  await assert.rejects(prepare(dir, true), /Module cycle/);
});
