import assert from "node:assert/strict";
import { cp, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import React, { act } from "react";
import { renderToString } from "react-dom/server";
import { hydrateRoot } from "react-dom/client";
import TestRenderer from "react-test-renderer";
import { JSDOM } from "jsdom";
import { prepareContent } from "./content.mjs";
import { createMessageFormatter } from "../src/mf2-runtime.mjs";
import { createPreviewMessage } from "../src/PreviewMessage.mjs";

const projectDirectory = fileURLToPath(new URL("..", import.meta.url));
const resource = "messages.mf2.json";

async function fixture(t) {
  const directory = await mkdtemp(path.join(projectDirectory, ".test-mf2-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  for (const name of ["content", "translations"])
    await cp(path.join(projectDirectory, name), path.join(directory, name), {
      recursive: true,
    });
  return {
    projectDirectory: directory,
    translationDirectory: path.join(directory, "translations"),
    sample: true,
  };
}
async function resourcesFor(t) {
  const paths = await fixture(t);
  await prepareContent(paths);
  return JSON.parse(
    await readFile(
      path.join(paths.projectDirectory, ".generated/resources.json"),
      "utf8",
    ),
  );
}
async function editCatalog(paths, change) {
  const filename = path.join(
    paths.translationDirectory,
    "messages.mf2_fr.json",
  );
  const catalog = JSON.parse(await readFile(filename, "utf8"));
  change(catalog);
  await writeFile(filename, JSON.stringify(catalog));
}

test("precompiles both catalogs and formats locale plurals and explicit UTC dates without a parser", async (t) => {
  const resources = await resourcesFor(t);
  assert.equal(resources.en[resource]["calendar.summary"].type, "select");
  const en = createMessageFormatter(resources, "en");
  const fr = createMessageFormatter(resources, "fr");
  assert.equal(
    en(resource, "calendar.summary", { count: 1 }),
    "You have 1 learning session planned.",
  );
  assert.equal(
    en(resource, "calendar.summary", { count: 2 }),
    "You have 2 learning sessions planned.",
  );
  assert.equal(
    fr(resource, "calendar.summary", { count: 1 }),
    "Vous avez 1 séance d’apprentissage prévue.",
  );
  assert.equal(
    fr(resource, "calendar.summary", { count: 2 }),
    "Vous avez 2 séances d’apprentissage prévues.",
  );
  assert.equal(
    en(resource, "calendar.date", { date: "2026-09-16T12:00:00Z" }),
    "Your next session is on September 16, 2026.",
  );
  assert.equal(
    fr(resource, "calendar.date", { date: "2026-09-16T12:00:00Z" }),
    "Votre prochaine séance aura lieu le 16 septembre 2026.",
  );
  assert.throws(
    () => fr(resource, "calendar.summary", {}),
    /MF2 formatting failed/,
  );
  assert.throws(() => fr(resource, "unknown", {}), /Unknown MF2 message/);
});

test("interactive component updates plural and date output from the bundled models", async (t) => {
  const Component = createPreviewMessage(
    createMessageFormatter(await resourcesFor(t), "fr"),
  );
  let tree;
  act(() => {
    tree = TestRenderer.create(
      React.createElement(
        React.Fragment,
        null,
        React.createElement(Component, {
          resource,
          name: "calendar.summary",
          args: '{"count":2}',
        }),
        React.createElement(Component, {
          resource,
          name: "calendar.date",
          args: '{"date":"2026-09-16T12:00:00Z"}',
        }),
      ),
    );
  });
  t.after(() => act(() => tree.unmount()));
  const outputs = () =>
    tree.root.findAllByType("p").map((node) => node.children.join(""));
  const inputs = tree.root.findAllByType("input");
  act(() => inputs[0].props.onChange({ target: { value: "1" } }));
  assert.equal(outputs()[0], "Vous avez 1 séance d’apprentissage prévue.");
  act(() => inputs[1].props.onChange({ target: { value: "2026-12-25" } }));
  assert.equal(
    outputs()[1],
    "Votre prochaine séance aura lieu le 25 décembre 2026.",
  );
  act(() => inputs[0].props.onChange({ target: { value: "-1" } }));
  assert.equal(outputs()[0], "Vous avez 1 séance d’apprentissage prévue.");
});

test("French prerendered MF2 components hydrate with identical text and no recoverable errors", async (t) => {
  const Component = createPreviewMessage(
    createMessageFormatter(await resourcesFor(t), "fr"),
  );
  const app = React.createElement(
    React.Fragment,
    null,
    React.createElement(Component, {
      resource,
      name: "calendar.summary",
      args: '{"count":2}',
    }),
    React.createElement(Component, {
      resource,
      name: "calendar.date",
      args: '{"date":"2026-09-16T12:00:00Z"}',
    }),
  );
  const html = renderToString(app);
  const dom = new JSDOM(`<div id="root">${html}</div>`, {
    url: "https://example.test/fr/",
  });
  const previousWindow = globalThis.window;
  const previousDocument = globalThis.document;
  const previousAct = globalThis.IS_REACT_ACT_ENVIRONMENT;
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  let root;
  try {
    const errors = [];
    const container = dom.window.document.getElementById("root");
    const beforeHydration = container.innerHTML;
    await act(async () => {
      root = hydrateRoot(container, app, {
        onRecoverableError: (error) => errors.push(error),
      });
    });
    assert.deepEqual(errors, []);
    assert.equal(container.innerHTML, beforeHydration);
  } finally {
    if (root) await act(async () => root.unmount());
    dom.window.close();
    globalThis.window = previousWindow;
    globalThis.document = previousDocument;
    globalThis.IS_REACT_ACT_ENVIRONMENT = previousAct;
  }
});

test("a saved French correction flows through a fresh preparation into runtime models", async (t) => {
  const paths = await fixture(t);
  await editCatalog(paths, (catalog) => {
    catalog["calendar.date"] =
      "Rendez-vous le {$date :date dateStyle=long timeZone=UTC}.";
  });
  await prepareContent(paths);
  const resources = JSON.parse(
    await readFile(
      path.join(paths.projectDirectory, ".generated/resources.json"),
      "utf8",
    ),
  );
  assert.equal(
    createMessageFormatter(resources, "fr")(resource, "calendar.date", {
      date: "2026-09-16T12:00:00Z",
    }),
    "Rendez-vous le 16 septembre 2026.",
  );
});

test("missing MF2 target catalogs or entries fail without source fallback", async (t) => {
  const missing = await fixture(t);
  await rm(path.join(missing.translationDirectory, "messages.mf2_fr.json"));
  await assert.rejects(prepareContent(missing), /Missing French MF2 export/);
  const incomplete = await fixture(t);
  await editCatalog(incomplete, (catalog) => {
    delete catalog["calendar.date"];
  });
  await assert.rejects(prepareContent(incomplete), /catalog keys differ/);
});

test("malformed MF2, changed arguments and unsafe formatting options fail the build", async (t) => {
  for (const [message, expected] of [
    ["Invalid {$date", /Invalid MF2/],
    [
      "Date {$other :date dateStyle=long timeZone=UTC}",
      /arguments or formatting differ/,
    ],
    ["Date {$date :number}", /arguments or formatting differ/],
    ["Date {$date :date dateStyle=long}", /timeZone=UTC/],
    ["Date {$date :unrecognized}", /Unsupported MF2 function/],
  ]) {
    const paths = await fixture(t);
    await editCatalog(paths, (catalog) => {
      catalog["calendar.date"] = message;
    });
    await assert.rejects(prepareContent(paths), expected);
  }
});

test("unknown references, mismatched samples, and invalid sample operands fail before bundling", async (t) => {
  for (const [change, both, expected] of [
    [
      (text) => text.replace('name="calendar.date"', 'name="unknown"'),
      true,
      /Unknown MF2 reference/,
    ],
    [
      (text) => text.replace('"count":2', '"count":3'),
      false,
      /references differ/,
    ],
    [
      (text) => text.replace('"count":2', '"unknown":2'),
      true,
      /args do not match/,
    ],
    [
      (text) => text.replace("2026-09-16T12:00:00Z", "not a date"),
      true,
      /MF2 formatting failed/,
    ],
    [
      (text) =>
        text.replace(
          'resource="./messages.mf2.json"',
          'resource="../messages.mf2.json"',
        ),
      true,
      /escapes the content directory/,
    ],
  ]) {
    const paths = await fixture(t);
    const files = [path.join(paths.translationDirectory, "index_fr.mdx")];
    if (both)
      files.push(path.join(paths.projectDirectory, "content/index.mdx"));
    for (const file of files)
      await writeFile(file, change(await readFile(file, "utf8")));
    await assert.rejects(prepareContent(paths), expected);
  }
});
