import assert from "node:assert/strict";
import { cp, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import React, { act } from "react";
import TestRenderer from "react-test-renderer";
import { MF2Provider } from "@mojito-mf2/react";
import { renderMF2Parts } from "@mojito-mf2/react";
import { formatMessageToParts } from "@mojito-mf2/core/formatter";
import { MF2ParseError, parseMessage } from "@mojito-mf2/react/parser";
import { prepareContent } from "./content.mjs";
import { createMessageFormatter } from "../src/mf2-runtime.mjs";
import { PreviewMessage } from "../src/PreviewMessage.mjs";
import { CatalogLoadingExample } from "../src/CatalogLoadingExample.mjs";
import { messageMarkupRenderers } from "../src/message-markup-renderers.mjs";

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
async function editCatalog(paths, change, { source = false } = {}) {
  const filename = source
    ? path.join(paths.projectDirectory, "content", resource)
    : path.join(paths.translationDirectory, "messages.mf2_fr.json");
  const catalog = JSON.parse(await readFile(filename, "utf8"));
  change(catalog);
  await writeFile(filename, JSON.stringify(catalog));
}

test("parses both catalogs at build time and formats locale plurals and explicit UTC dates without a runtime parser", async (t) => {
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
    "Your next session is on \u2068September 16, 2026\u2069.",
  );
  assert.equal(
    fr(resource, "calendar.date", { date: "2026-09-16T12:00:00Z" }),
    "Votre prochaine séance aura lieu le \u206816 septembre 2026\u2069.",
  );
  assert.throws(
    () => fr(resource, "calendar.summary", {}),
    /MF2 formatting failed/,
  );
  assert.throws(() => fr(resource, "unknown", {}), /MF2 formatting failed/);
});

test("interactive component updates plural and date output from the bundled models", async (t) => {
  const Component = previewComponent(await resourcesFor(t), "fr");
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
  const text = (node) =>
    typeof node === "string" ? node : node.children.map(text).join("");
  const outputs = () => tree.root.findAllByType("p").map(text);
  const inputs = tree.root.findAllByType("input");
  act(() => inputs[0].props.onChange({ target: { value: "1" } }));
  assert.equal(
    outputs()[0],
    "Vous avez \u20681\u2069 séance d’apprentissage prévue.",
  );
  act(() => inputs[1].props.onChange({ target: { value: "2026-12-25" } }));
  assert.equal(
    outputs()[1],
    "Votre prochaine séance aura lieu le \u206825 décembre 2026\u2069.",
  );
  act(() => inputs[0].props.onChange({ target: { value: "-1" } }));
  assert.equal(
    outputs()[0],
    "Vous avez \u20681\u2069 séance d’apprentissage prévue.",
  );
});

test("async selection loads first, ignores superseded responses, and retains the previous catalog on failure", async (t) => {
  const resources = await resourcesFor(t);
  const pending = [];
  const loadCatalog = (locale) =>
    new Promise((resolve, reject) => {
      pending.push({ locale, resolve, reject });
    });
  const selection = (locale, version = "fixture-v1") => ({
    locale,
    resources,
    version,
  });
  let tree;
  act(() => {
    tree = TestRenderer.create(
      React.createElement(CatalogLoadingExample, {
        initialSelection: selection("en"),
        loadCatalog,
      }),
    );
  });
  t.after(() => act(() => tree.unmount()));
  const rendered = () =>
    tree.root.findByProps({ className: "loaded-messages" }).props.title;
  const select = (locale) => {
    let request;
    act(() => {
      request = tree.root
        .findByType("select")
        .props.onChange({ target: { value: locale } });
    });
    return request;
  };

  const oldFrench = select("fr");
  assert.equal(rendered(), "You have 2 learning sessions planned.");
  const latestEnglish = select("en");
  await act(async () => {
    pending[1].resolve(selection("en"));
    await latestEnglish;
  });
  await act(async () => {
    pending[0].resolve(selection("fr"));
    await oldFrench;
  });
  assert.equal(rendered(), "You have 2 learning sessions planned.");
  assert.equal(tree.root.findByType("select").props.value, "en");

  const french = select("fr");
  await act(async () => {
    pending[2].resolve(selection("fr"));
    await french;
  });
  assert.equal(rendered(), "Vous avez 2 séances d’apprentissage prévues.");
  const failedEnglish = select("en");
  await act(async () => {
    pending[3].reject(new Error("Network unavailable"));
    await failedEnglish;
  });
  assert.equal(rendered(), "Vous avez 2 séances d’apprentissage prévues.");
  assert.equal(tree.root.findByType("select").props.value, "fr");
  assert.equal(tree.root.findAllByProps({ role: "alert" }).length, 1);

  const wrongVersion = select("en");
  await act(async () => {
    pending[4].resolve(selection("en", "another-deployment"));
    await wrongVersion;
  });
  assert.equal(rendered(), "Vous avez 2 séances d’apprentissage prévues.");
  assert.equal(tree.root.findByType("select").props.value, "fr");
});

test("catalog versions are deterministic and change when message content changes", async (t) => {
  const paths = await fixture(t);
  const first = await prepareContent(paths);
  const second = await prepareContent(paths);
  assert.equal(first.catalogVersion, second.catalogVersion);
  const envelope = JSON.parse(
    await readFile(
      path.join(
        paths.projectDirectory,
        ".generated/catalogs",
        second.catalogFiles.fr,
      ),
      "utf8",
    ),
  );
  assert.equal(envelope.version, first.catalogVersion);
  assert.equal(envelope.locale, "fr");
  assert.ok(envelope.resources.en[resource]);
  assert.ok(envelope.resources.fr[resource]);
  await editCatalog(paths, (catalog) => {
    catalog["calendar.terms"] += " Merci.";
  });
  const updated = await prepareContent(paths);
  assert.notEqual(updated.catalogVersion, first.catalogVersion);
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
    "Rendez-vous le \u206816 septembre 2026\u2069.",
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
      /arguments, formatting or markup differ/,
    ],
    ["Date {$date :number}", /arguments, formatting or markup differ/],
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

test("catalog syntax failures retain message identity and the parser diagnostic cause", async (t) => {
  const paths = await fixture(t);
  await editCatalog(paths, (catalog) => {
    catalog["calendar.date"] = "Invalid {$date";
  });
  await assert.rejects(prepareContent(paths), (error) => {
    assert.match(error.message, /messages\.mf2_fr\.json#calendar\.date/);
    assert.ok(error.cause instanceof MF2ParseError);
    assert.ok(error.cause instanceof SyntaxError);
    assert.equal(error.cause.diagnostics.length, 1);
    const diagnostic = error.cause.diagnostics[0];
    assert.equal(diagnostic.code, "unclosed-placeholder");
    assert.deepEqual([diagnostic.start, diagnostic.end], [8, 14]);
    assert.ok(error.message.includes(diagnostic.code));
    assert.ok(error.message.includes(diagnostic.message));
    assert.ok(error.message.includes(error.cause.message));
    return true;
  });
});

test("catalog parsing rejects unknown, unbalanced and option-bearing markup", async (t) => {
  for (const message of [
    "{#unknown}Terms{/unknown}",
    "{#strong}Terms{/em}",
    "{#termsLink href=|https://example.com/other|}Terms{/termsLink}",
  ]) {
    const paths = await fixture(t);
    await editCatalog(paths, (catalog) => {
      catalog["calendar.terms"] = message;
    });
    await assert.rejects(prepareContent(paths), /Invalid MF2/);
  }
});

test("catalog contracts reject dropped, added, recast and repeated markup", async (t) => {
  for (const change of [
    (message) =>
      message.replace("{#termsLink}", "").replace("{/termsLink}", ""),
    (message) => `{#em}${message}{/em}`,
    (message) =>
      message.replace("{#strong}", "{#strong /}").replace("{/strong}", ""),
    (message) => message.replace("{#br /}", "{#br /}{#br /}"),
    (message) =>
      message
        .replace("{#strong}", "{#strong}{#strong}")
        .replace("{/strong}", "{/strong}{/strong}"),
  ]) {
    const paths = await fixture(t);
    await editCatalog(paths, (catalog) => {
      catalog["calendar.terms"] = change(catalog["calendar.terms"]);
    });
    await assert.rejects(
      prepareContent(paths),
      /arguments, formatting or markup differ from source: messages\.mf2\.json#calendar\.terms/,
    );
  }
});

test("catalog contracts reject changed parent-child nesting within the same markup shape", async (t) => {
  const paths = await fixture(t);
  await editCatalog(paths, (catalog) => {
    catalog["calendar.terms"] =
      "{#br /}Consultez {#strong}nos{/strong} {#termsLink}conditions{/termsLink}.";
  });
  await assert.rejects(
    prepareContent(paths),
    /translation contract differs.*calendar\.terms/,
  );
});

test("catalog contracts allow added plural branches that repeat source markup shapes", async (t) => {
  const paths = await fixture(t);
  await editCatalog(
    paths,
    (catalog) => {
      catalog["calendar.summary"] =
        ".input {$count :number}\n.match $count\none {{{#strong}{$count} session{/strong}}}\n* {{{#termsLink}{$count} sessions{/termsLink}}}";
    },
    { source: true },
  );
  await editCatalog(paths, (catalog) => {
    catalog["calendar.summary"] =
      ".input {$count :number}\n.match $count\none {{{#strong}{$count} séance{/strong}}}\nmany {{{#termsLink}{$count} séances{/termsLink}}}\n* {{{#termsLink}{$count} séances{/termsLink}}}";
  });
  await assert.doesNotReject(prepareContent(paths));
});

test("catalog preparation rejects replacement or removal of source link bindings", async (t) => {
  for (const change of [
    (message) => message.replaceAll("termsLink", "em"),
    (message) =>
      message.replaceAll("{#termsLink}", "").replaceAll("{/termsLink}", ""),
  ]) {
    const paths = await fixture(t);
    await editCatalog(paths, (catalog) => {
      catalog["calendar.terms"] = change(catalog["calendar.terms"]);
    });
    await assert.rejects(
      prepareContent(paths),
      /(?:translation contract|arguments, formatting or markup) differ.*calendar\.terms/,
    );
  }
});

test("catalog preparation permits sibling markup to reorder within its source link", async (t) => {
  const paths = await fixture(t);
  const sourceFile = path.join(paths.projectDirectory, "content", resource);
  const source = JSON.parse(await readFile(sourceFile, "utf8"));
  source["calendar.terms"] =
    "Read {#termsLink}{#strong}terms{/strong} and {#em}conditions{/em}{/termsLink}.{#br /}Learn more.";
  await writeFile(sourceFile, JSON.stringify(source));
  await editCatalog(paths, (catalog) => {
    catalog["calendar.terms"] =
      "Lisez {#termsLink}{#em}les conditions{/em} et {#strong}les termes{/strong}{/termsLink}.{#br /}En savoir plus.";
  });
  await prepareContent(paths);
  const resources = JSON.parse(
    await readFile(
      path.join(paths.projectDirectory, ".generated/resources.json"),
      "utf8",
    ),
  );
  assert.equal(
    createMessageFormatter(resources, "fr")(resource, "calendar.terms"),
    "Lisez les conditions et les termes.En savoir plus.",
  );
});

test("catalog preparation checks rich markup in added locale plural branches", async (t) => {
  const paths = await fixture(t);
  const sourceFile = path.join(paths.projectDirectory, "content", resource);
  const source = JSON.parse(await readFile(sourceFile, "utf8"));
  source["calendar.summary"] = source["calendar.summary"].replaceAll(
    "{$count}",
    "{#termsLink}{$count}{/termsLink}",
  );
  await writeFile(sourceFile, JSON.stringify(source));
  await editCatalog(paths, (catalog) => {
    catalog["calendar.summary"] = catalog["calendar.summary"].replaceAll(
      "{$count}",
      "{#termsLink}{$count}{/termsLink}",
    );
  });
  await prepareContent(paths);
  await editCatalog(paths, (catalog) => {
    catalog["calendar.summary"] = catalog["calendar.summary"].replace(
      "many {{Vous avez {#termsLink}{$count}{/termsLink}",
      "many {{Vous avez {$count}",
    );
  });
  await assert.rejects(
    prepareContent(paths),
    /(?:translation contract|arguments, formatting or markup) differ.*calendar\.summary/,
  );
});

test("HTML binding rules reject paired br and nested links before publishing and during rendering", async (t) => {
  for (const [message, expected] of [
    ["{#br}Text must survive{/br}", /br must be standalone/],
    [
      "{#termsLink}{#strong}{#termsLink}Terms{/termsLink}{/strong}{/termsLink}",
      /termsLink cannot contain/,
    ],
    ["{#termsLink}{#termsLink /}{/termsLink}", /termsLink cannot contain/],
  ]) {
    const paths = await fixture(t);
    await editCatalog(paths, (catalog) => {
      catalog["calendar.terms"] = message;
    });
    await assert.rejects(prepareContent(paths), expected);
    const model = parseMessage(message, {
      allowedMarkup: Object.keys(messageMarkupRenderers),
    });
    assert.throws(
      () =>
        renderMF2Parts(
          formatMessageToParts(model).parts,
          messageMarkupRenderers,
        ),
      expected,
    );
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
