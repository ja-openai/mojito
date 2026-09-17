import { mkdir, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { manifest, renderEmail } from "../.generated/renderer/render.mjs";

const root = fileURLToPath(new URL("..", import.meta.url));
const output = path.join(root, "dist");
const escape = (text) =>
  text
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
await rm(output, { recursive: true, force: true });
await mkdir(output, { recursive: true });
const links = [];
for (const locale of manifest.locales)
  for (const email of manifest.emails) {
    const result = renderEmail(locale, email);
    const directory = path.join(output, locale, email);
    await mkdir(directory, { recursive: true });
    for (const [file, value] of Object.entries({
      "subject.txt": result.subject,
      "preheader.txt": result.preheader,
      "body.html": result.html,
      "body.txt": result.text,
    }))
      await writeFile(path.join(directory, file), value + "\n");
    links.push(
      `<a href="${locale}/${email}/preview.html">${escape(email)} · ${locale}</a>`,
    );
    await writeFile(
      path.join(directory, "preview.html"),
      `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>${escape(result.subject)}</title><body style="margin:24px auto;max-width:760px;padding:16px;font:16px/1.5 system-ui;background:#f2f5f4"><a href="../../index.html">← All emails</a><p>${manifest.mode === "sample" ? "Sample translations" : "Pulled translations"} · ${locale}</p><h1 style="font-size:24px">${escape(result.subject)}</h1><p>${escape(result.preheader)}</p><p><a download href="subject.txt">Subject</a> · <a download href="preheader.txt">Preheader</a> · <a download href="body.html">HTML</a> · <a download href="body.txt">Plain text</a></p><iframe title="Email body" src="body.html" sandbox="" style="width:100%;height:700px;border:0"></iframe></body></html>`,
    );
  }
await writeFile(
  path.join(output, "index.html"),
  `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Commonplace email templates</title><body style="max-width:760px;margin:64px auto;padding:24px;font:17px/1.6 system-ui;color:#243530;background:#f2f5f4"><p>MOJITO / EMAIL DEMO</p><h1>Two emails. Six languages.</h1><p>Subject, preheader, and body are separate assets. The header and footer are shared modules.</p><p>${manifest.mode === "sample" ? "Sample preview — checked-in translations, no Mojito server used." : "Built from pulled Mojito translations. Translation status is not proof of a review decision."}</p><nav style="display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px">${links.join("\n")}</nav><p>The exported email is standalone HTML, with no JavaScript. Nothing is sent.</p></body></html>`,
);
console.log(
  `Exported ${manifest.emails.length * manifest.locales.length} emails to dist/.`,
);
