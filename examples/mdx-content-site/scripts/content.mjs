import { readdir, readFile, mkdir, writeFile, rm } from "node:fs/promises";
import path from "node:path";
import { prepareMessageResources } from "./mf2-resources.mjs";

export const pages = ["index", "guide"];
export const targetLocale = "fr";
const maxDepth = 3;

async function mdxFiles(directory, prefix = "") {
  const result = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const relative = path.posix.join(prefix, entry.name);
    if (entry.isSymbolicLink())
      throw new Error(`Symlinks are not supported: ${relative}`);
    if (entry.isDirectory()) {
      result.push(
        ...(await mdxFiles(path.join(directory, entry.name), relative)),
      );
    } else if (entry.isFile() && entry.name.endsWith(".mdx")) {
      result.push(relative);
    }
  }
  return result.sort();
}

export function translationPath(file) {
  return file.replace(/\.mdx$/, `_${targetLocale}.mdx`);
}

function importPaths(file, source) {
  const imports = [];
  for (const line of source.split(/\r?\n/)) {
    if (!/^\s*(?:import|export)\b/.test(line)) continue;
    const match = line.match(
      /^import\s+[A-Z][\w]*\s+from\s+(['"])([^'"]+)\1;?\s*$/,
    );
    if (!match)
      throw new Error(`Only static default MDX imports are supported: ${file}`);
    const specifier = match[2];
    if (
      !/^\.\.?\//.test(specifier) ||
      !specifier.endsWith(".mdx") ||
      /[\\%?#:\x00-\x1f]/.test(specifier)
    ) {
      throw new Error(`Invalid module import in ${file}: ${specifier}`);
    }
    const resolved = path.posix.normalize(
      path.posix.join(path.posix.dirname(file), specifier),
    );
    if (resolved.startsWith("../") || resolved.startsWith("/")) {
      throw new Error(`Module import escapes the content directory: ${file}`);
    }
    imports.push(resolved);
  }
  return imports;
}

function validateModules(sources) {
  const graph = new Map(
    [...sources].map(([file, source]) => [file, importPaths(file, source)]),
  );
  for (const file of graph.keys()) {
    const pending = [{ file, ancestors: [] }];
    while (pending.length) {
      const current = pending.pop();
      if (current.ancestors.includes(current.file)) {
        throw new Error(
          `Module cycle: ${[...current.ancestors, current.file].join(" -> ")}`,
        );
      }
      if (current.ancestors.length > maxDepth) {
        throw new Error(`Module depth exceeds ${maxDepth}: ${current.file}`);
      }
      const children = graph.get(current.file);
      if (!children)
        throw new Error(`Missing imported module: ${current.file}`);
      for (const child of children) {
        pending.push({
          file: child,
          ancestors: [...current.ancestors, current.file],
        });
      }
    }
  }
}

// Mojito preserves authored imports while its default export adds a locale suffix.
// Restore original filenames inside a locale directory before compiling any MDX.
export async function assembleContent({
  sourceDirectory,
  translationDirectory,
  generatedDirectory,
}) {
  const files = await mdxFiles(sourceDirectory);
  const source = new Map();
  const translated = new Map();
  for (const file of files) {
    source.set(file, await readFile(path.join(sourceDirectory, file), "utf8"));
    const localizedFile = translationPath(file);
    try {
      translated.set(
        file,
        await readFile(path.join(translationDirectory, localizedFile), "utf8"),
      );
    } catch (error) {
      if (error.code === "ENOENT") {
        throw new Error(
          `Missing French export: ${localizedFile}. Pull all MDX assets before building.`,
        );
      }
      throw error;
    }
  }
  const exportedFiles = await mdxFiles(translationDirectory);
  const expectedFiles = new Set(files.map(translationPath));
  for (const file of exportedFiles) {
    // The CLI demo includes more locales. They may share this export directory,
    // while this website deliberately builds only French. Still reject stale assets.
    const sourceFile = file.replace(
      /_[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*\.mdx$/,
      ".mdx",
    );
    if (
      !expectedFiles.has(file) &&
      (sourceFile === file || !source.has(sourceFile))
    )
      throw new Error(
        `Unexpected MDX export: ${file}. Use a clean pull directory.`,
      );
  }
  validateModules(source);
  validateModules(translated);
  for (const file of files) {
    if (
      JSON.stringify(importPaths(file, source.get(file))) !==
      JSON.stringify(importPaths(file, translated.get(file)))
    ) {
      throw new Error(`French module imports differ from the source: ${file}`);
    }
  }
  const messages = await prepareMessageResources({
    sourceDirectory,
    translationDirectory,
    source,
    translated,
  });
  await rm(generatedDirectory, { recursive: true, force: true });
  for (const [locale, documents] of [
    ["en", source],
    [targetLocale, translated],
  ]) {
    for (const [file, text] of documents) {
      const destination = path.join(generatedDirectory, locale, file);
      await mkdir(path.dirname(destination), { recursive: true });
      await writeFile(destination, text);
    }
  }
  await writeFile(
    path.join(generatedDirectory, "resources.json"),
    `${JSON.stringify(messages.resources)}\n`,
  );
  return { source, translated, files, catalogs: messages.files };
}

export async function prepareContent({
  projectDirectory,
  translationDirectory,
  sample = false,
}) {
  const documents = await assembleContent({
    sourceDirectory: path.join(projectDirectory, "content"),
    translationDirectory,
    generatedDirectory: path.join(projectDirectory, ".generated"),
  });
  const titles = {};
  for (const [locale, assets] of [
    ["en", documents.source],
    [targetLocale, documents.translated],
  ]) {
    titles[locale] = Object.fromEntries(
      [...assets].map(([file, source]) => [
        file,
        source.match(/^#{1,6} (.+)$/m)?.[1] ?? path.basename(file),
      ]),
    );
  }
  const manifest = {
    mode: sample ? "sample" : "pulled",
    locales: ["en", targetLocale],
    targetLocale,
    pages,
    assets: documents.files,
    catalogs: documents.catalogs,
    titles,
  };
  await writeFile(
    path.join(projectDirectory, ".generated/manifest.json"),
    `${JSON.stringify(manifest, null, 2)}\n`,
  );
  return manifest;
}
