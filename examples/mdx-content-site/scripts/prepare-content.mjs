import path from "node:path";
import { fileURLToPath } from "node:url";
import { prepareContent } from "./content.mjs";

const projectDirectory = fileURLToPath(new URL("..", import.meta.url));
const args = process.argv.slice(2);
let sample = false;
let translationDirectory = path.join(projectDirectory, "localized");
let explicitTranslations = false;
for (let index = 0; index < args.length; index++) {
  if (args[index] === "--sample") {
    sample = true;
  } else if (
    args[index] === "--translations" &&
    args[index + 1] &&
    !args[index + 1].startsWith("--")
  ) {
    translationDirectory = path.resolve(args[++index]);
    explicitTranslations = true;
  } else {
    throw new Error(
      "Usage: node scripts/prepare-content.mjs [--sample | --translations DIRECTORY]",
    );
  }
}
if (sample && explicitTranslations)
  throw new Error("--sample and --translations cannot be combined.");
if (sample) translationDirectory = path.join(projectDirectory, "translations");
try {
  const manifest = await prepareContent({
    projectDirectory,
    translationDirectory,
    sample,
  });
  console.log(
    `Prepared ${manifest.assets.length} assets for Vite (${sample ? "sample fixtures" : "pulled translations"}).`,
  );
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
