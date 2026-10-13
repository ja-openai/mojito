import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { parseMessage } from "@mojito-mf2/react/parser";
import { FunctionRegistry } from "@mojito-mf2/core/formatter";
import { createIntlFunctionRegistry } from "@mojito-mf2/core/intl";
import { analyzeModel, checkContracts } from "@mojito-mf2/tooling";
import { createMessageFormatter } from "../src/mf2-runtime.mjs";

const functions = createIntlFunctionRegistry(FunctionRegistry);
const maxCatalogBytes = 1_000_000;
const allowedMarkup = ["strong", "em", "termsLink", "br"];

async function catalogFiles(directory, prefix = "", translated = false) {
  const result = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const relative = path.posix.join(prefix, entry.name);
    if (entry.isSymbolicLink())
      throw new Error(`Symlinks are not supported: ${relative}`);
    if (entry.isDirectory())
      result.push(
        ...(await catalogFiles(
          path.join(directory, entry.name),
          relative,
          translated,
        )),
      );
    else if (
      entry.isFile() &&
      entry.name.endsWith(translated ? ".mf2_fr.json" : ".mf2.json")
    )
      result.push(relative);
  }
  return result.sort();
}

function visit(value, callback) {
  if (!value || typeof value !== "object") return;
  callback(value);
  for (const child of Object.values(value)) visit(child, callback);
}

// The example deliberately keeps the formatting contract source-owned: targets
// can reorder expressions and add plural branches, but cannot change inputs,
// functions or their options. A production adapter could expose finer policy.
export function modelContract(model) {
  // These restrictions belong to the website's HTML bindings. Generic MF2
  // permits nesting, but HTML anchors cannot nest and br cannot have children.
  const patterns =
    model.type === "select"
      ? model.variants.map((variant) => variant.value)
      : [model.pattern];
  const markupShapes = new Set();
  for (const pattern of patterns) {
    let inLink = false;
    const markup = [];
    for (const part of pattern) {
      if (part.type !== "markup") continue;
      if (part.name === "br" && part.kind !== "standalone")
        throw new Error("br must be standalone MF2 markup");
      if (part.name === "termsLink") {
        if (part.kind !== "close" && inLink)
          throw new Error("termsLink cannot contain another link");
        if (part.kind === "open") inLink = true;
        else if (part.kind === "close") inLink = false;
      }
      if (part.kind !== "close") markup.push([part.name, part.kind]);
    }
    // Preserve names, kinds and counts within each source-approved shape.
    // Repeated shapes allow locale-added plural branches; selector equivalence
    // and nesting are separate from this order-independent contract.
    markupShapes.add(JSON.stringify(markup.sort()));
  }
  const locals = new Set(
    model.declarations.filter((d) => d.type === "local").map((d) => d.name),
  );
  const variables = new Set();
  const annotations = new Set();
  visit(model, (node) => {
    if (node.type === "variable" && !locals.has(node.name))
      variables.add(node.name);
    if (node.type === "expression" && node.function) {
      if (!functions.hasFormatter(node.function))
        throw new Error(`Unsupported MF2 function: ${node.function.name}`);
      const options = Object.entries(node.function.options ?? {}).sort(
        ([a], [b]) => a.localeCompare(b),
      );
      annotations.add(JSON.stringify([node.arg, node.function.name, options]));
      if (
        ["date", "time", "datetime"].includes(node.function.name) &&
        node.function.options?.timeZone?.value !== "UTC"
      )
        throw new Error(
          "MF2 date/time examples must explicitly use timeZone=UTC",
        );
    }
  });
  return {
    arguments: [...variables].sort(),
    annotations: [...annotations].sort(),
    markupShapes: [...markupShapes].sort(),
  };
}

async function readCatalog(filename) {
  const text = await readFile(filename, "utf8");
  if (Buffer.byteLength(text) > maxCatalogBytes)
    throw new Error(`MF2 catalog is too large: ${filename}`);
  const source = JSON.parse(text);
  if (
    !source ||
    Array.isArray(source) ||
    typeof source !== "object" ||
    Object.keys(source).length > 1000
  )
    throw new Error(`MF2 catalog must be a bounded object: ${filename}`);
  const models = Object.create(null);
  for (const [name, value] of Object.entries(source)) {
    if (
      !/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/.test(name) ||
      typeof value !== "string" ||
      value.length > 10000
    )
      throw new Error(`Invalid MF2 catalog entry: ${filename}#${name}`);
    let model;
    try {
      model = parseMessage(value, { allowedMarkup });
    } catch (error) {
      throw new Error(`Invalid MF2: ${filename}#${name}: ${error.message}`, {
        cause: error,
      });
    }
    modelContract(model);
    models[name] = model;
  }
  return models;
}

export function messageReferences(file, text) {
  const references = [];
  const pattern =
    /^\s*<PreviewMessage resource="([^"]+)" name="([A-Za-z][A-Za-z0-9_.-]{0,127})" args='([^']*)'\s*\/>\s*$/;
  for (const line of text.split(/\r?\n/)) {
    if (!line.includes("<PreviewMessage")) continue;
    const match = line.match(pattern);
    if (!match || line.includes("&"))
      throw new Error(`Invalid PreviewMessage adapter in ${file}`);
    const [, resource, name, serializedArgs] = match;
    if (
      resource.length > 512 ||
      !/^\.\.?\//.test(resource) ||
      !resource.endsWith(".mf2.json") ||
      /[\\%?#:\x00-\x1f]/.test(resource)
    )
      throw new Error(`Invalid MF2 resource in ${file}: ${resource}`);
    const resolved = path.posix.normalize(
      path.posix.join(path.posix.dirname(file), resource),
    );
    if (resolved.startsWith("../") || resolved.startsWith("/"))
      throw new Error(`MF2 resource escapes the content directory: ${file}`);
    if (serializedArgs.length > 4096)
      throw new Error(`PreviewMessage args are too large: ${file}`);
    const args = JSON.parse(serializedArgs);
    if (
      !args ||
      Array.isArray(args) ||
      typeof args !== "object" ||
      Object.keys(args).length > 16 ||
      Object.entries(args).some(
        ([key, value]) =>
          !/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(key) ||
          !["string", "number", "boolean"].includes(typeof value) ||
          (typeof value === "string" && value.length > 512) ||
          (typeof value === "number" && !Number.isFinite(value)),
      )
    )
      throw new Error(
        `PreviewMessage args must be a bounded scalar object: ${file}`,
      );
    references.push({
      resource: resolved,
      name,
      args,
      original: line,
      authoredResource: resource,
    });
  }
  return references;
}

export async function prepareMessageResources({
  sourceDirectory,
  translationDirectory,
  source,
  translated,
}) {
  const files = await catalogFiles(sourceDirectory);
  const expected = files.map((file) => file.replace(/\.json$/, "_fr.json"));
  const exported = await catalogFiles(translationDirectory, "", true);
  if (exported.some((file) => !expected.includes(file)))
    throw new Error("Unexpected MF2 export. Use a clean pull directory.");
  const resources = { en: Object.create(null), fr: Object.create(null) };
  for (const file of files) {
    resources.en[file] = await readCatalog(path.join(sourceDirectory, file));
    const target = file.replace(/\.json$/, "_fr.json");
    try {
      resources.fr[file] = await readCatalog(
        path.join(translationDirectory, target),
      );
    } catch (error) {
      if (error.code === "ENOENT")
        throw new Error(
          `Missing French MF2 export: ${target}. Pull all catalogs before building.`,
        );
      throw error;
    }
    const originals = resources.en[file];
    const targets = resources.fr[file];
    if (
      JSON.stringify(Object.keys(originals).sort()) !==
      JSON.stringify(Object.keys(targets).sort())
    )
      throw new Error(`French MF2 catalog keys differ from source: ${file}`);
    for (const name of Object.keys(originals)) {
      if (
        JSON.stringify(modelContract(originals[name])) !==
        JSON.stringify(modelContract(targets[name]))
      )
        throw new Error(
          `French MF2 arguments, formatting or markup differ from source: ${file}#${name}`,
        );
      // This site's strict build profile preserves declarations, formatting and
      // each branch's rich markup while allowing locale-specific plural forms.
      // Reuse compiled models; translation validation must not reparse strings.
      const sourceAnalysis = analyzeModel(originals[name]);
      const targetAnalysis = analyzeModel(targets[name]);
      const diagnostics = [
        ...sourceAnalysis.diagnostics,
        ...targetAnalysis.diagnostics,
      ];
      if (sourceAnalysis.ok && targetAnalysis.ok)
        diagnostics.push(
          ...checkContracts(sourceAnalysis.contract, targetAnalysis.contract, {
            targetLocale: "fr",
          }).diagnostics,
        );
      if (diagnostics.some((diagnostic) => diagnostic.severity === "error"))
        throw new Error(
          `French MF2 translation contract differs from source: ${file}#${name}: ${diagnostics.map((diagnostic) => diagnostic.message).join(" ")}`,
        );
    }
  }
  for (const [file, text] of source) {
    const references = messageReferences(file, text);
    const targetReferences = messageReferences(file, translated.get(file));
    const contract = (refs) =>
      refs.map(({ resource, name, args }) => ({ resource, name, args }));
    if (
      JSON.stringify(contract(references)) !==
      JSON.stringify(contract(targetReferences))
    )
      throw new Error(
        `French PreviewMessage references differ from source: ${file}`,
      );
    for (const [locale, documents, refs] of [
      ["en", source, references],
      ["fr", translated, targetReferences],
    ]) {
      const format = createMessageFormatter(resources, locale);
      let generated = documents.get(file);
      for (const ref of refs) {
        const model = resources[locale][ref.resource]?.[ref.name];
        if (!model)
          throw new Error(
            `Unknown MF2 reference: ${file} -> ${ref.resource}#${ref.name}`,
          );
        if (
          JSON.stringify(Object.keys(ref.args).sort()) !==
          JSON.stringify(modelContract(model).arguments)
        )
          throw new Error(
            `PreviewMessage args do not match MF2 inputs: ${file}#${ref.name}`,
          );
        format(ref.resource, ref.name, ref.args);
        generated = generated.replace(
          ref.original,
          ref.original.replace(
            `resource="${ref.authoredResource}"`,
            `resource="${ref.resource}"`,
          ),
        );
      }
      documents.set(file, generated);
    }
  }
  return { resources, files };
}
