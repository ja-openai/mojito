import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

function escapeHtml(text) {
  return text
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

export async function prerenderSite(projectDirectory) {
  const outputDirectory = path.join(projectDirectory, "dist");
  const manifest = JSON.parse(
    await readFile(
      path.join(projectDirectory, ".generated/manifest.json"),
      "utf8",
    ),
  );
  const { render } = await import(
    `${pathToFileURL(path.join(projectDirectory, ".generated/ssr/entry-server.mjs"))}?build=${Date.now()}`
  );
  for (const locale of manifest.locales) {
    for (const page of manifest.pages) {
      const filename = path.join(outputDirectory, locale, `${page}.html`);
      const template = await readFile(filename, "utf8");
      const marker = '<div id="root"></div>';
      if (!template.includes(marker))
        throw new Error(`Missing prerender marker in ${filename}`);
      const title = `${manifest.titles[locale][`${page}.mdx`]} · Commonplace`;
      await writeFile(
        filename,
        template
          .replace(
            "<title>Commonplace</title>",
            `<title>${escapeHtml(title)}</title>`,
          )
          .replace(
            marker,
            `<div id="root" data-prerendered="true">${render({ locale, page })}</div>`,
          ),
      );
    }
  }
  await writeFile(
    path.join(outputDirectory, "build-info.json"),
    `${JSON.stringify(
      {
        mode: manifest.mode,
        targetLocale: manifest.targetLocale,
        pages: manifest.pages,
        assets: manifest.assets,
      },
      null,
      2,
    )}\n`,
  );
  return outputDirectory;
}

if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const projectDirectory = fileURLToPath(new URL("..", import.meta.url));
  await prerenderSite(projectDirectory);
  console.log("Prerendered English and French pages in dist/.");
}
