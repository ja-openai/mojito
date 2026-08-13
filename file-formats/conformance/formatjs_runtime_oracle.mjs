#!/usr/bin/env node

import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = path.dirname(fileURLToPath(import.meta.url));
const moduleRoot = process.argv[2];
if (!moduleRoot) {
  throw new Error("Pass an installed frontend node_modules directory");
}

const { IntlMessageFormat } = await import(
  pathToFileURL(path.join(moduleRoot, "intl-messageformat", "index.js"))
);
const { parse, isTagElement } = await import(
  pathToFileURL(
    path.join(moduleRoot, "@formatjs", "icu-messageformat-parser", "index.js"),
  )
);

const manifest = JSON.parse(
  readFileSync(path.join(root, "manifest.json"), "utf8"),
);
const cardinal = JSON.parse(
  readFileSync(path.join(root, "cldr-cardinal-categories.v1.json"), "utf8"),
);
if (
  cardinal.schemaVersion !== 1 ||
  cardinal.cldrVersion !== "46" ||
  cardinal.unicodeVersion !== "16.0.0" ||
  cardinal.source !==
    "https://github.com/unicode-org/cldr-json/blob/46.0.0/cldr-json/cldr-core/supplemental/plurals.json"
) {
  throw new Error("Pinned CLDR provenance changed without an approved upgrade");
}
let cldrLocales = 0;
for (const [locale, expected] of Object.entries(cardinal.cardinalCategories)) {
  if (locale === "und") {
    if (Intl.PluralRules.supportedLocalesOf([locale]).length !== 0) {
      throw new Error("Undefined CLDR root cannot be a user target locale");
    }
    continue;
  }
  if (Intl.PluralRules.supportedLocalesOf([locale]).length !== 1) {
    throw new Error(`${locale}: pinned CLDR locale is unsupported by Node ICU`);
  }
  const actual = new Intl.PluralRules(locale)
    .resolvedOptions()
    .pluralCategories.toSorted();
  if (JSON.stringify(actual) !== JSON.stringify(expected.toSorted())) {
    throw new Error(
      `${locale}: pinned CLDR categories ${JSON.stringify(expected)} ` +
        `differ from actual Node ICU categories ${JSON.stringify(actual)}`,
    );
  }
  cldrLocales++;
}
for (const [locale, zeroCategory] of [
  ["pt-BR", "one"],
  ["pt-PT", "other"],
]) {
  if (Intl.PluralRules.supportedLocalesOf([locale]).length !== 1) {
    throw new Error(`${locale}: regional Portuguese locale is unsupported`);
  }
  const actual = new Intl.PluralRules(locale).select(0);
  if (actual !== zeroCategory) {
    throw new Error(
      `${locale}: zero must select ${zeroCategory}, not ${actual}`,
    );
  }
}
let catalogs = 0;
let messages = 0;
let selections = 0;
let fractionalSelections = 0;
let validPatterns = 0;
let preservedMarkup = 0;
let xcodeSubstitutionSelections = 0;
let foundationPluralSelections = 0;
let foundationStringSelections = 0;
let androidPluralSelections = 0;
let domainSelections = 0;
let futureDeviceSelections = 0;
const extendedGettextSamples = [
  1001, 1002, 1010, 1011, 1100, 10000, 100000, 999999, 1000000, 1000001,
  1000002, 2000000, 1000000000,
];

for (const fixture of [
  ...manifest.cases,
  ...(manifest.androidOverlays ?? []).map((overlay) => ({
    ...overlay,
    format: "android",
  })),
]) {
  if (!fixture.expected) {
    continue;
  }
  const catalog = JSON.parse(
    readFileSync(path.join(root, fixture.expected), "utf8"),
  );
  let checkedCatalog = false;
  for (const [id, descriptor] of Object.entries(catalog.messages)) {
    const ast = parse(descriptor.defaultMessage, { requiresOtherClause: true });
    validPatterns++;
    for (const [sampleField, label] of [
      ["xcstringsRuntimeSamples", "Xcode substitution"],
      ["appleStringsRuntimeSamples", "Foundation string"],
      ["appleStringsdictRuntimeSamples", "Foundation plural"],
      ["androidRuntimeSamples", "Android plural"],
    ]) {
      for (const sample of fixture[sampleField] ?? []) {
        if (sample.message !== id) {
          continue;
        }
        const actual = new IntlMessageFormat(
          descriptor.defaultMessage,
          catalog.locale ?? "en",
        ).format(sample.values);
        if (actual !== sample.expected) {
          throw new Error(
            `${fixture.id}/${id}: ${label} ${JSON.stringify(sample.values)} ` +
              `expected ${JSON.stringify(sample.expected)}, got ${JSON.stringify(actual)}`,
          );
        }
        if (sampleField === "xcstringsRuntimeSamples") {
          xcodeSubstitutionSelections++;
        } else if (sampleField === "appleStringsRuntimeSamples") {
          foundationStringSelections++;
        } else if (sampleField === "androidRuntimeSamples") {
          androidPluralSelections++;
        } else {
          foundationPluralSelections++;
        }
      }
    }
    if (
      descriptor.metadata?.androidMarkupEscaping === "icu-quoted-angle" ||
      descriptor.metadata?.appleMarkupEscaping === "icu-quoted-angle"
    ) {
      const values = { count: 2 };
      for (const placeholder of descriptor.placeholders ?? []) {
        values[placeholder.name] =
          placeholder.kind === "integer" || placeholder.kind === "number"
            ? 2
            : "sample";
      }
      addTagHandlers(ast, values);
      const actual = new IntlMessageFormat(
        descriptor.defaultMessage,
        catalog.locale ?? "en",
      ).format(values);
      const originalMarkup = descriptor.defaultMessage
        .replaceAll("'<'", "<")
        .replaceAll("''", "'");
      const expected = new IntlMessageFormat(
        originalMarkup,
        catalog.locale ?? "en",
        undefined,
        { ignoreTag: true },
      ).format(values);
      if (actual !== expected) {
        throw new Error(
          `${fixture.id}/${id}: native literal markup changed during ICU escaping; ` +
            `expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`,
        );
      }
      preservedMarkup++;
    }

    if (fixture.format !== "gettext_po") {
      continue;
    }
    const expression = descriptor.metadata?.gettextPluralForms?.expression;
    if (!expression) {
      continue;
    }
    if (!/^[n\d\s?:|&=!<>+*/%()\-]+$/.test(expression)) {
      throw new Error(`${fixture.id}/${id}: unsafe fixture plural expression`);
    }

    const messageLocale =
      descriptor.metadata?.gettextDomainHeader?.locale ?? catalog.locale;
    const formatted = new IntlMessageFormat(
      descriptor.defaultMessage,
      messageLocale,
    );
    const variants = new Map(
      Object.entries(descriptor.variants).map(([selector, text]) => [
        selector,
        new IntlMessageFormat(text, messageLocale),
      ]),
    );
    const integerExpression = expression.replace(
      /\b\d+\b/g,
      (digits) => `${digits.replace(/^0+(?=\d)/u, "")}n`,
    );
    const evaluate = new Function(
      "n",
      `"use strict"; return (${integerExpression});`,
    );
    const integerSamples = new Set([
      ...Array.from({ length: 1001 }, (_, sample) => sample),
      ...extendedGettextSamples,
      ...(fixture.gettextRuntimeSamples ?? []),
    ]);
    for (const sample of integerSamples) {
      const index = String(Number(evaluate(BigInt(sample))));
      const selector = descriptor.metadata.gettextPluralIndexes[index];
      const expected = variants.get(selector);
      if (!expected) {
        throw new Error(
          `${fixture.id}/${id}: no variant for gettext index ${index}`,
        );
      }
      const values = { count: sample };
      for (const placeholder of descriptor.placeholders ?? []) {
        values[placeholder.name] = sample;
      }
      const actualText = formatted.format(values);
      const expectedText = expected.format(values);
      if (actualText !== expectedText) {
        throw new Error(
          `${fixture.id}/${id}: n=${sample}, gettext index=${index}, ` +
            `expected ${JSON.stringify(expectedText)}, got ${JSON.stringify(actualText)}`,
        );
      }
      selections++;
    }
    for (const sample of fixture.gettextFractionalSamples ?? []) {
      const selector =
        descriptor.metadata.gettextPluralIndexes[String(sample.index)];
      const expected = variants.get(selector);
      if (!expected) {
        throw new Error(
          `${fixture.id}/${id}: no variant for fractional index ${sample.index}`,
        );
      }
      const values = { count: sample.value };
      for (const placeholder of descriptor.placeholders ?? []) {
        values[placeholder.name] = sample.value;
      }
      const actualText = formatted.format(values);
      const expectedText = expected.format(values);
      if (actualText !== expectedText) {
        throw new Error(
          `${fixture.id}/${id}: fractional n=${sample.value}, ` +
            `expected ${JSON.stringify(expectedText)}, got ${JSON.stringify(actualText)}`,
        );
      }
      fractionalSelections++;
    }
    for (const sample of fixture.gettextDomainRuntimeSamples ?? []) {
      if (
        (descriptor.metadata.gettextDomain ?? "messages") !== sample.domain ||
        descriptor.metadata.sourceMessage !== sample.message
      ) {
        continue;
      }
      const values = { count: sample.value };
      for (const placeholder of descriptor.placeholders ?? []) {
        values[placeholder.name] = sample.value;
      }
      const actual = formatted.format(values);
      if (actual !== sample.expected) {
        throw new Error(
          `${fixture.id}/${id}: domain ${sample.domain}, n=${sample.value}, ` +
            `expected ${JSON.stringify(sample.expected)}, got ${JSON.stringify(actual)}`,
        );
      }
      domainSelections++;
    }
    checkedCatalog = true;
    messages++;
  }
  if (checkedCatalog) {
    catalogs++;
  }
}

for (const fixture of manifest.sourceSkeletons ?? []) {
  if (!fixture.xcstringsFirstLocaleFutureDevices) {
    continue;
  }
  const translations = JSON.parse(
    readFileSync(path.join(root, fixture.translations), "utf8"),
  );
  const original = JSON.parse(
    readFileSync(path.join(root, fixture.input), "utf8"),
  );
  const localized = JSON.parse(
    readFileSync(path.join(root, fixture.localized), "utf8"),
  );
  for (const [id, translation] of Object.entries(translations)) {
    const ast = parse(translation, { requiresOtherClause: true });
    if (ast.length !== 1 || ast[0].value !== "device") {
      throw new Error(
        `${fixture.id}/${id}: first-target device select is incomplete`,
      );
    }
    const source =
      original.strings[id].localizations[original.sourceLanguage].variations
        .device;
    const actual = new Set(Object.keys(ast[0].options));
    const required = new Set([...Object.keys(source), "other"]);
    if (
      actual.size !== required.size ||
      [...required].some((device) => !actual.has(device))
    ) {
      throw new Error(
        `${fixture.id}/${id}: FormatJS device keywords lost source ownership`,
      );
    }
    const message = new IntlMessageFormat(
      translation,
      fixture.xcstringsFormattingLocale,
    );
    const targets =
      localized.strings[id].localizations[fixture.xcstringsTargetLocale]
        .variations.device;
    for (const device of actual) {
      const target = targets[device] ?? targets.iphone;
      for (const count of target.stringUnit ? [0] : [1, 2, 5]) {
        const value = target.stringUnit
          ? target.stringUnit.value
          : target.variations.plural[
              new Intl.PluralRules(fixture.xcstringsFormattingLocale).select(
                count,
              )
            ].stringUnit.value;
        const expected = value.replace(
          /%([123])\$(lld|@|n)/g,
          (_, position, conversion) =>
            conversion === "n"
              ? ""
              : target.stringUnit
                ? "Rowan"
                : position === "1"
                  ? String(count)
                  : "Rowan",
        );
        const rendered = message.format({
          device,
          arg0: "Rowan",
          arg1: "Rowan",
          count,
        });
        if (rendered !== expected) {
          throw new Error(
            `${fixture.id}/${id}/${device}: FormatJS produced ` +
              `${JSON.stringify(rendered)}, expected ${JSON.stringify(expected)}`,
          );
        }
        futureDeviceSelections++;
      }
    }
  }
}

console.log(
  `FormatJS verified ${cldrLocales} pinned CLDR target locales, ` +
    `2 distinct Portuguese regional zero selections, ` +
    `${validPatterns} canonical ICU messages, ` +
    `${preservedMarkup} lossless native markup renderings, ` +
    `${xcodeSubstitutionSelections} Xcode substitution selections, ` +
    `${foundationStringSelections} Foundation string selections, ` +
    `${domainSelections} native-domain gettext selections, ` +
    `${foundationPluralSelections} Foundation plural selections, ` +
    `${androidPluralSelections} Android plural selections, ` +
    `${futureDeviceSelections} opaque Xcode device selections, and ${messages} plural ` +
    `messages from ${catalogs} gettext fixtures (${selections} integer and ` +
    `${fractionalSelections} fractional runtime selections).`,
);

function addTagHandlers(elements, values) {
  for (const element of elements) {
    if (isTagElement(element)) {
      values[element.value] = (children) =>
        `<${element.value}>${children.join("")}</${element.value}>`;
      addTagHandlers(element.children, values);
    }
    for (const option of Object.values(element.options ?? {})) {
      addTagHandlers(option.value, values);
    }
  }
}
