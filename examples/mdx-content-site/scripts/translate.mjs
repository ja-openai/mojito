#!/usr/bin/env node
import { readFile, readdir, mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  readSources,
  createSnapshot,
  missingUnits,
  candidateSchema,
  workerPrompt,
  runCodex,
  validateCandidates,
  stageCandidates,
  assertFreshSources,
  assertFreshTargets,
  mojitoClient,
  fetchBaseline,
  importCandidates,
} from "./translate-worker.mjs";

const projectDirectory = fileURLToPath(new URL("..", import.meta.url));
const sourceDirectory = path.join(projectDirectory, "content");
const [action, ...argv] = process.argv.slice(2);
const options = {};
for (let index = 0; index < argv.length; index += 2) {
  if (
    !argv[index]?.startsWith("--") ||
    !argv[index + 1] ||
    argv[index + 1].startsWith("--")
  )
    throw new Error(`Expected --option value: ${argv[index]}`);
  if (options[argv[index].slice(2)] !== undefined)
    throw new Error(`Duplicate option: ${argv[index]}`);
  options[argv[index].slice(2)] = argv[index + 1];
}
const required = (name) => {
  if (!options[name]) throw new Error(`Missing --${name}`);
  return options[name];
};
const json = async (file) => JSON.parse(await readFile(file, "utf8"));
const client = () =>
  mojitoClient({
    url: process.env.MOJITO_URL || "http://localhost:8080",
    username: process.env.MOJITO_USER,
    password: process.env.MOJITO_PASSWORD,
  });

async function context() {
  const result = [];
  for (const entry of await readdir(path.join(projectDirectory, "src"), {
    withFileTypes: true,
  })) {
    if (entry.isSymbolicLink())
      throw new Error("Symlink component context is unsupported");
    if (entry.isFile() && /\.(jsx|mjs)$/.test(entry.name))
      result.push({
        path: `src/${entry.name}`,
        content: await readFile(
          path.join(projectDirectory, "src", entry.name),
          "utf8",
        ),
      });
  }
  if (JSON.stringify(result).length > 128 * 1024)
    throw new Error("Component context exceeds example limit");
  return result;
}

async function main() {
  switch (action) {
    case "snapshot": {
      const scope = {
        repositoryId: Number(required("repo-id")),
        branchId: Number(required("branch-id")),
        locale: options.locale || "fr",
      };
      const snapshot = createSnapshot({
        ...scope,
        sources: await readSources(sourceDirectory),
        rows: await fetchBaseline(client(), scope),
        context: await context(),
        guidance: options.guidance
          ? await readFile(options.guidance, "utf8")
          : "Use friendly, concise French for a small learning website.",
      });
      const directory = path.resolve(required("out"));
      await mkdir(directory);
      for (const [name, value] of [
        ["snapshot.json", JSON.stringify(snapshot, null, 2)],
        ["prompt.md", workerPrompt(snapshot)],
        ["schema.json", JSON.stringify(candidateSchema(snapshot), null, 2)],
      ])
        await writeFile(path.join(directory, name), `${value}\n`, {
          flag: "wx",
        });
      console.log(
        `Snapshot: ${snapshot.units.length} strings, ${missingUnits(snapshot).length} missing. Existing translations are retained as context. ${directory}`,
      );
      break;
    }
    case "generate": {
      const snapshot = await json(required("snapshot"));
      await assertFreshSources(snapshot, sourceDirectory);
      const candidates = await runCodex({
        snapshot,
        outputFile: path.resolve(required("out")),
        ...(options.codex ? { executable: options.codex } : {}),
        ...(options.model ? { model: options.model } : {}),
      });
      console.log(
        `Generated and validated ${candidates.translations.length} candidates. No Mojito translations changed.`,
      );
      break;
    }
    case "stage": {
      const snapshot = await json(required("snapshot"));
      const candidates = await json(required("candidates"));
      validateCandidates(snapshot, candidates);
      await assertFreshTargets(
        snapshot,
        await fetchBaseline(client(), snapshot),
      );
      const staged = await stageCandidates({
        snapshot,
        candidates,
        sourceDirectory,
        outputDirectory: path.resolve(required("out")),
      });
      console.log(
        `Staged ${staged.candidates.length} REVIEW_NEEDED candidates and localized files. No Mojito translations changed.`,
      );
      break;
    }
    case "import": {
      const snapshot = await json(required("snapshot"));
      const candidates = await json(required("candidates"));
      const report = await importCandidates({
        snapshot,
        candidates,
        sourceDirectory,
        client: client(),
        reportFile: path.resolve(required("report")),
      });
      console.log(
        `Imported ${report.imported.length} candidates as REVIEW_NEEDED. Review them in Mojito, then pull and build.`,
      );
      break;
    }
    case "validate": {
      const snapshot = await json(required("snapshot"));
      validateCandidates(snapshot, await json(required("candidates")));
      await assertFreshSources(snapshot, sourceDirectory);
      console.log(
        "Candidate identities, coverage, source fingerprints, Markdown tokens and MF2 contracts passed.",
      );
      break;
    }
    default:
      console.log(
        `Optional Codex translation worker (en -> fr, missing translations only)\n\nSet MOJITO_URL, MOJITO_USER and MOJITO_PASSWORD for your local demo.\n\nnode scripts/translate.mjs snapshot --repo-id 1 --branch-id 1 --out /tmp/site-translation\nnode scripts/translate.mjs generate --snapshot /tmp/site-translation/snapshot.json --out /tmp/site-candidates.json\nnode scripts/translate.mjs validate --snapshot /tmp/site-translation/snapshot.json --candidates /tmp/site-candidates.json\nnode scripts/translate.mjs stage --snapshot /tmp/site-translation/snapshot.json --candidates /tmp/site-candidates.json --out /tmp/site-stage\nnode scripts/translate.mjs import --snapshot /tmp/site-translation/snapshot.json --candidates /tmp/site-candidates.json --report /tmp/site-import.json\n\nUse --codex /absolute/path/to/codex (or CODEX_BIN), optionally --model MODEL.\nGeneration uses existing local Codex authentication and a read-only, temporary task.\n--guidance FILE on snapshot adds editorial guidance/glossary terms.\nDirectories and output files must be new; none overwrite pulled translations.\nCandidates are separate from fixture translations. The import command writes to Mojito using its atomic fill-missing endpoint, always REVIEW_NEEDED. Existing variants (even blank ones) are never replaced. A conflict stops further rows and records any partial success in the new report file.`,
      );
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
