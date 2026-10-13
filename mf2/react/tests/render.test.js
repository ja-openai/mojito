import assert from "node:assert/strict";
import test from "node:test";
import React from "react";
import { createPortal } from "react-dom";
import { renderToStaticMarkup } from "react-dom/server";
import TestRenderer, { act } from "react-test-renderer";
import {
  createMF2,
  MF2,
  MF2Provider,
  renderMF2Parts,
  useMF2,
} from "@mojito-mf2/react";
import { MF2ParseError, parseMessage } from "@mojito-mf2/react/parser";
import { validateMarkupParts } from "@mojito-mf2/react/markup";
import { createMessages } from "@mojito-mf2/react/bindings";
import { FunctionRegistry } from "@mojito-mf2/core/formatter";
import { createIntlFunctionRegistry } from "@mojito-mf2/core/intl";

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const h = React.createElement;
const html = (value) => renderToStaticMarkup(h(React.Fragment, null, value));
const model = (source, allowedMarkup = []) =>
  parseMessage(source, { allowedMarkup });
const markup = (name, kind, extra = {}) => ({
  type: "markup",
  name,
  kind,
  ...extra,
});
const resourcesFor = (messages) => ({ en: { guide: messages } });
const formatterFor = (messages, options = {}) =>
  createMF2({
    resources: resourcesFor(messages),
    locale: "en",
    bidiIsolation: "none",
    ...options,
  });

test("nested and standalone markup invokes only code-owned bindings", () => {
  let standaloneChildren;
  const formatter = formatterFor({
    terms: model(
      "Read {#termsLink}our {#strong}terms{/strong}{/termsLink}.{#break /}Thanks!",
      ["termsLink", "strong", "break"],
    ),
  });
  const output = formatter.render(
    "guide",
    "terms",
    {},
    {
      termsLink: (children) =>
        h("a", { href: "https://example.com/terms" }, children),
      strong: (children) => h("strong", null, children),
      break: (children) => {
        standaloneChildren = children;
        return h("br");
      },
    },
  );
  assert.equal(
    html(output),
    'Read <a href="https://example.com/terms">our <strong>terms</strong></a>.<br/>Thanks!',
  );
  assert.equal(standaloneChildren, null);
  assert.equal(formatter.format("guide", "terms"), "Read our terms.Thanks!");
});

test("markup render callbacks return components with independent React state", () => {
  function Counter({ children }) {
    const [count, setCount] = React.useState(0);
    return h(
      "button",
      { onClick: () => setCount((value) => value + 1) },
      children,
      `: ${count}`,
    );
  }
  const resources = resourcesFor({
    counter: model("{#counter}{$name}{/counter}", ["counter"]),
  });
  const view = (name) =>
    h(
      MF2Provider,
      { resources, locale: "en", bidiIsolation: "none" },
      h(MF2, {
        resource: "guide",
        name: "counter",
        args: { name },
        markupRenderers: { counter: (children) => h(Counter, null, children) },
      }),
    );
  let renderer;
  act(() => {
    renderer = TestRenderer.create(view("First"));
  });
  try {
    assert.deepEqual(renderer.toJSON().children, ["First", ": 0"]);
    act(() => renderer.root.findByType("button").props.onClick());
    assert.deepEqual(renderer.toJSON().children, ["First", ": 1"]);
    act(() => renderer.update(view("Second")));
    assert.deepEqual(renderer.toJSON().children, ["Second", ": 1"]);
  } finally {
    act(() => renderer.unmount());
  }
});

test("all markup validates before any application callback runs", () => {
  const invalid = [
    [markup("unknown", "standalone")],
    [markup("strong", "close")],
    [markup("strong", "open")],
    [
      markup("strong", "open"),
      markup("link", "open"),
      markup("strong", "close"),
      markup("link", "close"),
    ],
    [markup("strong", "invalid")],
    [
      markup("strong", "standalone", {
        options: { href: { type: "variable", name: "url" } },
      }),
    ],
    [
      markup("strong", "standalone", {
        attributes: { title: { type: "literal", value: "title" } },
      }),
    ],
    [markup("strong", "standalone", { attributes: { hidden: true } })],
  ];
  for (const suffix of invalid) {
    let calls = 0;
    const parts = [markup("strong", "standalone"), ...suffix];
    assert.throws(() =>
      renderMF2Parts(parts, {
        strong: () => {
          calls++;
        },
        link: () => {
          calls++;
        },
      }),
    );
    assert.equal(calls, 0);
  }
  assert.throws(
    () =>
      renderMF2Parts([markup("strong", "standalone")], { strong: "strong" }),
    /Unknown MF2 markup/,
  );
  assert.throws(
    () => renderMF2Parts([markup("toString", "standalone")], {}),
    /Unknown MF2 markup/,
  );
  assert.doesNotThrow(() =>
    validateMarkupParts([markup("strong", "standalone")], ["strong"]),
  );
});

test("syntax errors expose structured diagnostics and actionable source ranges", () => {
  assert.throws(
    () => parseMessage("Hello {$name"),
    (error) => {
      assert.ok(error instanceof SyntaxError);
      assert.ok(error instanceof MF2ParseError);
      assert.equal(error.name, "MF2ParseError");
      assert.deepEqual(error.diagnostics, [
        {
          code: "unclosed-placeholder",
          message: "Placeholder is missing a closing brace.",
          start: 6,
          end: 12,
          severity: "error",
        },
      ]);
      assert.match(error.message, /unclosed-placeholder at 6-12/);
      assert.ok(error.message.includes(error.diagnostics[0].message));
      return true;
    },
  );
});

test("markup policy failures remain distinct from syntax errors", () => {
  assert.throws(
    () => parseMessage("Read {#link}terms{/link}"),
    (error) => {
      assert.ok(error instanceof Error);
      assert.equal(error instanceof MF2ParseError, false);
      assert.equal(error instanceof SyntaxError, false);
      assert.match(error.message, /Unknown MF2 markup: link/);
      return true;
    },
  );
});

test("parser checks markup in every plural variant", () => {
  for (const source of [
    "{#strong}unclosed",
    "{/strong}",
    "{#strong}{#link}crossed{/strong}{/link}",
    "{#link href=|https://example.com/terms|}terms{/link}",
    "{#link @title=|Terms|}terms{/link}",
  ])
    assert.throws(() => model(source, ["strong", "link"]));
  assert.throws(
    () =>
      model(
        ".input {$count :number}\n.match $count\none {{plain}}\n* {{{#unknown /}}}",
      ),
    /Unknown MF2 markup/,
  );
  assert.doesNotThrow(() =>
    model(
      ".input {$count :number}\n.match $count\none {{{#strong}one{/strong}}}\n* {{many}}",
      ["strong"],
    ),
  );
});

test("text and expression strings are escaped by React, never treated as HTML or nodes", () => {
  const formatter = formatterFor({
    safe: model('<script>alert("text")</script> {#strong}{$name}{/strong}', [
      "strong",
    ]),
  });
  const output = html(
    formatter.render(
      "guide",
      "safe",
      { name: '<img src=x onerror="alert(1)">' },
      { strong: (children) => h("strong", null, children) },
    ),
  );
  assert.equal(
    output,
    "&lt;script&gt;alert(&quot;text&quot;)&lt;/script&gt; <strong>&lt;img src=x onerror=&quot;alert(1)&quot;&gt;</strong>",
  );
  assert.equal(output.includes("<script>"), false);
  assert.equal(output.includes("<img"), false);
});

test("missing and invalid arguments retain formatter recovery and report structured context", () => {
  const reports = [];
  const formatter = formatterFor(
    { hello: model("Hello {$name}"), count: model("Count {$n :number}") },
    {
      onError: (error, context) => reports.push({ error, context }),
    },
  );
  assert.equal(formatter.format("guide", "hello"), "Hello {$name}");
  assert.equal(html(formatter.render("guide", "hello")), "Hello {$name}");
  assert.equal(
    formatter.format("guide", "count", { n: "not numeric" }),
    "Count {$n}",
  );
  assert.deepEqual(
    reports.map(({ error }) => error.code),
    ["unresolved-variable", "unresolved-variable", "bad-operand"],
  );
  for (const report of reports) {
    assert.equal(report.error instanceof Error, true);
    assert.deepEqual(report.context, {
      resource: "guide",
      name: report.error.code === "bad-operand" ? "count" : "hello",
      locale: "en",
    });
  }
  assert.equal(formatter.format("guide", "missing"), "guide#missing");
  assert.equal(
    reports.at(-1).error.message,
    "Unknown MF2 message: en/guide#missing",
  );
});

test("React nodes, portals, and object values are rejected as arguments with a useful diagnostic", () => {
  for (const value of [
    h("strong", null, "name"),
    createPortal("name", { nodeType: 1 }),
    ["name"],
    () => "name",
    { name: "reader" },
    Symbol("name"),
  ]) {
    const reports = [];
    const formatter = formatterFor(
      { hello: model("Hello {$name}") },
      { onError: (error) => reports.push(error.message) },
    );
    assert.equal(
      formatter.format("guide", "hello", { name: value }),
      "guide#hello",
    );
    assert.equal(
      html(formatter.render("guide", "hello", { name: value })),
      "guide#hello",
    );
    assert.equal(reports.length, 2);
    assert.ok(reports.every((message) => /use markup bindings/.test(message)));
  }
});

test("source fallback uses the source locale for plurals, numbers, and dates", () => {
  const reports = [];
  const resources = {
    fr: { guide: { count: model("Broken {$missing}") } },
    en: {
      guide: {
        count: model(
          ".input {$n :number}\n.match $n\none {{one item}}\n* {{{$n :number} items}}",
        ),
        total: model("Total {$n :number}"),
        date: model("Date {$date :date dateStyle=full}"),
      },
    },
  };
  const formatter = createMF2({
    resources,
    locale: "fr",
    sourceLocale: "en",
    timeZone: "UTC",
    bidiIsolation: "none",
    onError: (error, context) => reports.push({ error, context }),
  });
  assert.equal(formatter.format("guide", "count", { n: 0 }), "0 items");
  assert.equal(html(formatter.render("guide", "count", { n: 0 })), "0 items");
  assert.equal(
    formatter.format("guide", "total", { n: 1234.5 }),
    `Total ${new Intl.NumberFormat("en").format(1234.5)}`,
  );
  const date = new Date("2026-01-02T03:04:05Z");
  assert.equal(
    formatter.format("guide", "date", { date }),
    `Date ${new Intl.DateTimeFormat("en", { dateStyle: "full", timeZone: "UTC" }).format(date)}`,
  );
  assert.ok(reports.every(({ context }) => context.locale === "fr"));
  assert.equal(reports[0].error.code, "unresolved-variable");
});

test("invalid translated markup falls back without running invalid callbacks", () => {
  const reports = [];
  let calls = 0;
  const formatter = createMF2({
    resources: {
      fr: { guide: { terms: model("{#unknown /}", ["unknown"]) } },
      en: { guide: { terms: model("Read {#link}terms{/link}", ["link"]) } },
    },
    locale: "fr",
    onError: (error, context) => reports.push({ error, context }),
  });
  assert.equal(
    html(
      formatter.render(
        "guide",
        "terms",
        {},
        {
          link: (children) => {
            calls++;
            return h("a", { href: "/terms" }, children);
          },
        },
      ),
    ),
    'Read <a href="/terms">terms</a>',
  );
  assert.equal(calls, 1);
  assert.equal(reports.length, 1);
  assert.match(reports[0].error.message, /Unknown MF2 markup/);
  assert.equal(reports[0].context.locale, "fr");
});

test("callback failures report Error instances and retain source fallback", () => {
  const originalError = new Error("Unsupported binding");
  for (const thrown of ["Invalid binding", null, originalError]) {
    const reports = [];
    const formatter = createMF2({
      resources: {
        fr: { guide: { terms: model("{#link /}", ["link"]) } },
        en: { guide: { terms: model("Read the terms") } },
      },
      locale: "fr",
      onError(error, context) {
        assert.ok(error instanceof Error);
        reports.push({ error, message: error.message, context });
      },
    });
    assert.equal(
      html(
        formatter.render(
          "guide",
          "terms",
          {},
          {
            link() {
              throw thrown;
            },
          },
        ),
      ),
      "Read the terms",
    );
    assert.equal(reports.length, 1);
    assert.equal(
      reports[0].message,
      thrown instanceof Error ? thrown.message : String(thrown),
    );
    assert.deepEqual(reports[0].context, {
      resource: "guide",
      name: "terms",
      locale: "fr",
    });
    if (thrown instanceof Error) assert.equal(reports[0].error, originalError);
  }
});

test("timezones default to UTC and allow provider defaults, explicit options, and inherited options", () => {
  const date = new Date("2026-01-02T01:04:05Z");
  const messages = {
    date: model("{$date :date dateStyle=full}"),
    explicit: model("{$date :date dateStyle=full timeZone=UTC}"),
    inherited: model(
      ".local $zoned = {$date :datetime timeZone=|America/Los_Angeles|}\n.local $copy = {$zoned}\n{{{$copy :date dateStyle=full}}}",
    ),
    override: model(
      ".local $zoned = {$date :datetime timeZone=|America/Los_Angeles|}\n{{{$zoned :date dateStyle=full timeZone=UTC}}}",
    ),
  };
  const expected = (timeZone) =>
    new Intl.DateTimeFormat("en", { dateStyle: "full", timeZone }).format(date);
  const formatter = formatterFor(messages, { timeZone: "Asia/Tokyo" });
  assert.equal(
    formatterFor(messages).format("guide", "date", { date }),
    expected("UTC"),
  );
  assert.equal(
    formatter.format("guide", "date", { date }),
    expected("Asia/Tokyo"),
  );
  assert.equal(
    formatter.format("guide", "explicit", { date }),
    expected("UTC"),
  );
  assert.equal(
    formatter.format("guide", "inherited", { date: date.toISOString() }),
    expected("America/Los_Angeles"),
  );
  assert.equal(
    formatter.format("guide", "override", { date: date.toISOString() }),
    expected("UTC"),
  );
  assert.throws(
    () => formatterFor(messages, { timeZone: "Invalid/Zone" }),
    RangeError,
  );
});

test("rich rendering preserves bidi isolation around expressions and fallback text", () => {
  const formatter = formatterFor(
    {
      name: model("Hello {#strong}{$name}{/strong}!", ["strong"]),
      direction: model("{$name :string u:dir=rtl}"),
    },
    { bidiIsolation: "default" },
  );
  assert.equal(
    html(
      formatter.render(
        "guide",
        "name",
        { name: "مرحبا" },
        { strong: (children) => h("strong", null, children) },
      ),
    ),
    "Hello <strong>\u2068مرحبا\u2069</strong>!",
  );
  assert.equal(
    formatter.format("guide", "name", { name: "مرحبا" }),
    "Hello \u2068مرحبا\u2069!",
  );
  assert.equal(
    html(formatter.render("guide", "direction", { name: "name" })),
    "\u2067name\u2069",
  );
  assert.equal(
    html(
      formatter.render(
        "guide",
        "name",
        {},
        { strong: (children) => h("strong", null, children) },
      ),
    ),
    "Hello <strong>{$name}</strong>!",
  );
});

function freeze(value) {
  Object.freeze(value);
  for (const child of Object.values(value))
    if (child && typeof child === "object") freeze(child);
  return value;
}

test("provider atomically updates immutable catalogs and locale for content and hook-formatted attributes", () => {
  const resources = freeze({
    en: {
      guide: { hello: model("Hello {$name}"), label: model("Open {$name}") },
    },
    fr: {
      guide: {
        hello: model("Bonjour {$name}"),
        label: model("Ouvrir {$name}"),
      },
    },
  });
  function View() {
    const { format, locale } = useMF2();
    return h(
      "button",
      { lang: locale, title: format("guide", "label", { name: "guide" }) },
      h(MF2, { resource: "guide", name: "hello", args: { name: "reader" } }),
    );
  }
  const view = (locale, current = resources) =>
    h(
      MF2Provider,
      { locale, resources: current, bidiIsolation: "none" },
      h(View),
    );
  let renderer;
  act(() => {
    renderer = TestRenderer.create(view("en"));
  });
  assert.deepEqual(renderer.toJSON(), {
    type: "button",
    props: { lang: "en", title: "Open guide" },
    children: ["Hello ", "reader"],
  });
  act(() => {
    renderer.update(view("fr"));
  });
  assert.deepEqual(renderer.toJSON(), {
    type: "button",
    props: { lang: "fr", title: "Ouvrir guide" },
    children: ["Bonjour ", "reader"],
  });
  const replacement = freeze({
    ...resources,
    fr: {
      guide: { hello: model("Salut {$name}"), label: model("Lire {$name}") },
    },
  });
  act(() => {
    renderer.update(view("fr", replacement));
  });
  assert.equal(renderer.toJSON().props.title, "Lire guide");
  assert.deepEqual(renderer.toJSON().children, ["Salut ", "reader"]);
  assert.equal(
    createMF2({ resources, locale: "fr", bidiIsolation: "none" }).format(
      "guide",
      "hello",
      { name: "reader" },
    ),
    "Bonjour reader",
  );
  act(() => renderer.unmount());
});

test("hooks require a provider and inherited object keys cannot resolve catalog messages", () => {
  function View() {
    useMF2();
    return null;
  }
  assert.throws(() => renderToStaticMarkup(h(View)), /requires an MF2Provider/);
  const formatter = formatterFor({});
  assert.equal(formatter.format("guide", "constructor"), "guide#constructor");
  assert.equal(formatter.format("toString", "name"), "toString#name");
});

test("trusted registries extend formatting and retain fallback diagnostics and locale", () => {
  const calls = [];
  const functions = createIntlFunctionRegistry(FunctionRegistry, {
    timeZone: "UTC",
  }).withFunction("app:upper", (call) => {
    calls.push(call.locale);
    if (call.locale === "fr") throw new Error("Custom formatter failed");
    return call.value.toUpperCase();
  });
  const message = model("{$name :app:upper}: {$count :number}");
  const reports = [];
  const formatter = createMF2({
    resources: {
      fr: { guide: { message } },
      en: { guide: { message } },
    },
    locale: "fr",
    functions,
    // The application-owned registry configures its own cache.
    formatterCacheSize: -1,
    bidiIsolation: "none",
    onError: (error, context) => reports.push({ error, context }),
  });
  assert.equal(
    formatter.format("guide", "message", { name: "reader", count: 1234 }),
    "READER: 1,234",
  );
  assert.equal(
    html(formatter.render("guide", "message", { name: "reader", count: 1234 })),
    "READER: 1,234",
  );
  assert.deepEqual(calls, ["fr", "en", "fr", "en"]);
  assert.equal(reports.length, 2);
  assert.ok(reports.every(({ context }) => context.locale === "fr"));
});

test("builtin formatter cache capacity is configurable and validated", (t) => {
  const NativeNumberFormat = Intl.NumberFormat;
  let constructed = 0;
  t.mock.method(Intl, "NumberFormat", function (...args) {
    constructed++;
    return new NativeNumberFormat(...args);
  });
  const messages = { count: model("{$count :number}") };
  const cached = formatterFor(messages, { formatterCacheSize: 1 });
  const uncached = formatterFor(messages, { formatterCacheSize: 0 });
  for (const count of [1, 2, 3]) {
    assert.equal(cached.format("guide", "count", { count }), String(count));
    assert.equal(uncached.format("guide", "count", { count }), String(count));
  }
  assert.equal(constructed, 4);
  assert.throws(
    () => formatterFor(messages, { formatterCacheSize: -1 }),
    RangeError,
  );
});

test("typed hooks remain stable until provider configuration changes, including registries", () => {
  const { useMF2: useMessages } = createMessages("guide");
  const resources = resourcesFor({ hello: model("{$name :app:case}") });
  const upper = FunctionRegistry.portable().withFunction("app:case", (call) =>
    call.value.toUpperCase(),
  );
  const lower = FunctionRegistry.portable().withFunction("app:case", (call) =>
    call.value.toLowerCase(),
  );
  const snapshots = [];
  function View() {
    const formatter = useMessages();
    snapshots.push(formatter);
    return h("p", null, formatter.format("hello", { name: "Reader" }));
  }
  const view = (functions) =>
    h(
      MF2Provider,
      { resources, locale: "en", functions, bidiIsolation: "none" },
      h(View),
    );
  let tree;
  try {
    act(() => {
      tree = TestRenderer.create(view(upper));
    });
    assert.equal(tree.toJSON().children[0], "READER");
    act(() => tree.update(view(upper)));
    assert.equal(snapshots[0], snapshots[1]);
    assert.equal(snapshots[0].format, snapshots[1].format);
    act(() => tree.update(view(lower)));
    assert.equal(tree.toJSON().children[0], "reader");
    assert.notEqual(snapshots[1], snapshots[2]);
  } finally {
    if (tree) act(() => tree.unmount());
  }
});

test("configuration validation rejects malformed locales before formatting", () => {
  const messages = { hello: model("Hello") };
  for (const timeZone of ["UTC", "Europe/Paris"]) {
    for (const locale of ["en_US", "not a locale"]) {
      assert.throws(
        () => formatterFor(messages, { locale, timeZone }),
        RangeError,
      );
      assert.throws(
        () => formatterFor(messages, { sourceLocale: locale, timeZone }),
        RangeError,
      );
    }
  }
  // Well-formed unsupported locales retain Intl fallback behavior.
  assert.equal(
    formatterFor(messages, { locale: "zz" }).format("guide", "hello"),
    "Hello",
  );
});
