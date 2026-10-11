import assert from "node:assert/strict";
import { cp, mkdtemp, readFile, rm } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import React, { act } from "react";
import { renderToString } from "react-dom/server";
import { hydrateRoot } from "react-dom/client";
import { JSDOM } from "jsdom";
import { MF2Provider } from "@mojito-mf2/react";
import { prepareContent } from "./content.mjs";
import { PreviewMessage } from "../src/PreviewMessage.mjs";
import { CatalogLoadingExample } from "../src/CatalogLoadingExample.mjs";

const projectDirectory = fileURLToPath(new URL("..", import.meta.url));
const resource = "messages.mf2.json";

function previewComponent(resources, locale) {
  return (props) =>
    React.createElement(
      MF2Provider,
      { resources, locale, sourceLocale: "en", timeZone: "UTC" },
      React.createElement(PreviewMessage, props),
    );
}

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
test("French prerendered MF2 components hydrate with identical text and no recoverable errors", async (t) => {
  const resources = await resourcesFor(t);
  const Component = previewComponent(resources, "fr");
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
    React.createElement(Component, {
      resource,
      name: "calendar.terms",
      args: "{}",
    }),
    React.createElement(CatalogLoadingExample, {
      initialSelection: { locale: "fr", resources, version: "fixture-v1" },
      loadCatalog() {
        throw new Error("Hydration must use the pinned initial catalog");
      },
    }),
  );
  const html = renderToString(app);
  assert.match(
    html,
    /href="https:\/\/example.com\/terms"><strong>conditions d’apprentissage<\/strong><\/a>/,
  );
  assert.match(html, /<br\/>/);
  const dom = new JSDOM(`<div id="root">${html}</div>`, {
    url: "https://example.com/fr/",
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
