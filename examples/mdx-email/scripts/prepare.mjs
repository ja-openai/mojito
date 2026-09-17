import { readdir, readFile, mkdir, writeFile, rm } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { structure, validateModules } from "./validate.mjs";

export async function files(directory, prefix = "") {
  const result = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (entry.isSymbolicLink()) throw new Error("Symlinks are not supported");
    const name = path.posix.join(prefix, entry.name);
    if (entry.isDirectory())
      result.push(...(await files(path.join(directory, entry.name), name)));
    else if (name.endsWith(".mdx")) result.push(name);
  }
  return result.sort();
}

export function plainField(source) {
  const text = source
    .replace(/^\{\/\* mojito-id: [^\n]* \*\/\}\s*$/gm, "")
    .trim();
  if (!text || /[\x00-\x1f\x7f<>{}\[\]*`#]/.test(text))
    throw new Error(
      "Subject and preheader must be a single line of plain text",
    );
  return text;
}

export async function prepare(root, sample = false) {
  const config = JSON.parse(
    await readFile(path.join(root, "email.config.json"), "utf8"),
  );
  const locales = [config.sourceLocale, ...config.targetLocales];
  if (
    new Set(locales).size !== locales.length ||
    locales.some((l) => !/^[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/.test(l)) ||
    !config.emails.length ||
    new Set(config.emails).size !== config.emails.length ||
    config.emails.some((e) => !/^[a-z][a-z0-9-]*$/.test(e))
  )
    throw new Error("Invalid email configuration");
  const sourceFiles = await files(path.join(root, "content"));
  const structures = new Map();
  for (const file of sourceFiles) {
    const text = await readFile(path.join(root, "content", file), "utf8");
    structures.set(file, structure(text, file));
  }
  validateModules(structures);
  const generated = path.join(root, ".generated/content");
  const manifest = {
    ...config,
    locales,
    mode: sample ? "sample" : "pulled",
    fields: {},
  };
  // Validate every input before replacing the previous build inputs.
  const documents = [];
  for (const locale of locales) {
    manifest.fields[locale] = {};
    for (const name of sourceFiles) {
      const source = path.join(root, "content", name);
      const target =
        locale === config.sourceLocale
          ? source
          : path.join(
              root,
              sample ? "translations" : "localized",
              name.replace(/\.mdx$/, `_${locale}.mdx`),
            );
      let text;
      try {
        text = await readFile(target, "utf8");
      } catch (error) {
        if (error.code === "ENOENT")
          throw new Error(
            `Missing ${locale} email content: ${name}. Pull translations before building.`,
          );
        throw error;
      }
      if (
        JSON.stringify(structure(text, name)) !==
        JSON.stringify(structures.get(name))
      ) {
        throw new Error(`Translated module structure changed: ${name}`);
      }
      documents.push({ locale, name, text });
    }
    for (const email of config.emails) {
      const field = (part) =>
        documents.find(
          (d) => d.locale === locale && d.name === `${email}_${part}.mdx`,
        );
      if (!field("subject") || !field("preheader") || !field("body"))
        throw new Error(`Incomplete email: ${email}`);
      manifest.fields[locale][email] = {
        subject: plainField(field("subject").text),
        preheader: plainField(field("preheader").text),
      };
    }
  }
  await rm(generated, { recursive: true, force: true });
  for (const { locale, name, text } of documents) {
    const output = path.join(generated, locale, name);
    await mkdir(path.dirname(output), { recursive: true });
    await writeFile(output, text);
  }
  await mkdir(path.join(root, ".generated"), { recursive: true });
  await writeFile(
    path.join(root, ".generated/manifest.json"),
    JSON.stringify(manifest, null, 2),
  );
  return manifest;
}

if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  if (process.argv.slice(2).some((arg) => arg !== "--sample"))
    throw new Error("Usage: prepare.mjs [--sample]");
  await prepare(
    fileURLToPath(new URL("..", import.meta.url)),
    process.argv.includes("--sample"),
  );
}
