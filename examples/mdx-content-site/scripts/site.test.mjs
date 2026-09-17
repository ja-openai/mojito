import assert from "node:assert/strict";
import { cp, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import {
  assembleContent,
  prepareContent,
  translationPath,
} from "./content.mjs";
import { build as viteBuild } from "vite";
import { prerenderSite } from "./prerender.mjs";
import { createPreviewChoice } from "../src/PreviewChoice.mjs";
import TestRenderer, { act } from "react-test-renderer";

// Exercise the same two Vite builds as npm run build:site in an isolated fixture.
async function buildSite(options) {
  await prepareContent(options);
  const config = {
    root: options.projectDirectory,
    configFile: path.join(options.projectDirectory, "vite.config.mjs"),
    logLevel: "silent",
  };
  await viteBuild(config);
  await viteBuild({
    ...config,
    build: {
      ssr: path.join(options.projectDirectory, "src/entry-server.jsx"),
      outDir: ".generated/ssr",
      emptyOutDir: true,
    },
  });
  return prerenderSite(options.projectDirectory);
}

const projectDirectory = fileURLToPath(new URL("..", import.meta.url));

async function fixture(t) {
  const directory = await mkdtemp(path.join(projectDirectory, ".test-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  await cp(
    path.join(projectDirectory, "content"),
    path.join(directory, "content"),
    { recursive: true },
  );
  await cp(
    path.join(projectDirectory, "translations"),
    path.join(directory, "translations"),
    { recursive: true },
  );
  for (const file of [
    "styles.css",
    "vite.config.mjs",
    "index.html",
    "package.json",
    "src",
    "en",
    "fr",
  ]) {
    await cp(path.join(projectDirectory, file), path.join(directory, file), {
      recursive: true,
    });
  }
  return {
    projectDirectory: directory,
    sourceDirectory: path.join(directory, "content"),
    translationDirectory: path.join(directory, "translations"),
    generatedDirectory: path.join(directory, ".generated"),
  };
}

test("normalizes locale suffixes while preserving nested directories and imports", async (t) => {
  const paths = await fixture(t);
  const documents = await assembleContent(paths);
  assert.equal(documents.files.length, 8);
  assert.equal(
    translationPath("modules/routine/Tip.mdx"),
    "modules/routine/Tip_fr.mdx",
  );
  const welcome = await readFile(
    path.join(paths.generatedDirectory, "fr/modules/Welcome.mdx"),
    "utf8",
  );
  assert.match(welcome, /import Steps from '.\/routine\/Steps.mdx'/);
  assert.match(welcome, /De petites idées à mettre en pratique/);
  assert.match(
    await readFile(
      path.join(paths.generatedDirectory, "fr/modules/routine/Tip.mdx"),
      "utf8",
    ),
    /Un petit rappel/,
  );
});

test("compiles real MDX with three levels, reused modules, localized navigation and page links", async (t) => {
  const paths = await fixture(t);
  const output = await buildSite({ ...paths, sample: true });
  const frenchHome = await readFile(path.join(output, "fr/index.html"), "utf8");
  const frenchGuide = await readFile(
    path.join(output, "fr/guide.html"),
    "utf8",
  );
  const englishHome = await readFile(
    path.join(output, "en/index.html"),
    "utf8",
  );
  assert.match(frenchHome, /lang="fr"/);
  assert.match(frenchHome, /Apprendre en toute tranquillité/);
  assert.match(frenchHome, /Un petit rappel/);
  assert.doesNotMatch(frenchHome, /A gentle reminder|Three simple steps/);
  assert.equal(frenchHome.split("Retenez une idée utile.").length - 1, 2);
  assert.equal(frenchGuide.split("Retenez une idée utile.").length - 1, 1);
  assert.match(frenchGuide, /Trois étapes simples/);
  assert.match(frenchGuide, /href="index.html"/);
  assert.match(frenchGuide, /href="..\/en\/guide.html"/);
  assert.match(frenchHome, /href="guide.html">Créez une petite routine/);
  assert.match(englishHome, /A gentle reminder/);
  assert.match(frenchHome, /Sample preview/);
  const info = JSON.parse(
    await readFile(path.join(output, "build-info.json"), "utf8"),
  );
  assert.equal(info.mode, "sample");
  assert.equal(info.assets.length, 8);
  assert.match(
    frenchHome,
    /<option value="0" selected="">Individuel<\/option>/,
  );
  assert.match(frenchHome, /<option value="1">Équipe<\/option>/);
  assert.match(frenchHome, /data-choice-panel="0">\s*<h2>Individuel<\/h2>/);
  assert.match(
    frenchHome,
    /data-choice-panel="1" hidden="">\s*<h2>Équipe<\/h2>/,
  );
  assert.match(frenchHome, /Partagez une idée avec votre équipe/);
  assert.match(englishHome, /<option value="1">Team<\/option>/);
  assert.match(englishHome, /Share one idea with your team/);
  assert.match(frenchHome, /data-prerendered="true"/);
  assert.match(frenchHome, /Vous avez 2 séances d’apprentissage prévues/);
  assert.match(frenchHome, /16 septembre 2026/);
  assert.match(englishHome, /You have 2 learning sessions planned/);
  assert.match(englishHome, /September 16, 2026/);
  assert.match(frenchHome, /type="date"/);
  assert.match(frenchHome, /type="number"/);
  assert.deepEqual(
    JSON.parse(
      await readFile(path.join(output, "mf2-build-info.json"), "utf8"),
    ),
    {
      parserIncluded: false,
      resources: "precompiled models",
    },
  );
  assert.match(
    frenchHome,
    /<script type="module"[^>]+src="\.\.\/assets\/[^" ]+\.js"/,
  );
  assert.match(
    frenchHome,
    /<link rel="stylesheet"[^>]+href="\.\.\/assets\/[^" ]+\.css"/,
  );
});

test("choice adapter requires bounded static module alternatives without props", () => {
  const Individual = () => React.createElement("h2", null, "Individual");
  const Team = () => React.createElement("h2", null, "Team");
  const Choice = createPreviewChoice(
    new Map([
      [Individual, "Individual"],
      [Team, "Team"],
    ]),
  );
  const renderChoice = (children) =>
    renderToStaticMarkup(React.createElement(Choice, null, ...children));
  assert.throws(
    () => renderChoice([React.createElement(Individual)]),
    /between two and eight/,
  );
  assert.throws(
    () =>
      renderChoice(
        Array.from({ length: 9 }, () => React.createElement(Individual)),
      ),
    /between two and eight/,
  );
  assert.throws(
    () =>
      renderChoice([
        React.createElement(Individual, { label: "override" }),
        React.createElement(Team),
      ]),
    /without props/,
  );
  assert.throws(
    () => renderChoice(["Unexpected prose", React.createElement(Team)]),
    /imported MDX modules/,
  );
});

test("each React choice switches its own panels without changing another choice", () => {
  const Individual = () => React.createElement("h2", null, "Individual");
  const Team = () => React.createElement("h2", null, "Team");
  const Choice = createPreviewChoice(
    new Map([
      [Individual, "Individual"],
      [Team, "Team"],
    ]),
  );
  const choice = (key) =>
    React.createElement(
      Choice,
      { key },
      React.createElement(Individual),
      React.createElement(Team),
    );
  let rendered;
  act(() => {
    rendered = TestRenderer.create(
      React.createElement(
        React.Fragment,
        null,
        choice("first"),
        choice("second"),
      ),
    );
  });
  const selectors = rendered.root.findAllByType("select");
  const panels = () =>
    rendered.root
      .findAll((node) => node.props?.["data-choice-panel"] != null)
      .map((node) => node.props.hidden);
  assert.deepEqual(panels(), [false, true, false, true]);
  act(() => selectors[0].props.onChange({ target: { value: "1" } }));
  assert.deepEqual(panels(), [true, false, false, true]);
  act(() => selectors[0].props.onChange({ target: { value: "0" } }));
  assert.deepEqual(panels(), [false, true, false, true]);
  act(() => rendered.unmount());
});

test("missing translated modules fail without an English fallback", async (t) => {
  const paths = await fixture(t);
  await rm(path.join(paths.translationDirectory, "modules/routine/Tip_fr.mdx"));
  await assert.rejects(
    assembleContent(paths),
    /Missing French export: modules\/routine\/Tip_fr.mdx/,
  );
});

test("stale extra exports fail instead of appearing in the site", async (t) => {
  const paths = await fixture(t);
  await writeFile(
    path.join(paths.translationDirectory, "removed_fr.mdx"),
    "# Old content",
  );
  await assert.rejects(
    assembleContent(paths),
    /Unexpected MDX export: removed_fr.mdx/,
  );
});

test("other demo locales coexist with French but stale assets are still rejected", async (t) => {
  const paths = await fixture(t);
  const documents = await assembleContent(paths);
  assert.equal(
    documents.translated.get("index.mdx").match(/^# .+$/m)?.[0],
    (
      await readFile(
        path.join(paths.translationDirectory, "index_fr.mdx"),
        "utf8",
      )
    ).match(/^# .+$/m)?.[0],
  );
  await writeFile(
    path.join(paths.translationDirectory, "removed_de.mdx"),
    "# Old content",
  );
  await assert.rejects(
    assembleContent(paths),
    /Unexpected MDX export: removed_de.mdx/,
  );
});

test("module cycles are rejected while sibling reuse remains allowed", async (t) => {
  const paths = await fixture(t);
  await writeFile(
    path.join(paths.sourceDirectory, "modules/routine/Tip.mdx"),
    "import Steps from './Steps.mdx';\n\n<Steps />\n",
  );
  await assert.rejects(assembleContent(paths), /Module cycle/);
});

test("a fourth included level is rejected to match review preview depth", async (t) => {
  const paths = await fixture(t);
  await writeFile(
    path.join(paths.sourceDirectory, "modules/routine/Tip.mdx"),
    "import SharedNote from '../SharedNote.mdx';\n\n<SharedNote />\n",
  );
  await assert.rejects(assembleContent(paths), /Module depth exceeds 3/);
});

test("module paths cannot escape the source tree or use package imports", async (t) => {
  for (const importPath of [
    "../../../outside.mdx",
    "package/component.mdx",
    "./Tip.mdx?raw",
  ]) {
    const paths = await fixture(t);
    await writeFile(
      path.join(paths.sourceDirectory, "modules/routine/Tip.mdx"),
      `import Outside from '${importPath}';\n\n<Outside />\n`,
    );
    await assert.rejects(
      assembleContent(paths),
      /escapes the content directory|Invalid module import/,
    );
  }
});

test("translated module imports must match the authored source", async (t) => {
  const paths = await fixture(t);
  const filename = path.join(
    paths.translationDirectory,
    "modules/Welcome_fr.mdx",
  );
  await writeFile(
    filename,
    (await readFile(filename, "utf8")).replace(
      "./routine/Steps.mdx",
      "./SharedNote.mdx",
    ),
  );
  await assert.rejects(
    assembleContent(paths),
    /French module imports differ from the source/,
  );
});

test("Vite rejects a parser accidentally imported into browser runtime code", async (t) => {
  const paths = await fixture(t);
  await prepareContent({ ...paths, sample: true });
  const filename = path.join(paths.projectDirectory, "src/main.jsx");
  await writeFile(
    filename,
    (await readFile(filename, "utf8")) +
      `
import { parseToModel } from "@mojito-mf2/core/parser";
console.log(parseToModel("Parser must stay in the build"));
`,
  );
  await assert.rejects(
    viteBuild({
      root: paths.projectDirectory,
      configFile: path.join(paths.projectDirectory, "vite.config.mjs"),
      logLevel: "silent",
    }),
    /MF2 parser must not be included/,
  );
});
