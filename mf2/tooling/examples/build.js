import { readFile, writeFile, mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { compileCatalog, checkCatalog, generateTypes } from "../src/index.js";

const read = async (locale) =>
  JSON.parse(
    await readFile(new URL(`./${locale}.mf2.json`, import.meta.url), "utf8"),
  );
const source = await read("en");
const target = await read("fr");
const checked = checkCatalog(source, target, { targetLocale: "fr" });
if (!checked.ok) throw new Error(JSON.stringify(checked.diagnostics));
const resources = {};
for (const [locale, catalog] of [
  ["en", source],
  ["fr", target],
]) {
  const compiled = compileCatalog(catalog);
  if (!compiled.ok) throw new Error(JSON.stringify(compiled.diagnostics));
  resources[locale] = { "shop.mf2.json": compiled.artifact.messages };
}
const output = new URL("./dist/", import.meta.url);
await mkdir(output, { recursive: true });
await writeFile(
  new URL("resources.json", output),
  JSON.stringify(resources, null, 2) + "\n",
);
const types = generateTypes(source, { resource: "shop.mf2.json" });
if (!types.ok) throw new Error(JSON.stringify(types.diagnostics));
await writeFile(new URL("../messages.ts", output), types.code);
console.log(
  `Checked and compiled English/French catalogs: ${fileURLToPath(output)}`,
);
