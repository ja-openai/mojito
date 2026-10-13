import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MF2Provider, createMF2 } from "@mojito-mf2/react";
import { createMessages } from "@mojito-mf2/react/bindings";
import { compileCatalog, checkCatalog } from "../src/index.js";

test("source -> translation check -> AST catalog -> typed binding renders static email markup", async () => {
  const read = async (locale) =>
    JSON.parse(
      await readFile(
        new URL(`../examples/${locale}.mf2.json`, import.meta.url),
        "utf8",
      ),
    );
  const en = await read("en");
  const fr = await read("fr");
  assert.equal(checkCatalog(en, fr, { targetLocale: "fr" }).ok, true);
  const resources = {};
  for (const [locale, catalog] of Object.entries({ en, fr }))
    resources[locale] = { shop: compileCatalog(catalog).artifact.messages };
  const { MF2, bind } = createMessages("shop");
  const html = renderToStaticMarkup(
    React.createElement(
      MF2Provider,
      { resources, locale: "fr" },
      React.createElement(MF2, {
        name: "welcome",
        args: { name: "<Reader>" },
        markupRenderers: {
          terms: (children) =>
            React.createElement(
              "a",
              { href: "https://example.com/terms" },
              children,
            ),
        },
      }),
    ),
  );
  assert.match(html, /Bienvenue/);
  assert.match(html, /&lt;Reader&gt;/);
  assert.match(html, /<a href="https:\/\/example.com\/terms">conditions<\/a>/);
  assert.ok(!html.includes("script"));
  const formatter = bind(
    createMF2({ resources, locale: "fr", bidiIsolation: "none" }),
  );
  assert.equal(
    formatter.format("cart.summary", { count: 1 }),
    "Vous avez 1 article.",
  );
  assert.equal(
    formatter.format("cart.summary", { count: 5 }),
    "Vous avez 5 articles.",
  );
});

test("React typed binding import remains parser-free", () => {
  const loader = fileURLToPath(
    new URL("../../react/tests/parser-guard-loader.mjs", import.meta.url),
  );
  const result = spawnSync(
    process.execPath,
    [
      "--loader",
      loader,
      "--input-type=module",
      "-e",
      'import { createMessages } from "@mojito-mf2/react/bindings"; createMessages("shop");',
    ],
    {
      cwd: fileURLToPath(new URL("..", import.meta.url)),
      encoding: "utf8",
      env: { ...process.env, MF2_FORBID_PARSER: "1" },
    },
  );
  assert.equal(result.status, 0, result.stderr);
  assert.doesNotMatch(result.stderr, /MF2_LOADED .*\/parser\.js/);
});
