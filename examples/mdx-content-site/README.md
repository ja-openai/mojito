# Vite MDX content site: English → Mojito → French

A small, neutral website called **Commonplace**, with two pages and six MDX
modules plus an MF2 catalog: **nine assets and 25 strings**. The same files are fixtures for the
Java CLI integration test. Every paragraph, heading, list item, and quote has an
explicit `mojito-id`. The demo uses language-only locales, `en` and `fr`.

This is a Vite + React project using the official `@mdx-js/rollup` integration.
Mojito supplies translated MDX files and an MF2 JSON catalog; the site's layout, routes, components, and
build remain ordinary website code. The built website makes no Mojito API calls.

Author each asset's source MDX once in `content/`. Mojito keeps its translations
and reconstructs localized MDX on pull, preserving the authored module structure.
The checked-in `translations/` files are French demonstration fixtures for seeding
and tests; generated localized files are build inputs, not separately maintained
page templates.

For directory browsing and page/module management with a larger library, use the
[content catalogue demo](../content-catalogue-demo/README.md): 120 pages and 60
reused modules. This small website remains the end-to-end publishing example.

## Run the complete local round trip

From the Mojito repository root, with Java 21 and Maven available:

```sh
source webapp/use_local_npm.sh
npm --prefix examples/mdx-content-site ci
npm --prefix examples/mdx-content-site run test:e2e
npm --prefix examples/mdx-content-site run preview
```

Open <http://127.0.0.1:5190/> and switch between English and French. Navigation
stays in the selected language. The build renders real MDX with React, using the
[official Vite integration](https://mdxjs.com/docs/getting-started/#vite).

The mini website is read-only. Its **Version** dropdown displays the Individual
or Team module, with translated labels on the French page. Edit translations from
Mojito's **Content** page or a Review Project preview, then pull and build again to
see the saved changes on the website.

`test:e2e` runs `MdxWebsiteExampleTest` against a random-port Spring server with
an in-memory HSQL database and an in-memory replacement for the external Azure
blob provider. It runs the real Java CLI commands through HTTP: create repository,
push sources, import the French fixtures, and pull localized documents. The site
is then built from those **pulled documents**, under
`cli/target/mdx-content-site/localized/`.

The isolated test routes all structured blobs, including translation caches, to
the in-memory external provider. It checks zero database blob rows, nested document
preview, shared-string identity, both choice alternatives, MF2 integrity, and a
saved catalog correction surviving another pull. It does not connect
to your running Mojito instance or exercise Azure credentials/networking.
`no-local-config` excludes developer-specific Maven test configuration; the
round-trip test also disables Mojito's unrelated frontend build. No Docker or
external database is required.

## Try the integrated Mojito UI

From the repository root, this opt-in command builds Mojito's frontend, seeds the
isolated example, and keeps the normal application running for browser editing:

```sh
mvn -Pno-local-config,frontend -pl cli -am test \
  -Dtest=MdxWebsiteExampleTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmojito.mdxExample.demo=true
```

After setup, read `cli/target/mdx-content-site/demo.json` in another terminal. It
contains the current `port` and `repositoryId`; open
`http://127.0.0.1:<port>/content?repoId=<repositoryId>&locale=fr` and sign in with
`admin` / `ChangeMe`. The Content tab is hidden by default; enable **My Settings →
Admin features → Show Content tab** and save to show it for your admin account.
The direct Content link works without opting in. Open a document, choose **Edit
translations**, and click a passage to edit beside it without moving the page.
Double-clicking a passage also opens editing from reading mode. **More details**
opens the full editor, and **Save & next** moves to the next visible string after
saving. Cmd/Ctrl+Enter saves; Escape closes while retaining the draft. Saving
refreshes the preview; repeated module occurrences share the same translation.
Content browsing needs no
Review Project.

The Content catalogue uses cursor navigation with at most 100 assets per page.
Search by full path prefix, exact path, or contains; folders load one level and
50 names at a time. Selecting a folder includes its descendant assets. Opening a
document enters its preview workspace; **Back to content** restores the directory,
search, and result position. Old offset/search links still work. These bounds
keep browser work small; production query plans and latency at 100,000 assets
still need measurement, especially for contains search and directory grouping.

In a Review Project the page navigator starts collapsed on laptops. The editor
uses one column when its pane is narrow. At widths up to 1100px it becomes a drawer; **Back to
preview** closes it without losing the draft. Phone-sized screens use a full-width
editor. Resizing keeps the same editor mounted.

The UI uses real Mojito APIs with an ephemeral HSQL database and an in-memory test
replacement for external blob storage. It does not use your development database
or a production blob provider. AI review is not configured in this demo. Stop it
with Ctrl-C; the application data disappears when the process stops. The ordinary
`test:e2e` command completes without holding a server open.

For a quick visual sample without running Mojito:

```sh
npm --prefix examples/mdx-content-site run build:sample
npm --prefix examples/mdx-content-site run preview
```

This explicitly uses the checked-in French fixtures and labels the result as a
sample. Ordinary `npm run build` requires pulled files in `localized/`; it does
not fall back to the sample translations.

## Work with Vite

After the round trip above has prepared `.generated/`, start Vite's development
server from this example directory:

```sh
npm run dev
```

For development using the checked-in French sample directly:

```sh
npm run dev:sample
```

Both use port 5190, so stop `preview` before starting `dev`. The normal project
entry points are `vite.config.mjs`, `src/App.jsx`, and `src/main.jsx`. The four
locale HTML entries keep direct URLs such as `/en/index.html` and
`/fr/guide.html`. Vite handles React and MDX compilation and generates hashed
JavaScript and CSS assets.

The small `scripts/prepare-content.mjs` adapter prepares the localized file tree
and its title manifest before Vite runs. Re-run `content:prepare` after editing
source MDX or pulling translations, or `content:sample` while using sample fixtures. This
adapter is specific to the example's filenames and locales; another website can
replace it without changing Mojito's push/pull protocol.

`build:site` runs a normal Vite client build, a Vite SSR build of the same React
tree, and `scripts/prerender.mjs` to fill the four HTML pages. The SSR bundle is
used only during the build. In the browser, React hydrates the pages and manages
the version dropdown and the MF2 count/date controls. `npm run preview` uses Vite's static preview server;
`npm run serve` remains an alias.

## Nested and reused modules

```text
content/
├── index.mdx
│   ├── PreviewChoice
│   │   ├── modules/audience/Individual.mdx
│   │   └── modules/audience/Team.mdx
│   ├── modules/SharedNote.mdx         (also repeated later on this page)
│   └── modules/Welcome.mdx            depth 1
│       └── routine/Steps.mdx          depth 2
│           └── Tip.mdx                depth 3
└── guide.mdx
    ├── modules/routine/Steps.mdx      same asset and translations
    │   └── Tip.mdx
    └── modules/SharedNote.mdx         same asset and translation
```

Each module is its own Mojito asset. For example, `Welcome.mdx` includes:

```mdx
import Steps from './routine/Steps.mdx';

{/* mojito-id: welcome.title */}
## Small ideas, put into practice

<Steps />
```

The example uses the supported static subset: default relative `.mdx` imports,
standalone component tags, prose, Markdown links, inline code, lists, and quotes.
There are no arbitrary props, JavaScript expressions, or recursive includes. The
reserved `PreviewMessage` adapter below accepts literal reference and sample inputs. The deepest
include matches Mojito review's three-level limit. Editing the shared note in
Mojito updates all its occurrences on the next pull/build.

## Preview alternative modules

`index.mdx` offers two versions of an introductory module:

```mdx
import Individual from './modules/audience/Individual.mdx';
import Team from './modules/audience/Team.mdx';

<PreviewChoice>
<Individual />
<Team />
</PreviewChoice>
```

`PreviewChoice` is a reserved built-in wrapper in Mojito review. Use paired,
standalone tags containing two to eight self-closing, default-imported relative
MDX modules. The wrapper is not imported, and accepts no attributes, prose,
expressions, or nested choice wrappers directly inside it. The ordinary include
depth and cycle limits still apply to its module alternatives.

All alternatives are extracted, pushed, reviewed, and pulled. Selecting a version
only changes which module is visible; it does not save a translation or mark a
string reviewed. Mojito's dropdown shows review progress for each alternative.
The example website supplies its own React component, using each module's
localized heading as its dropdown label. Both panels are prerendered, with
Individual visible initially; React switches their visibility in the browser.

## Push and pull against a development Mojito server

Use a **Java CLI and server built from this checkout** with MDX support. The
independent Rust CLI does not support this example yet. Configure the server's
`asset-content` route to your existing external blob provider, for example:

```properties
l10n.blob-storage.routing.prefixes.asset-content=azure
l10n.blob-storage.routing.prefixes.pollable-task=azure
```

The usual Azure/S3 provider configuration is also required. `pollable-task` keeps
the asynchronous import's full-document job payload in external storage too.
An external default route can cover both prefixes instead. MDX payloads must
not use the database blob route. Source-template keys follow the existing
asset/branch/content-hash convention; no blob-reference column is needed.

To keep every structured blob outside the database, including existing DTO caches,
configure `l10n.blob-storage.default-type=AZURE` (with the provider configured).
The isolated demo uses that default with an in-memory provider replacement.

Set `MOJITO_BIN` to the Java CLI wrapper for that development server; its existing
configuration supplies the host and authentication. The example does not contain
credentials or change that configuration. If using a freshly built CLI JAR,
the [CLI configuration guide](../../docs/_docs/refs/configurations.md) describes
the `l10n.resttemplate` settings.

From this example directory:

```sh
export MOJITO_BIN=/absolute/path/to/your/development/mojito
export MOJITO_REPO=mdx-content-site

bash scripts/mojito.sh create   # once: English source, fr target
bash scripts/mojito.sh push     # eight pages/modules and one catalog, default branch
```

Use a dedicated example repository: an ordinary push removes assets omitted from
the selected source tree. `create` fails if the repository name already exists.

Translate/review the strings in Mojito, or seed the demonstration translations:

```sh
bash scripts/mojito.sh seed     # writes the checked-in French targets
bash scripts/mojito.sh pull
npm run build
npm run preview
```

The seed step is optional. Administrators can open **Content** in Mojito, select
the repository, branch, and French, then search asset paths and open a document.
The asset list loads 100 at a time, shows the current page count, and offers
**Previous** and **Next**. Returning to the browser tab picks up external changes.
Selecting a translated passage opens the existing editor beside the preview;
saving refreshes saved text without leaving the page. Unsaved drafts do not replace
preview text. No repository tag or model change is required.

To showcase review decisions, create a normal French Review Project including
**all nine assets**, open Preview, and select a passage to edit it.
The **Pages and modules** navigator lists the documents available in that
project and opens one at a time. Search titles, asset paths, source text, or saved
translations, including text from nested modules. Shared modules have **Used in**
links back to their parent pages. Preview and search use saved translations;
unsaved editor drafts do not replace the rendered text.

Including module assets gives their passages editable review rows. A shared
passage has one translation/decision even when shown several times. Importing
sample translations does not manufacture review decisions. After saving a change,
run `pull` and `build` again.

The helper runs ordinary commands, without a custom upload protocol:

```sh
mojito repo-create -n mdx-content-site -sl en -l fr -it json:MF2
mojito push -r mdx-content-site -s content -ft MDX JSON
mojito import -r mdx-content-site -s content -t translations -ft MDX JSON \
  -lm fr:fr -lmt MAP_ONLY
mojito pull -r mdx-content-site -s content -t localized -ft MDX JSON \
  -lm fr:fr -lmt MAP_ONLY
```

## How localized imports keep working

Mojito pull emits `index_fr.mdx`, `modules/Welcome_fr.mdx`, etc. The MDX
still imports `./modules/Welcome.mdx`. Before compilation, the example copies
the complete French output into `.generated/fr/` with original filenames.
English is assembled separately in `.generated/en/`. Each locale now has a
self-contained module tree, so imports resolve without changing authored MDX.
Missing French files fail the build instead of loading an English module.

You can build a different pull output with:

```sh
npm run content:prepare -- --translations /absolute/path/to/pulled-files
npm run build:site
```

`dist/` contains prerendered HTML and Vite's hashed CSS and JavaScript assets.
Build outputs, pulled translations, and dependencies are ignored by Git. Page
titles also provide translated navigation labels; the site name and language
names are fixed display labels.

## Serve the build from a CDN

The publishable artifact is **only `dist/`**. Copy its contents to a static host or
CDN origin, preserving the `en/`, `fr/`, and `assets/` directories. The example
is documented at the origin root: direct requests to `/en/index.html` and
`/fr/guide.html` serve real HTML files, with no application-server rewrite or
production SSR server needed. Vite emits relative asset URLs.

Use a short cache lifetime or revalidation for HTML, and long immutable caching
for hashed files under `assets/`. Keep older hashed assets available through the
cache transition when publishing a new build. The content flow is
push → translate in Mojito → pull → Vite build → publish `dist/`. This example
includes no CDN credentials or publishing step.

## Checks and boundaries

```sh
npm test                   # assembly, rendering, links, and failure cases
npm run test:e2e           # Java HTTP round trip, then build the pulled result
```

The website build compiles trusted project MDX as a normal site build. Mojito's
review renderer continues to use inert, bounded document rendering. This example
does not add arbitrary React execution to Mojito or cover deployment or a
production storage provider.

## MF2 resources and interactive messages

`content/messages.mf2.json` owns two messages: a session count with plural selection,
and a date. The French fixture is `translations/messages.mf2_fr.json`; pull emits
the same filename under `localized/`. Both formats must be pushed together so a
push does not retire the other format's assets. The dedicated demo repository
configures `json:MF2` integrity checking at creation.

An MDX page references a message with literal sample inputs:

```mdx
<PreviewMessage resource="./messages.mf2.json" name="calendar.summary" args='{"count":2}' />
<PreviewMessage resource="./messages.mf2.json" name="calendar.date" args='{"date":"2026-09-16T12:00:00Z"}' />
```

The resource path is relative to its owning MDX asset. Mojito resolves the catalog
in the same repository and branch, formats one sample with the locale's Intl
functions, and maps clicks to the catalog's existing translation/review row.
Errors remain visible and the mapped translation can still be opened for repair.
For the structured MF2 editor, enable **Settings → Translation editor → Use the
assisted rich text editor**; the ordinary text editor remains available when that
preference is off.
This is a bounded message adapter; Mojito does not execute the website component
or claim all runtime values have been reviewed.

The website's own React adapter adds count/date controls. `content:prepare` parses
catalogs into `.generated/resources.json` at build time using `mf2/javascript`.
Vite bundles those models with the site; browser and prerendering use the same
formatter plus Intl bootstrap. Locale comes from the page's HTML language and
dates use an explicit UTC zone, so hydration starts with the same output.
The build rejects parser imports into either runtime bundle and validates missing
messages, changed MF2 arguments/options, references, and sample inputs. Resources
are included in the static assets: there is no runtime request to Mojito.

## Optional Codex translation stage

Use an unseeded demo repository after `create` and `push`. The worker reads all
source MDX, catalog messages, component context, and current Mojito translations.
Snapshot and import require an administrator or project manager account; target
locale edit permissions still apply.
Existing targets are context only; it generates candidates solely for missing
translations. Supply the local server URL and login through `MOJITO_URL`,
`MOJITO_USER`, and `MOJITO_PASSWORD`, then run from this directory:

```sh
npm run translate -- snapshot --repo-id 1 --branch-id 1 --out /tmp/site-translation
npm run translate -- generate --snapshot /tmp/site-translation/snapshot.json --out /tmp/site-candidates.json
npm run translate -- validate --snapshot /tmp/site-translation/snapshot.json --candidates /tmp/site-candidates.json
npm run translate -- import --snapshot /tmp/site-translation/snapshot.json --candidates /tmp/site-candidates.json --report /tmp/site-import.json
```

Use the IDs from your local repository, and new output paths for each run.
`CODEX_BIN` or `generate --codex /absolute/path/to/codex` selects the installed CLI;
generation uses its existing authentication in a read-only task. The child
process does not receive `MOJITO_*` environment variables, including server
credentials. `snapshot
--guidance FILE` adds editorial guidance. The generated JSON format and separate
`validate`/`import` steps also allow another engine to supply the candidates.

The snapshot reads the successful branch extraction and owning asset's content
hash through the candidate source endpoint; ordinary text-unit search returns a
merged extraction and is not the source of this revision identity. Import
rechecks those fingerprints and calls the atomic fill-missing endpoint one string
at a time. The server validates integrity, requires the
expected absent translation, and saves `REVIEW_NEEDED`. Any existing target,
including a blank target or previous translation history, blocks the write. A
conflict stops the batch; the report records any earlier successful rows.
Human corrections are preserved. Review/edit in Mojito, then `pull` and `build`.

`stage` can instead write a new local directory of candidate files for inspection;
it does not write translations to Mojito. The ordinary `seed` command imports
deterministic French fixtures and is separate from real model generation.
