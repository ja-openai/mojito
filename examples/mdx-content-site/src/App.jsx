import { MDXProvider } from "@mdx-js/react";
import manifest from "../.generated/manifest.json";
import { createPreviewChoice } from "./PreviewChoice.mjs";
import { createPreviewMessage } from "./PreviewMessage.mjs";
import { createMessageFormatter } from "./mf2-runtime.mjs";
import resources from "../.generated/resources.json";

const modules = import.meta.glob("../.generated/**/*.mdx", {
  eager: true,
  import: "default",
});
const moduleFor = (locale, file) => modules[`../.generated/${locale}/${file}`];
const choices = Object.fromEntries(
  manifest.locales.map((locale) => [
    locale,
    createPreviewChoice(
      new Map(
        manifest.assets.map((file) => [
          moduleFor(locale, file),
          manifest.titles[locale][file],
        ]),
      ),
    ),
  ]),
);

const messages = Object.fromEntries(
  manifest.locales.map((locale) => [
    locale,
    createPreviewMessage(createMessageFormatter(resources, locale)),
  ]),
);

export function App({ locale, page }) {
  if (!manifest.locales.includes(locale) || !manifest.pages.includes(page)) {
    throw new Error(`Unknown example page: ${locale}/${page}`);
  }
  const Page = moduleFor(locale, `${page}.mdx`);
  return (
    <>
      {manifest.mode === "sample" ? (
        <div className="sample-banner">
          Sample preview · checked-in translations · no Mojito server used
        </div>
      ) : null}
      <div className="site-shell">
        <header>
          <a className="brand" href="index.html">
            <span className="brand-mark" aria-hidden="true">
              c.
            </span>
            Commonplace
          </a>
          <nav className="languages" aria-label="Language">
            {manifest.locales.map((target) => (
              <a
                key={target}
                href={`../${target}/${page}.html`}
                lang={target}
                hrefLang={target}
                aria-current={target === locale ? "true" : undefined}
              >
                {target === "en" ? "English" : "Français"}
              </a>
            ))}
          </nav>
        </header>
        <nav className="pages" aria-label="Pages">
          {manifest.pages.map((target) => (
            <a
              key={target}
              href={`${target}.html`}
              aria-current={target === page ? "page" : undefined}
            >
              {manifest.titles[locale][`${target}.mdx`]}
            </a>
          ))}
        </nav>
        <main>
          <article>
            <MDXProvider
              components={{
                PreviewChoice: choices[locale],
                PreviewMessage: messages[locale],
              }}
            >
              <Page />
            </MDXProvider>
          </article>
        </main>
        <footer>
          <a className="brand" href="index.html">
            Commonplace<span aria-hidden="true">↗</span>
          </a>
          <span>MDX / Mojito</span>
        </footer>
      </div>
    </>
  );
}

export function pageTitle(locale, page) {
  return `${manifest.titles[locale][`${page}.mdx`]} · Commonplace`;
}
