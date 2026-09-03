import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import {
  FunctionRegistry,
  formatMessage,
  formatMessageToParts,
  parseToModel,
} from "../../javascript/src/index.js";
import { createIntlFunctionRegistry } from "../../javascript/src/intl_functions.js";
import { createServer } from "../../../webapp/frontend/node_modules/vite/dist/node/index.js";

const data = JSON.parse(
  await readFile(new URL("./dataset.json", import.meta.url)),
);
const intl = createIntlFunctionRegistry(FunctionRegistry);
// Fixture-only lookup of caller-supplied forms. This is not an inflection engine.
const availableCase = (raw) =>
  ["vocative", "accusative"].find((key) => raw?.forms?.[key]) ?? "none";
const custom = intl
  .withFunction("ns:hasCase", ({ rawValue }) => availableCase(rawValue))
  .withSelector("ns:hasCase", ({ value, key }) => (key === value ? 0 : null))
  .withFunction(
    "ns:person",
    ({ rawValue, optionValue }) => rawValue.forms[optionValue("case")],
  );

const server = await createServer({
  root: fileURLToPath(new URL("../../../webapp/frontend/", import.meta.url)),
  configFile: false,
  optimizeDeps: { noDiscovery: true, include: [] },
  server: { middlewareMode: true },
  appType: "custom",
});
const report = { messages: [], totalCases: 0, knownMismatches: 0 };
try {
  const { mf2TranslationErrorCount } = await server.ssrLoadModule(
    "/src/components/mf2/translationValidation.ts",
  );
  const { parseMf2, diagnosticsFor } = await server.ssrLoadModule(
    "/src/components/mf2/model.ts",
  );
  for (const message of data.messages) {
    const models = {};
    const editorWarnings = {};
    for (const [locale, source] of Object.entries({
      en: message.source,
      ...message.translations,
    })) {
      const parsed = parseToModel(source);
      assert.deepEqual(
        parsed.diagnostics,
        [],
        `${message.id}/${locale}: syntax`,
      );
      assert.ok(parsed.model, `${message.id}/${locale}: model`);
      models[locale] = parsed.model;
      if (locale !== "en") {
        assert.equal(
          mf2TranslationErrorCount({
            locale,
            source: message.source,
            target: source,
          }),
          0,
          `${message.id}/${locale}: editor/source contract`,
        );
        const original = parseMf2(message.source, {}, locale, {
          includeRuntimeDiagnostics: false,
        });
        const target = parseMf2(source, {}, locale, {
          includeRuntimeDiagnostics: false,
        });
        editorWarnings[locale] = diagnosticsFor(
          original.model,
          target.model,
          target.diagnostics,
          locale,
          original.diagnostics,
        )
          .filter((d) => d.severity === "warning")
          .reduce(
            (counts, d) => ({ ...counts, [d.code]: (counts[d.code] ?? 0) + 1 }),
            {},
          );
      }
    }
    const entry = {
      id: message.id,
      variants: models.en.variants?.length ?? 1,
      targets: Object.keys(message.translations),
      editorWarnings,
      cases: [],
    };
    for (const test of message.cases) {
      const options = {
        locale: test.locale,
        functions: test.custom ? custom : intl,
        bidiIsolation: test.bidi ? "default" : "none",
      };
      const result = formatMessage(models[test.locale], test.args, options);
      assert.deepEqual(
        result.errors,
        [],
        `${message.id}/${test.locale}: runtime`,
      );
      const knownMismatch = Boolean(
        test.knownGap && result.value !== test.expected,
      );
      if (test.expected !== undefined && !test.knownGap)
        assert.equal(
          result.value,
          test.expected,
          `${message.id}/${test.locale}`,
        );
      if (knownMismatch) report.knownMismatches++;
      if (test.startsWith)
        assert.ok(
          result.value.startsWith(test.startsWith),
          `${message.id}/${test.locale}: branch ${JSON.stringify(test.args)}`,
        );
      if (test.markup) {
        const parts = formatMessageToParts(
          models[test.locale],
          test.args,
          options,
        );
        assert.deepEqual(parts.errors, []);
        assert.deepEqual(
          parts.parts.filter((p) => p.type === "markup").map((p) => p.name),
          test.markup,
        );
      }
      if (test.bidi)
        assert.ok(
          /[\u2066-\u2068]/u.test(result.value) &&
            result.value.includes("\u2069"),
          "Bidi isolates",
        );
      const portable = formatMessage(models[test.locale], test.args, {
        locale: test.locale,
      });
      if (test.custom)
        assert.ok(
          portable.hasErrors,
          "Custom example must expose the missing host functions",
        );
      entry.cases.push({
        ...test,
        output: result.value,
        knownMismatch,
        portableErrors: portable.errors.map((error) => error.code),
      });
      report.totalCases++;
    }
    report.messages.push(entry);
  }
  await writeFile(
    new URL("./validation-results.json", import.meta.url),
    JSON.stringify(report, null, 2) + "\n",
  );
  console.log(
    `Checked ${report.messages.length} messages, ${data.messages.reduce((sum, m) => sum + Object.keys(m.translations).length, 0)} translations, ${report.totalCases} runtime cases; ${report.knownMismatches} documented formatting mismatches.`,
  );
} finally {
  await server.close();
}
