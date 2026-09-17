import assert from "node:assert/strict";
import {
  mkdtemp,
  mkdir,
  writeFile,
  readFile,
  rm,
  chmod,
} from "node:fs/promises";
import path from "node:path";
import os from "node:os";
import test from "node:test";
import {
  readSources,
  createSnapshot,
  missingUnits,
  workerPrompt,
  validateCandidates,
  validateTarget,
  stageCandidates,
  assertFreshSources,
  assertFreshTargets,
  runCodex,
  fetchBaseline,
  mojitoClient,
  mdxSegments,
  catalogSegments,
  importCandidates,
} from "./translate-worker.mjs";

const summary =
  ".input {$count :integer}\n.match $count\none {{You have {$count} session.}}\n* {{You have {$count} sessions.}}";
const translatedSummary =
  ".input {$count :integer}\n.match $count\none {{Vous avez {$count} séance.}}\nmany {{Vous avez {$count} séances.}}\n* {{Vous avez {$count} séances.}}";
async function fixture(t) {
  const directory = await mkdtemp(
    path.join(os.tmpdir(), "mojito-worker-test-"),
  );
  t.after(() => rm(directory, { recursive: true, force: true }));
  const sourceDirectory = path.join(directory, "content");
  await mkdir(sourceDirectory);
  await writeFile(
    path.join(sourceDirectory, "index.mdx"),
    "import Shared from './modules/Shared.mdx';\n\n{/* mojito-id: title */}\n# A small website\n\n{/* mojito-id: intro */}\nRead the [guide](guide.html) and use `notes.md`.\n\n<Shared />\n",
  );
  await mkdir(path.join(sourceDirectory, "modules"));
  await writeFile(
    path.join(sourceDirectory, "modules/Shared.mdx"),
    "{/* mojito-id: title */}\nA shared note.\n",
  );
  await writeFile(
    path.join(sourceDirectory, "messages.mf2.json"),
    JSON.stringify({
      summary,
      date: "On {$date :date dateStyle=long timeZone=UTC}.",
    }),
  );
  const sources = await readSources(sourceDirectory);
  const rows = sources.flatMap((asset, assetIndex) =>
    asset.segments.map((segment, index) => ({
      assetPath: asset.assetPath,
      name: segment.name,
      source: segment.source,
      tmTextUnitId: assetIndex * 10 + index + 1,
      assetId: assetIndex + 1,
      localeId: 2,
      targetLocale: "fr",
      branchId: 1,
      assetExtractionId: assetIndex + 100,
      branchAssetExtractionId: assetIndex + 200,
      branchAssetContentMd5: asset.md5,
      used: true,
      target:
        asset.assetPath === "modules/Shared.mdx"
          ? "Une correction humaine."
          : null,
      tmTextUnitVariantId:
        asset.assetPath === "modules/Shared.mdx" ? 123 : null,
      status: asset.assetPath === "modules/Shared.mdx" ? "APPROVED" : null,
    })),
  );
  const snapshot = createSnapshot({
    sources,
    rows,
    repositoryId: 1,
    branchId: 1,
    context: [{ path: "src/App.jsx", content: "// component context" }],
  });
  const targets = {
    title: "Un petit site",
    intro: "Lisez le [guide](guide.html) et utilisez `notes.md`.",
    summary: translatedSummary,
    date: "Le {$date :date dateStyle=long timeZone=UTC}.",
  };
  const candidates = {
    snapshotHash: snapshot.snapshotHash,
    locale: "fr",
    translations: missingUnits(snapshot).map(
      ({ assetPath, name, sourceSha256 }) => ({
        assetPath,
        name,
        sourceSha256,
        target: targets[name],
      }),
    ),
  };
  return { directory, sourceDirectory, sources, rows, snapshot, candidates };
}

function baselineResponse(url, rows, repositoryId = 1) {
  if (!url.includes("/translation-candidates/source?")) return rows;
  const params = new URL(url, "http://localhost").searchParams;
  const row = rows.find(
    (item) => item.assetId === Number(params.get("assetId")),
  );
  return {
    repositoryId,
    branchId: Number(params.get("branchId")),
    assetId: row.assetId,
    assetPath: row.assetPath,
    assetExtractionId: row.branchAssetExtractionId,
    assetContentMd5: row.branchAssetContentMd5,
  };
}

test("snapshot keys repeated names by asset and carries full modules, components and human targets", async (t) => {
  const { snapshot } = await fixture(t);
  assert.equal(snapshot.units.length, 5);
  assert.equal(missingUnits(snapshot).length, 4);
  assert.ok(
    snapshot.units.every((unit) => unit.expectedAssetExtractionId >= 200),
  );
  const prompt = workerPrompt(snapshot);
  assert.match(prompt, /Une correction humaine/);
  assert.match(prompt, /component context/);
  assert.match(prompt, /import Shared/);
  assert.match(prompt, /calendar|summary/);
  assert.match(prompt, /must never appear in the candidates/);
});

test("requires exact pushed source, explicit branch and unique server identity", async (t) => {
  const { sources, rows } = await fixture(t);
  const options = { sources, rows, repositoryId: 1, branchId: 1 };
  assert.throws(
    () =>
      createSnapshot({
        ...options,
        rows: rows.map((row, i) =>
          i ? row : { ...row, source: "Old source" },
        ),
      }),
    /Push the exact/,
  );
  assert.throws(
    () => createSnapshot({ ...options, rows: [...rows, rows[0]] }),
    /Ambiguous/,
  );
  assert.throws(
    () => createSnapshot({ ...options, branchId: 2 }),
    /Push the exact/,
  );
  assert.throws(
    () => createSnapshot({ ...options, branchId: undefined }),
    /Explicit positive/,
  );
  assert.throws(
    () =>
      createSnapshot({
        ...options,
        rows: rows.map((row) => ({
          ...row,
          branchAssetContentMd5: "0".repeat(32),
        })),
      }),
    /Push the exact/,
  );
});

test("rejects incomplete, duplicate, stale and existing-target candidates", async (t) => {
  const { snapshot, candidates } = await fixture(t);
  assert.equal(validateCandidates(snapshot, candidates).length, 4);
  assert.throws(
    () =>
      validateCandidates(snapshot, {
        ...candidates,
        translations: candidates.translations.slice(1),
      }),
    /coverage/,
  );
  assert.throws(
    () =>
      validateCandidates(snapshot, {
        ...candidates,
        translations: [...candidates.translations, candidates.translations[0]],
      }),
    /duplicate/,
  );
  assert.throws(
    () =>
      validateCandidates(snapshot, { ...candidates, snapshotHash: "other" }),
    /snapshot/,
  );
  const human = snapshot.units.find((unit) => unit.target !== null);
  assert.throws(
    () =>
      validateCandidates(snapshot, {
        ...candidates,
        translations: [
          ...candidates.translations,
          { ...human, target: "Overwritten" },
        ],
      }),
    /Unexpected/,
  );
  assert.throws(
    () => validateCandidates({ ...snapshot, branchId: 2 }, candidates),
    /fingerprint/,
  );
});

test("retains links and code and rejects structural MDX injection", () => {
  const unit = {
    assetPath: "index.mdx",
    name: "intro",
    format: "MDX",
    source: "Use `notes.md` with the [guide](guide.html).",
  };
  validateTarget(unit, "Utilisez `notes.md` avec le [guide](guide.html).");
  assert.throws(
    () =>
      validateTarget(
        unit,
        "Utilisez `notes.md` avec le [guide](https://other.example).",
      ),
    /Protected/,
  );
  assert.throws(
    () =>
      validateTarget(unit, "Utilisez `other.md` avec le [guide](guide.html)."),
    /Protected/,
  );
  assert.throws(
    () => validateTarget(unit, "<script>bad</script>"),
    /structure/,
  );
  assert.throws(
    () => validateTarget(unit, "{dangerousExpression()}"),
    /structure/,
  );
  assert.throws(() => validateTarget(unit, "# A new heading"), /structure/);
});

test("MF2 preserves declarations, per-branch arguments, selectors, functions and formatting options", () => {
  const unit = { name: "summary", format: "MF2", source: summary };
  validateTarget(unit, translatedSummary);
  assert.throws(
    () =>
      validateTarget(unit, translatedSummary.replaceAll("$count", "$number")),
    /MF2 arguments/,
  );
  assert.throws(
    () =>
      validateTarget(
        unit,
        translatedSummary.replace(
          "one {{Vous avez {$count} séance.}}",
          "one {{Une séance.}}",
        ),
      ),
    /MF2 arguments/,
  );
  assert.throws(
    () => validateTarget(unit, translatedSummary.replace("one {{", "many {{")),
    /Duplicate MF2/,
  );
  assert.throws(
    () =>
      validateTarget(unit, translatedSummary.replace(":integer", ":number")),
    /MF2 arguments/,
  );
  assert.throws(
    () =>
      validateTarget(
        {
          name: "date",
          format: "MF2",
          source: "On {$date :date timeZone=UTC}.",
        },
        "Le {$date :date timeZone=Europe/Paris}.",
      ),
    /MF2 arguments|Invalid MF2/,
  );
  assert.throws(() => validateTarget(unit, "{unclosed"), /Invalid MF2/);
});

test("stages correct locale files without losing existing corrections, never overwrites outputs", async (t) => {
  const paths = await fixture(t);
  const outputDirectory = path.join(paths.directory, "staged");
  const staged = await stageCandidates({ ...paths, outputDirectory });
  assert.equal(staged.candidates.length, 4);
  assert.ok(
    staged.candidates.every(
      (candidate) =>
        candidate.status === "REVIEW_NEEDED" &&
        candidate.expectedVariantId === null,
    ),
  );
  assert.equal(
    await readFile(
      path.join(outputDirectory, "localized/modules/Shared_fr.mdx"),
      "utf8",
    ),
    "{/* mojito-id: title */}\nUne correction humaine.\n",
  );
  assert.match(
    await readFile(
      path.join(outputDirectory, "localized/index_fr.mdx"),
      "utf8",
    ),
    /# Un petit site/,
  );
  assert.equal(
    JSON.parse(
      await readFile(
        path.join(outputDirectory, "localized/messages.mf2_fr.json"),
        "utf8",
      ),
    ).summary,
    translatedSummary,
  );
  await assert.rejects(
    stageCandidates({ ...paths, outputDirectory }),
    /EEXIST/,
  );
});

test("source and human correction changes invalidate the snapshot before staging/import", async (t) => {
  const paths = await fixture(t);
  assertFreshTargets(paths.snapshot, paths.rows);
  assert.throws(
    () =>
      assertFreshTargets(
        paths.snapshot,
        paths.rows.map((row, i) =>
          i
            ? row
            : {
                ...row,
                target: "New human correction",
                tmTextUnitVariantId: 321,
              },
        ),
      ),
    /baseline changed/,
  );
  assert.throws(
    () =>
      assertFreshTargets(
        paths.snapshot,
        paths.rows.map((row, i) =>
          i ? row : { ...row, source: "New source" },
        ),
      ),
    /baseline changed/,
  );
  await writeFile(
    path.join(paths.sourceDirectory, "index.mdx"),
    "{/* mojito-id: changed */}\nNew source\n",
  );
  await assert.rejects(
    assertFreshSources(paths.snapshot, paths.sourceDirectory),
    /Local source changed/,
  );
});

test("limited extractor rejects unlabeled/duplicate blocks and malformed catalogs", () => {
  assert.throws(() => mdxSegments("Unlabeled paragraph"), /needs mojito-id/);
  assert.throws(
    () =>
      mdxSegments("{/* mojito-id: one */}\nOne\n\n{/* mojito-id: one */}\nTwo"),
    /Duplicate/,
  );
  assert.throws(() => catalogSegments('{"x":3}'), /nonempty string/);
  assert.throws(() => catalogSegments('{"x":"{bad"}'), /Invalid MF2/);
});

test("mock executable tests structured Codex boundary separately from any real model run", async (t) => {
  const { directory, snapshot, candidates } = await fixture(t);
  const executable = path.join(directory, "fake-codex.mjs");
  const previousPassword = process.env.MOJITO_PASSWORD;
  process.env.MOJITO_PASSWORD = "dummy-worker-isolation-regression";
  t.after(() => {
    if (previousPassword === undefined) delete process.env.MOJITO_PASSWORD;
    else process.env.MOJITO_PASSWORD = previousPassword;
  });
  await writeFile(
    executable,
    `#!/usr/bin/env node\nimport fs from 'node:fs';\nconst args = process.argv.slice(2);\nif (!args.includes('--ignore-user-config') || !args.includes('--ephemeral') || args[args.indexOf('--sandbox') + 1] !== 'read-only') process.exit(2);\nif (Object.keys(process.env).some((name) => name.startsWith('MOJITO_'))) process.exit(4);\nlet prompt='';for await (const chunk of process.stdin) prompt+=chunk;\nif (!prompt.includes('Une correction humaine')) process.exit(3);\nfs.writeFileSync(args[args.indexOf('--output-last-message')+1], ${JSON.stringify(JSON.stringify(candidates))});\n`,
  );
  await chmod(executable, 0o700);
  const outputFile = path.join(directory, "generated.json");
  assert.deepEqual(
    await runCodex({ snapshot, outputFile, executable }),
    candidates,
  );
  assert.deepEqual(JSON.parse(await readFile(outputFile, "utf8")), candidates);
  await assert.rejects(
    runCodex({ snapshot, outputFile, executable }),
    /EEXIST/,
  );
});

test("baseline is bounded, paginated and scoped to the explicit branch and locale", async () => {
  const calls = [];
  const first = {
    assetId: 7,
    assetPath: "index.mdx",
    branchAssetExtractionId: 201,
    branchAssetContentMd5: "a".repeat(32),
  };
  const client = async (url) => {
    calls.push(url);
    if (url.includes("/translation-candidates/source?"))
      return baselineResponse(url, [first], 3);
    return calls.length === 1
      ? Array.from({ length: 100 }, (_, i) => ({ ...first, tmTextUnitId: i }))
      : [{ ...first, tmTextUnitId: 101 }];
  };
  const rows = await fetchBaseline(client, { repositoryId: 3, branchId: 4 });
  assert.equal(rows.length, 101);
  assert.match(calls[0], /repositoryIds=3&branchId=4&localeTags=fr/);
  assert.match(calls[1], /offset=100/);
  assert.equal(calls.length, 3);
  assert.match(calls[2], /source\?branchId=4&assetId=7/);
  await assert.rejects(
    fetchBaseline(async () => Array(100).fill({}), {
      repositoryId: 1,
      branchId: 1,
    }),
    /limit/,
  );
});

test("branch metadata must match exact repository, branch, asset and local source revision", async (t) => {
  const paths = await fixture(t);
  const wrongBranch = async (url) => {
    const response = baselineResponse(url, paths.rows);
    return Array.isArray(response) ? response : { ...response, branchId: 2 };
  };
  await assert.rejects(
    fetchBaseline(wrongBranch, paths.snapshot),
    /branch source identity/,
  );
  assert.throws(
    () =>
      assertFreshTargets(
        paths.snapshot,
        paths.rows.map((row) => ({
          ...row,
          branchAssetContentMd5: "0".repeat(32),
        })),
      ),
    /baseline changed/,
  );
});

test("form login refreshes session/CSRF and never forwards credentials or follows redirects", async () => {
  const seen = [];
  const options = {
    username: "demo",
    password: "test",
    fetchImpl: async (url, init) => {
      seen.push({ url: String(url), init });
      if (url.pathname === "/api/frontend/config")
        return new Response(
          JSON.stringify({
            csrfToken: seen.length === 1 ? "initial" : "authenticated",
          }),
          {
            headers: {
              "Set-Cookie": `JSESSIONID=${seen.length === 1 ? "initial-session" : "authenticated-session"}; Path=/; HttpOnly`,
            },
          },
        );
      if (url.pathname === "/login")
        return new Response(null, { status: 302, headers: { Location: "/" } });
      return new Response("[]");
    },
  };
  assert.throws(
    () => mojitoClient({ ...options, url: "http://example.com" }),
    /HTTPS/,
  );
  assert.throws(
    () => mojitoClient({ ...options, url: "https://demo:secret@example.com" }),
    /without credentials/,
  );
  const client = mojitoClient({ ...options, url: "http://localhost:8080" });
  await client("/api/textunits?repositoryIds=1");
  await client("/api/repositories/1/content/translation-candidates", {
    target: "Test",
  });
  assert.equal(seen.length, 5);
  assert.ok(seen.every((call) => call.init.redirect === "manual"));
  assert.equal(seen[1].init.headers["X-CSRF-TOKEN"], "initial");
  assert.equal(seen[1].init.body, "username=demo&password=test");
  assert.equal(seen[3].init.headers.Cookie, "JSESSIONID=authenticated-session");
  assert.equal(seen[4].init.headers["X-CSRF-TOKEN"], "authenticated");
  assert.ok(
    seen.slice(2).every((call) => !call.init.body?.includes("password")),
  );
  await assert.rejects(
    client("https://evil.example/api/textunits"),
    /origin changed/,
  );
});

test("guarded import sends exact extraction/source identities and forces review through the endpoint", async (t) => {
  const paths = await fixture(t);
  const writes = [];
  const client = async (url, body) => {
    if (body === undefined) return baselineResponse(url, paths.rows);
    writes.push({ url, body });
    return {
      tmTextUnitId: body.tmTextUnitId,
      tmTextUnitVariantId: 500 + writes.length,
      target: body.target,
      status: "REVIEW_NEEDED",
    };
  };
  const reportFile = path.join(paths.directory, "import-report.json");
  const report = await importCandidates({ ...paths, client, reportFile });
  assert.equal(report.status, "complete");
  assert.equal(report.imported.length, 4);
  assert.ok(
    writes.every(
      ({ url, body }) =>
        url === "/api/repositories/1/content/translation-candidates" &&
        body.expectedVariantId === null &&
        body.expectedAssetExtractionId >= 200 &&
        /^[a-f0-9]{32}$/.test(body.expectedAssetContentMd5),
    ),
  );
  assert.ok(
    writes.every(
      ({ body }) =>
        body.tmTextUnitId !==
        paths.rows.find((row) => row.target !== null).tmTextUnitId,
    ),
  );
  assert.deepEqual(JSON.parse(await readFile(reportFile, "utf8")), report);
});

test("concurrent human correction returns conflict, stops remaining writes and records partial import", async (t) => {
  const paths = await fixture(t);
  let writes = 0;
  const client = async (url, body) => {
    if (body === undefined) return baselineResponse(url, paths.rows);
    writes++;
    if (writes === 2) {
      const error = new Error("HTTP 409");
      error.status = 409;
      throw error;
    }
    return {
      tmTextUnitId: body.tmTextUnitId,
      tmTextUnitVariantId: 900,
      target: body.target,
      status: "REVIEW_NEEDED",
    };
  };
  const reportFile = path.join(paths.directory, "import-report.json");
  await assert.rejects(
    importCandidates({ ...paths, client, reportFile }),
    /after 1 successful/,
  );
  const report = JSON.parse(await readFile(reportFile, "utf8"));
  assert.equal(report.status, "stopped");
  assert.equal(report.error.httpStatus, 409);
  assert.equal(report.imported.length, 1);
  assert.equal(writes, 2);
});

test("server extraction update invalidates candidate import before any writes", async (t) => {
  const paths = await fixture(t);
  let writes = 0;
  const client = async (url, body) => {
    if (body) {
      writes++;
      throw new Error("Unexpected write");
    }
    return baselineResponse(
      url,
      paths.rows.map((row) => ({
        ...row,
        branchAssetExtractionId: row.branchAssetExtractionId + 1,
      })),
    );
  };
  await assert.rejects(
    importCandidates({
      ...paths,
      client,
      reportFile: path.join(paths.directory, "report.json"),
    }),
    /baseline changed/,
  );
  assert.equal(writes, 0);
});

test("French plural candidates require many and preserve fallback arguments in added branches", () => {
  const unit = { name: "summary", format: "MF2", source: summary };
  assert.throws(
    () =>
      validateTarget(
        unit,
        translatedSummary.replace("many {{Vous avez {$count} séances.}}\n", ""),
      ),
    /Missing MF2 plural category.*many/,
  );
  assert.throws(
    () =>
      validateTarget(
        unit,
        translatedSummary.replace(
          "many {{Vous avez {$count} séances.}}",
          "many {{Beaucoup de séances.}}",
        ),
      ),
    /MF2 arguments/,
  );
  assert.throws(
    () =>
      validateTarget(unit, translatedSummary.replace("many {{", "unknown {{")),
    /unsupported added branch/,
  );
  validateTarget(unit, translatedSummary);
});

test("imported candidates normalize to the server's NFC representation before saving", async (t) => {
  const paths = await fixture(t);
  paths.candidates.translations[0].target = "Un cafe\u0301 tranquille";
  const normalized = validateCandidates(paths.snapshot, paths.candidates);
  assert.equal(normalized[0].target, "Un café tranquille");
  let saved;
  const client = async (url, body) => {
    if (!body) return baselineResponse(url, paths.rows);
    saved ??= body.target;
    return {
      tmTextUnitId: body.tmTextUnitId,
      tmTextUnitVariantId: 500,
      status: "REVIEW_NEEDED",
      target: body.target.normalize("NFC"),
    };
  };
  const report = await importCandidates({
    ...paths,
    client,
    reportFile: path.join(paths.directory, "normalized.json"),
  });
  assert.equal(saved, "Un café tranquille");
  assert.equal(report.status, "complete");
});
