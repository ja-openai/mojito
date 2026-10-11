import { MDXProvider } from "@mdx-js/react";
import { MF2Provider } from "@mojito-mf2/react";
import manifest from "../.generated/manifest.json";
import { createPreviewChoice } from "./PreviewChoice.mjs";
import { PreviewMessage } from "./PreviewMessage.mjs";
import { CatalogLoadingExample } from "./CatalogLoadingExample.mjs";
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

// Vite emits separate hashed chunks. Imports fetch resources; the component
// decides when to swap React state. No Mojito connection is used at runtime.
const catalogImports = import.meta.glob("../.generated/catalogs/*.json");
async function loadCatalog(locale) {
  const filename = manifest.catalogFiles[locale];
  const load = catalogImports[`../.generated/catalogs/${filename}`];
  if (!load) throw new Error(`Unsupported message locale: ${locale}`);
  return (await load()).default;
}

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
            <MF2Provider
              resources={resources}
              locale={locale}
              sourceLocale="en"
              timeZone="UTC"
            >
              <MDXProvider
                components={{
                  PreviewChoice: choices[locale],
                  PreviewMessage,
                }}
              >
                <Page />
              </MDXProvider>
            </MF2Provider>
            <CatalogLoadingExample
              initialSelection={{
                locale,
                resources,
                version: manifest.catalogVersion,
              }}
              loadCatalog={loadCatalog}
            />
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
