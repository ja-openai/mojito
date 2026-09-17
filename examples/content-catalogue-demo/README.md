# Content catalogue demo

A larger, neutral library for exploring Mojito's repository **Content** interface:
**180 MDX assets: 120 pages and 60 reused modules**, spread across **66 directory
prefixes**. Twelve topics cover planning, writing, learning, research, design,
accessibility, collaboration, community, operations, everyday routines, resourceful
habits, and getting started. Each topic has ten pages with distinct authored
introductions, grouped into `explore`, `guides`, and `field-notes`.

This is a Content-management fixture, not a second website build. The independent
[small Vite website](../mdx-content-site/README.md) demonstrates static publishing,
MF2, and push/translate/pull/build. This example exercises two catalogue pages,
directory navigation, exact/prefix/contains search, shared-module previews, and
translation editing. **It does not measure or establish performance at 100,000
assets.**

## Generate and validate

From the repository root:

```sh
source webapp/use_local_npm.sh
npm --prefix examples/content-catalogue-demo test
npm --prefix examples/content-catalogue-demo run generate
```

No package installation or third-party dependency is needed. Authored fixture data
is in `scripts/catalogue.mjs`; generation is deterministic. Generated source MDX,
partial French fixtures, and `manifest.json` are under ignored `.generated/`.
`seed-content/` contains only the 72 sources with a French fixture, so CLI import
does not request missing target files; push always uses the complete `content/`.
Generation replaces that directory, so edit the fixture data rather than generated
files. The manifest records exact counts and every generated path.

All 720 source strings have explicit `mojito-id` markers. The optional French seed
contains 180 translations across all 60 modules and one featured page per topic
(72 assets). The other 108 pages deliberately have no French fixture, providing
both translated and untranslated content to inspect. These are demonstration
translations, not a Codex-generated or reviewed result.

## Push, seed, and pull

Use the Java CLI and a Mojito server built from this checkout. Configure external
blob storage for asset templates and asynchronous payloads, as described in the
[small website setup](../mdx-content-site/README.md#push-and-pull-against-a-development-mojito-server).
The scripts use ordinary CLI commands; they do not start a server or write data
through internal APIs. A CLI wrapper supplies your server and authentication.

```sh
cd examples/content-catalogue-demo
export MOJITO_BIN=/absolute/path/to/your/development/mojito
export MOJITO_REPO=content-catalogue-demo

bash scripts/mojito.sh create   # once, in a new dedicated repository
bash scripts/mojito.sh push     # generates and uploads all 180 MDX assets
bash scripts/mojito.sh seed     # optional partial French demonstration targets
bash scripts/mojito.sh pull     # French output in ignored localized/
```

Use a **dedicated repository**: push removes assets omitted from its source tree.
Do not point this fixture at a repository containing real work. The create command
fails if the name already exists. Generation and tests are local; push, seed, and
pull only run when explicitly invoked.

In Mojito, open **Content**, select `content-catalogue-demo`, and use `en` or `fr`.
The tab is an admin opt-in: enable **My Settings → Admin features → Show Content
tab** and save. Direct Content URLs remain available to admins with the tab hidden.
The folder tree opens children on demand and keeps the selected hierarchy beside
the results; on small screens, collapse **Folders** to make room for the list.
Suggested paths to explore:

- At the root, advance from the first 100 assets to the remaining 80.
- Open `pages/`, choose `writing/`, then `guides/`. Search by full path prefix
  `pages/writing/guides/`, or use Contains for `feedback` across the repository.
- Open `pages/planning/explore/define-a-useful-outcome.mdx` in French. Choose
  **Edit translations**, then click a passage to edit beside it. Try **Save & next**
  and **More details**; **Back to content** restores the catalogue position.
- Open `pages/design/guides/design-error-recovery.mdx` to inspect the Facilitator
  and Visitor alternatives. The featured page in each topic offers Individual
  and Team alternatives; all 24 scenario pages use supported `PreviewChoice`.
- Edit `modules/shared/patterns/NextStep.mdx` and inspect its repeated uses in a
  featured page. Reuse points to the same asset/string identity.

## Composition boundaries

`pages/` and `modules/` are conventions of this integration. Mojito's catalogue
does not infer these roles or add role fields to the repository model. Every file
is an ordinary MDX asset and can be opened independently.

Imports are relative and static, with no arbitrary JavaScript or props. The
deepest path has three include edges:

```text
page
  → topic Overview (or audience alternative)
    → shared practice pattern
      → shared note
```

Every module has at least two importing assets. The generator checks missing
imports, cycles, depth, stable unique segment IDs, and French module coverage.
CLI push provides the actual Mojito MDX extraction check; the Node checks do not
pretend to replace its parser. These fixtures use language-only `en` and `fr`.
