import { mkdir, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { sharedModules, topics } from "./catalogue.mjs";

const projectDirectory = fileURLToPath(new URL("..", import.meta.url));
const segment = (id, text) => `{/* mojito-id: ${id} */}\n${text}\n`;
const relativeImport = (from, to) => {
  const relative = path.posix.relative(path.posix.dirname(from), to);
  return relative.startsWith(".") ? relative : `./${relative}`;
};
const importLine = (from, to, name) =>
  `import ${name} from '${relativeImport(from, to)}';\n`;
const translatedPath = (assetPath) => assetPath.replace(/\.mdx$/, "_fr.mdx");

function sharedModule(row, french) {
  const [name, title, body, frenchTitle, frenchBody, child] = row;
  const assetPath = `modules/shared/${name}.mdx`;
  const id = `shared.${name.replaceAll("/", ".").toLowerCase()}`;
  return (
    (child
      ? importLine(assetPath, `modules/shared/${child}.mdx`, "Note") + "\n"
      : "") +
    segment(`${id}.title`, `### ${french ? frenchTitle : title}`) +
    "\n" +
    segment(`${id}.body`, french ? frenchBody : body) +
    (child ? "\n<Note />\n" : "")
  );
}

function topicModule(topic, kind, french) {
  const assetPath = `modules/${topic.slug}/${kind}.mdx`;
  const copy = {
    Overview: [
      `A practical approach to ${topic.title.toLowerCase()}`,
      `Une approche concrète : ${topic.frenchTitle.toLowerCase()}`,
      topic.principle,
      topic.frenchPrinciple,
      "SmallExperiment",
    ],
    Checklist: [
      `Before you begin: ${topic.title.toLowerCase()}`,
      `Avant de commencer : ${topic.frenchTitle.toLowerCase()}`,
      "Choose a real example. Gather the material you need, and decide what a useful result would look like.",
      "Choisissez un exemple réel. Rassemblez le matériel nécessaire et définissez ce que serait un résultat utile.",
      "KeepRecord",
    ],
    Practice: [
      `Put ${topic.title.toLowerCase()} into practice`,
      `Passez à la pratique : ${topic.frenchTitle.toLowerCase()}`,
      `Try the idea with a colleague, then compare what each of you noticed. ${topic.principle}`,
      `Essayez cette idée avec un collègue, puis comparez vos observations. ${topic.frenchPrinciple}`,
      "ReviewTogether",
    ],
    Questions: [
      `Questions for your ${topic.title.toLowerCase()} review`,
      `Questions pour votre revue : ${topic.frenchTitle.toLowerCase()}`,
      `What became easier? What still needs a clearer example? ${topic.principle}`,
      `Qu’est-ce qui est devenu plus simple ? Qu’est-ce qui nécessite encore un exemple plus clair ? ${topic.frenchPrinciple}`,
      "NextStep",
    ],
  }[kind];
  const id = `${topic.slug}.${kind.toLowerCase()}`;
  const note = {
    Overview: "Context",
    Checklist: "Privacy",
    Practice: "Accessibility",
    Questions: "Ownership",
  }[kind];
  return (
    importLine(
      assetPath,
      `modules/shared/patterns/${copy[4]}.mdx`,
      "SharedPractice",
    ) +
    importLine(assetPath, `modules/shared/notes/${note}.mdx`, "SharedNote") +
    "\n" +
    segment(`${id}.title`, `## ${copy[french ? 1 : 0]}`) +
    "\n" +
    segment(`${id}.body`, copy[french ? 3 : 2]) +
    "\n<SharedPractice />\n\n<SharedNote />\n"
  );
}

function pagePath(topic, index) {
  const collection =
    index < 3 ? "explore" : index < 7 ? "guides" : "field-notes";
  return `pages/${topic.slug}/${collection}/${topic.pages[index][0]}.mdx`;
}

function pageDocument(topic, index, french) {
  const [slug, title, body] = topic.pages[index];
  const assetPath = pagePath(topic, index);
  const id = `${topic.slug}.${slug}`;
  const supportingKind = ["Checklist", "Practice", "Questions"][index % 3];
  const alternatives =
    index === 0
      ? ["Individual", "Team"]
      : index === 5
        ? ["Facilitator", "Visitor"]
        : [];
  const imports = [
    importLine(assetPath, `modules/${topic.slug}/Overview.mdx`, "Overview"),
    importLine(
      assetPath,
      `modules/${topic.slug}/${supportingKind}.mdx`,
      "Practice",
    ),
    importLine(assetPath, "modules/shared/patterns/NextStep.mdx", "NextStep"),
    ...alternatives.map((name) =>
      importLine(assetPath, `modules/shared/audience/${name}.mdx`, name),
    ),
  ].join("");
  const relatedPath = relativeImport(
    assetPath,
    pagePath(topic, (index + 1) % topic.pages.length),
  );
  return (
    imports +
    "\n" +
    segment(`${id}.title`, `# ${french ? topic.firstFrench[1] : title}`) +
    "\n" +
    segment(`${id}.intro`, french ? topic.firstFrench[2] : body) +
    "\n<Overview />\n\n" +
    segment(
      `${id}.practice.title`,
      french
        ? "## Essayez avec un exemple réel"
        : "## Try it with a real example",
    ) +
    "\n" +
    segment(
      `${id}.practice.body`,
      french ? topic.frenchPrinciple : topic.principle,
    ) +
    "\n<Practice />\n\n" +
    (alternatives.length
      ? `<PreviewChoice>\n${alternatives.map((name) => `<${name} />`).join("\n")}\n</PreviewChoice>\n\n`
      : "") +
    segment(
      `${id}.related`,
      french
        ? `Poursuivez avec le [prochain guide](${relatedPath}) et adaptez l’exercice à votre situation.`
        : `Continue with the [next guide](${relatedPath}) and adapt the exercise to your own situation.`,
    ) +
    "\n<NextStep />\n" +
    (index === 0 ? "\n<NextStep />\n" : "")
  );
}

export function createCatalogue() {
  const entries = sharedModules.map((row) => ({
    path: `modules/shared/${row[0]}.mdx`,
    role: "module",
    source: sharedModule(row, false),
    french: sharedModule(row, true),
  }));
  for (const topic of topics) {
    for (const kind of ["Overview", "Checklist", "Practice", "Questions"]) {
      entries.push({
        path: `modules/${topic.slug}/${kind}.mdx`,
        role: "module",
        source: topicModule(topic, kind, false),
        french: topicModule(topic, kind, true),
      });
    }
    topic.pages.forEach((_, index) =>
      entries.push({
        path: pagePath(topic, index),
        role: "page",
        source: pageDocument(topic, index, false),
        ...(index === 0 ? { french: pageDocument(topic, index, true) } : {}),
      }),
    );
  }
  return entries.sort((a, b) => a.path.localeCompare(b.path, "en"));
}

export function inspectCatalogue(entries) {
  const byPath = new Map(entries.map((entry) => [entry.path, entry]));
  if (byPath.size !== entries.length) throw new Error("Duplicate asset paths");
  const imports = new Map();
  const incoming = new Map();
  const directories = new Set();
  let sourceStrings = 0;
  let frenchStrings = 0;
  for (const entry of entries) {
    if (entry.path.startsWith("/") || entry.path.split("/").includes(".."))
      throw new Error("Unsafe asset path");
    const parts = entry.path.split("/");
    for (let index = 1; index < parts.length; index++)
      directories.add(parts.slice(0, index).join("/") + "/");
    const ids = [
      ...entry.source.matchAll(/\{\/\* mojito-id: ([^ ]+) \*\/\}/g),
    ].map((match) => match[1]);
    if (!ids.length || new Set(ids).size !== ids.length)
      throw new Error(`Missing or duplicate IDs: ${entry.path}`);
    sourceStrings += ids.length;
    if (entry.french) {
      const frenchIds = [
        ...entry.french.matchAll(/\{\/\* mojito-id: ([^ ]+) \*\/\}/g),
      ].map((match) => match[1]);
      if (JSON.stringify(ids) !== JSON.stringify(frenchIds))
        throw new Error(`Translation IDs differ: ${entry.path}`);
      frenchStrings += frenchIds.length;
    }
    const dependencies = [
      ...entry.source.matchAll(/^import (\w+) from '([^']+)';$/gm),
    ].map((match) => {
      const target = path.posix.normalize(
        path.posix.join(path.posix.dirname(entry.path), match[2]),
      );
      if (!byPath.has(target))
        throw new Error(`Unresolved import: ${entry.path} -> ${target}`);
      if (entry.french && !byPath.get(target).french)
        throw new Error(`Missing French module: ${target}`);
      if (!entry.source.includes(`<${match[1]} />`))
        throw new Error(`Unused import: ${entry.path}`);
      incoming.set(target, (incoming.get(target) ?? 0) + 1);
      return target;
    });
    imports.set(entry.path, dependencies);
  }
  function depth(assetPath, visiting = new Set()) {
    if (visiting.has(assetPath))
      throw new Error(`Recursive include: ${assetPath}`);
    const next = new Set(visiting).add(assetPath);
    return Math.max(
      0,
      ...imports.get(assetPath).map((child) => 1 + depth(child, next)),
    );
  }
  const maximumIncludeDepth = Math.max(
    ...entries.map((entry) => depth(entry.path)),
  );
  if (maximumIncludeDepth > 3)
    throw new Error("Include depth exceeds the preview limit");
  for (const entry of entries.filter((entry) => entry.role === "module")) {
    if ((incoming.get(entry.path) ?? 0) < 2)
      throw new Error(`Module is not reused: ${entry.path}`);
  }
  return {
    assets: entries.length,
    pages: entries.filter((entry) => entry.role === "page").length,
    modules: entries.filter((entry) => entry.role === "module").length,
    directories: directories.size,
    sourceStrings,
    frenchAssets: entries.filter((entry) => entry.french).length,
    frenchStrings,
    scenarioPages: entries.filter((entry) =>
      entry.source.includes("<PreviewChoice>"),
    ).length,
    maximumIncludeDepth,
  };
}

export async function generate(
  outputDirectory = path.join(projectDirectory, ".generated"),
) {
  const entries = createCatalogue();
  const summary = inspectCatalogue(entries);
  await rm(outputDirectory, { recursive: true, force: true });
  for (const entry of entries) {
    for (const [directory, assetPath, content] of [
      ["content", entry.path, entry.source],
      ["seed-content", entry.path, entry.french ? entry.source : null],
      ["translations", translatedPath(entry.path), entry.french],
    ]) {
      if (!content) continue;
      const destination = path.join(outputDirectory, directory, assetPath);
      await mkdir(path.dirname(destination), { recursive: true });
      await writeFile(destination, content);
    }
  }
  await writeFile(
    path.join(outputDirectory, "manifest.json"),
    JSON.stringify(
      {
        ...summary,
        roleConvention:
          "The integration treats pages/ as pages and modules/ as reusable modules. Mojito does not infer or store these roles.",
        files: entries.map((entry) => ({
          path: entry.path,
          role: entry.role,
          frenchFixture: Boolean(entry.french),
        })),
      },
      null,
      2,
    ) + "\n",
  );
  return summary;
}

if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  console.log(JSON.stringify(await generate(), null, 2));
}
