# MDX preview and document review

## Scope

The Java CLI exposes the small example as `mojito demo-create -n ContentDemo -t
content`. It packages the canonical MDX/MF2 fixtures from `examples/mdx-content-site`
and seeds English source with `fr`, `de`, `es`, `ja`, and `ar` targets through
ordinary repository, push, and import commands. It needs no Node runtime and
creates no review decisions. Nonempty output directories and existing repository
names are rejected. The separate 180-asset generator remains a navigation fixture.

An MDX asset can be pushed with its source template, translated as ordinary Mojito
text units, and read as a localized page from repository Content or a Review
Project. The preview is the main reading surface; selecting a passage opens the
existing editor for that context.
The reader uses an unboxed page and module flow. File paths remain in navigator
hover details and accessible names; module headers show the component name with
the full path on hover, without repeating a file header above the page.
The desktop navigator uses the shared file tree: `/`, repository names, folders,
and filenames. Only the current review project's documents are included. Repository
IDs keep identical paths from different repositories separate; branches get a level
when needed. Search also matches translated titles and content. Compact panes keep
the Browse control, and selecting a review string reveals its file and ancestors.
The preview opens directly on content, without an instruction banner. Content's
toolbar exposes optional preview help; Review Project includes preview gestures
in its existing shortcuts dialog. Link tooltips show the current platform's
modifier for editable passages, and the source locale has a compact read-only label.
Review previews use subtle pending highlights and compact margin dots/checks by
default; hover titles and accessible descriptions identify the status without
repeating labels below every passage. The preview editor exposes **Accept** and
**Accept & next**, with shortcuts documented in the existing shortcut bar. The
latter uses the same save-and-advance handler as Ctrl/⌘+Shift+Enter and advances only after the
save succeeds; Ctrl/⌘+Enter accepts in place. Clicking the compact remaining count
moves through pending strings across pages and module variants, skipping completed
decisions and deduplicating reused strings. There is no separate start-review step
or CTA. When no pending string has a valid document mapping, the count opens the
pending list; unmapped strings remain included in that count.
Content offers a compact editor anchored to the selected passage, preserving the
document's width and scroll position. The editor is up to 800px wide, bounded by
the viewport. Source stays visible above Translation with matching text size, weight, and contrast; an
icon-only close control sits at the top right. Save & next saves
before advancing to the next visible passage, while Ctrl/⌘+Enter saves in place.
Ctrl/⌘+Shift+Enter uses the same save-and-advance action. Content and Review reuse
the same bottom shortcut bar; the compact editor has no shortcut footer.
When a target comment is being edited in the side panel, save or cancel that
comment before advancing to the next passage.
The placement icon beside the close control offers **Open side panel** or
**Edit inline**, with matching tooltips. **Open side panel** docks the same editor
in a resizable right pane, reducing the preview width instead of covering the
document. **Edit inline** returns it to the
selected passage. Draft text, selection, and AI conversation survive placement
changes. New passage selections keep the chosen placement. Below 80rem, docking
collapses the file explorer; its existing handle can expand it again.
Ordinary clicks on preview links keep selecting the passage for editing;
⌘/Ctrl-click or Option/Alt-click follows the link. A focused link also follows on
Enter; source-locale links also allow an ordinary click. There is no edit/reading
mode switch or close-preview action: the explorer selects the active file and
clicking a target passage opens its editor. Saving is explicit in the editor.
Relative page links open another
preview on the same repository branch (or a document already in the Review
Project), retaining drafts. Links inside included modules resolve relative to the
containing page, matching ordinary rendered MDX links. The preview convention maps
`.html`/`.htm` to `.mdx`,
bare routes to `.mdx`, and directory routes to `index.mdx`; custom website route
mappings are outside this adapter. Local fragments scroll to a visible block ID
or heading; a fragment on another page currently opens that page. Missing targets
show feedback without leaving Mojito. Safe external links open separately; unsafe
URL schemes remain inert.
Review Project uses the shared resizable master/detail layout for both its
preview/editor split and page navigator, with separate saved widths for Preview,
List, and navigation. Dividers support pointer and keyboard resizing; the outer
pane can collapse and expand without unmounting an editor draft. Closing the
editor returns the preview to full width. Navigation starts collapsed on smaller
screens. Below 1100px, the Review Project editor
becomes a drawer with focus containment and a return-to-preview action; phone
screens use its full width. Resizing preserves the mounted editor, and closing
uses the existing draft retention. Review editor columns follow the pane's width
instead of the browser width, so a laptop's narrow pane stays readable.
Translation saves, decisions, retained drafts, permissions, integrity checks,
and conflict recovery keep their existing
owners; the document view does not create a second write path.

The first version supports static MDX prose and bounded includes of other MDX
assets. It does not execute JavaScript or load the site's React components.
Unresolved component boundaries and fenced code remain read-only context.
The reserved `PreviewMessage` adapter formats catalog messages with literal sample
inputs. General React component behavior, full site styling, and arbitrary MF2
expressions inside MDX need further adapters.
This implementation is in the Java CLI and backend; the independent Rust
converter does not yet include MDX.

The runnable [MDX content-site example](../../examples/mdx-content-site/README.md)
uses Vite, React, and the official MDX plugin. It contains two pages, nested and
reused modules, a module-choice dropdown, French translations, and a Java CLI
integration test, using language-only locales `en` and `fr`. A replaceable content
adapter assembles pulled files into locale trees so unchanged MDX imports resolve
correctly. `npm run test:e2e` exercises
the local HTTP push/import/pull flow before building prerendered HTML and browser
assets for static hosting. The README also documents an opt-in integrated Mojito
UI demo with an ephemeral database and test blob provider.

## Author and push

For example, save `content/invite.mdx`:

```mdx
import { Callout } from './components';

{/* mojito-id: invite.title */}
# Invite your team

{/* mojito-id: invite.intro */}
Share **ideas** and [projects](/projects) in one workspace.

<Callout>

{/* mojito-id: invite.tip */}
You can invite more people later.

</Callout>
```

Use the Java CLI with matching server support:

```sh
mojito push -r Content -s ./content -ft MDX
mojito pull -r Content -s ./content -t ./localized -ft MDX
```

The server must route `asset-content` to external blob storage. For example,
with Azure already configured:

```properties
l10n.blob-storage.routing.prefixes.asset-content=azure
```

An external default route also works. Azure, S3, and the existing
Azure-with-database-fallback route are supported; the latter writes only to Azure.
A database route rejects MDX upload instead of storing a payload in `mblob`.

`MDX` is an explicit file type, outside the default file discovery set. Its
default filter options select portable conversion; an explicit `--converter
okapi` is still respected and is unsupported for MDX. The normal push request
sends the original source, not flattened extracted strings. Local `extract`
also understands this file type. Default localized filenames follow the existing
locale-in-name convention, for example `invite_fr.mdx`.

An optional `{/* mojito-id: stable.name */}` comment owns the next translatable
block. Use these IDs for authored documents so changing or moving the text keeps
its logical name. Unannotated blocks get a content-derived name, with occurrence
suffixes for duplicates; changing their source can therefore change their name.
IDs are local to the asset. The normal Mojito source identity and revision rules
still apply when text changes, even with an explicit logical ID.

Importing an already localized MDX file requires explicit `mojito-id` comments
on every translatable block, because content-derived names change with language.
Ordinary source push and localized pull do not require explicit IDs.

## Supported format

- ATX headings, paragraphs, single-line ordered/unordered list items, and
  single-line blockquotes become separate text units in reading order.
- Inline emphasis, code spans, and Markdown links stay inside their enclosing
  text unit. This preserves sentence context for translators.
- Single-line static imports before the content, standalone component tags
  without attributes, thematic breaks, and fenced code remain template-owned.
- Unsupported constructs fail with a line-specific diagnostic. These include
  expressions, exports, component attributes, inline JSX, raw HTML, frontmatter,
  tables, reference links, setext headings, and multiline list items.

The source skeleton keeps original source bytes and translation slot offsets.
Localized output replaces only those slots. Translation insertion validates that
the replacement cannot add executable syntax or change the document's block
structure. Code, imports, component boundaries, list markers, and untouched
source whitespace survive reconstruction.
Link destinations/titles and inline code are protected; their labels and surrounding
prose can be translated, and protected spans can be reordered.

This is a supported subset, not full MDX compiler compatibility. Each file owns
its strings: an included module must be pushed as its own source asset. Localized
output preserves authored imports; CLI pull does not rewrite their paths to the
locale-in-name output filenames or bundle modules.

## Nested modules

A default import gives the preview an explicit template mapping. For example,
`content/invite.mdx` can contain:

```mdx
import Steps from './modules/steps.mdx';

{/* mojito-id: invite.title */}
# Invite your team

<Steps />

<Steps />
```

Push `content/modules/steps.mdx` to the same repository and branch:

```mdx
import Tip from './tip.mdx';

{/* mojito-id: steps.create */}
1. Create a workspace.

{/* mojito-id: steps.invite */}
2. Invite teammates by email.

<Tip />
```

`content/modules/tip.mdx` contains its own prose and string IDs. The preview
resolves each relative `.mdx` import against its owning asset's directory, then
loads the referenced asset's successful extraction and convention-derived source
blob. Lookup stays within the same repository and exact branch; there is no
fallback to a different branch, filesystem access, network import, or JavaScript
execution. Imports with `../` may stay inside the repository but cannot escape it.
Module specifiers and resolved paths are limited to 512 characters.

Expansion allows three include levels below the root document. An asset already
on the active include path is blocked, so `A → B → A` cannot expand recursively.
The same module may appear repeatedly as a sibling; both occurrences refer to the
same underlying Mojito strings. A response is limited to 100 module/root visits,
2,000 expanded blocks, 5,000,000 fetched source characters, and 5,000,000 emitted
text characters. Repeated cached modules count again toward the output limit.
Each source is limited to 1,000,000 characters. The current blob API fetches a
complete object before its size is checked; the last fetch can exceed the source
budget, after which no more objects are fetched.

The preview marks module boundaries and their source assets. Missing templates,
cycles, excessive depth, and unsupported component forms remain visible at their
include location. Except for the explicit `PreviewChoice` builtin described below,
a component with paired tags and children remains source context;
this version has no `children` slot, translatable props, named-export mapping,
package aliases, or automatic rendering of `.tsx`/`.jsx` components. Those need
an explicit adapter or an MDX template supplied by the author.

Project access is checked first. Module lookup follows the existing authenticated
Workbench source-read policy, restricted to the root's repository and branch.
Included strings get editable IDs only when exact source and identity match rows
in the current project. Other included text is source context; add those assets'
strings to the project to translate or review them. Repeated instances share saves
and decisions but keep separate occurrence identities for selection and scrolling.

The review-project root list comes from assets owning strings in the project.
A composition file containing only imports and module tags has no text-unit row
to anchor it as a project root. Repository Content browsing lists these assets
directly and can open them without a review project.

## Static preview choices

The reserved `PreviewChoice` builtin supplies a small, explicit set of alternatives:

```mdx
import Individual from './modules/audience/Individual.mdx';
import Team from './modules/audience/Team.mdx';

<PreviewChoice>
<Individual />
<Team />
</PreviewChoice>
```

A choice must contain two to eight direct, self-closing components mapped to
default relative MDX imports. Attributes, expressions, direct prose, and arbitrary
child wrappers are rejected. A referenced module can contain its own choice,
subject to the existing include depth and cycle limits. Do not import a component
under the reserved `PreviewChoice` name.

Both alternatives are extracted and included in the review response. A dropdown
shows one at a time, using the first localized heading as its label when available.
In a Review Project, each option shows its mapped strings' review count; shared
strings are counted once per alternative and retain the same underlying decision.
Repository Content previews omit those review counts. Switching options
does not save text or create decisions. Selecting a review row in a hidden
alternative reveals it, including when resuming an editor draft. An incomplete
choice in a preview response shows the available alternatives with a warning.

This is a static preview convention, not execution of arbitrary React conditions.
The example website provides its own matching dropdown adapter. The builtin does
not define market rules, locale-based composition, or page-level approval.

## Blob convention and read API

MDX uploads write their complete payload through `StructuredBlobStorage` before
saving the existing extraction-job metadata. The storage keys are derived from
fields Mojito already has:

```text
asset_content/v1/{assetId}/{branchId}/source/{contentMd5}
asset_content/v1/{assetId}/{branchId}/catalog/{contentMd5}
```

Asset ID alone would allow branches and revisions to overwrite one another. The
branch and content hash keep those payloads separate. Retries use the same key,
and reads verify the existing Mojito MD5 content fingerprint. Catalog uploads use
their own path and never overwrite a source template. No blob locator column,
additional database table, or schema migration is needed.

The extraction job still uses its existing `AssetContent` metadata row and ID.
For MDX, its legacy `content` field is empty, and extraction derives the blob key
from the asset ID, branch ID, content hash, and extracted-content flag. Failed
uploads do not save that row or fall back to database blobs. The job input contains
the content-row ID and options, not document bytes.

Review derives the source key directly from the successful branch extraction.
It neither retains nor queries the upload's `AssetContent` row, so ordinary
orphan-row cleanup cannot remove its template. A failed or pending extraction
does not select a new template. Segmented source strings and translations remain
ordinary Mojito text-unit rows. Existing inline payloads for other formats are
not migrated by this change.

A catalog-only upload does not create a source template. Preview requires an
original-source blob matching the current extraction's content hash; an existing
matching source blob can be reused. Missing blobs produce an explicit unavailable
preview, with no fallback to an old database payload or a different source revision.

Objects use permanent retention. Database rollback does not delete an object
that a concurrent retry may reference. Reference-aware cleanup of superseded or
orphaned objects is follow-up work; no external deletion is introduced here.

`GET /api/review-projects/{projectId}/documents` checks access to the project and
loads templates only for its MDX assets. Each document includes its asset and
branch identity, source-content hash, ordered blocks, and warnings. Blocks map
to review rows using the exact logical name and source. A changed source is
context only until the review project contains that source; the renderer must
never attach an old translation to a new source by name alone.

Templates describe the current successful extraction, rather than an immutable
snapshot at project creation. Missing or changed source must be visible in the
UI. Multiple branch templates remain separate documents.
The endpoint returns at most 100 documents and skips parsing templates larger than
1,000,000 characters. Before fetching templates, the metadata query reads at most
2,000 project strings plus a truncation sentinel. Source strings over 10,000
characters are excluded from that projection and remain read-only preview context;
their full identity is never replaced with a truncated match. Warnings direct
remaining work to List. Rendering also bounds module expansion and total template
and emitted text sizes.

## Review behavior

When a project has multiple documents, a compact navigator lists Pages and
Modules, with search over titles, paths, source text, and saved translations.
Only one document is open at a time. Expanded includes identify modules and
their parent links within the same repository and exact branch; null and empty
branch names remain distinct. Page/module classification uses all documents
available in the project, independently of the current search filter. It is not
a repository-wide route index. Composition-only roots are available through
the repository Content page described below.

Opening another document closes the editor while retaining its draft; Resume
returns to the selected passage and its variant. In-flight saves and active
composition prevent navigation. Selecting a row through existing review controls
opens a document containing that row.

Normal projects containing only MDX default to Preview; mixed-format and other
project types keep their List default. `view=preview` selects the preview,
`view=list` selects the list, and existing `view=document` links remain compatible.
The preview uses the template's reading order and preserves full document
context even when list filters are set. Only mapped review strings are editable.
Untranslated content is visibly identified as source fallback; code, component
boundaries, missing review rows, and changed source are non-editable context.
The full-width preview opens without an editor unless the URL explicitly selects
a passage. Selecting one opens the existing editor. Closing it unmounts the
editor and its save shortcuts while retaining unsaved drafts in the existing
QueryClient cache; the reviewer can resume them. Composition and in-flight
save/recovery guards still apply. Routine review labels are optional annotations;
untranslated fallback and stale-context warnings remain visible.

The existing editor owns selection, composition, drafts, and saves as
preview/list views change. Rendered target text comes from current project rows
after a successful save; a draft remains owned by the normal editor.

Document previews use React text rendering and a small inline Markdown renderer.
They do not execute MDX, inject raw HTML, or request external media. Review status
belongs to individual strings, not to an inferred approval of every page state.

## Preview and review ownership

The product direction is a preview-first content surface. Previewing reads saved
translations regardless of whether a reviewer has decided on them. Routine
review annotations should be optional; missing translations and stale mappings
must remain visible. Passage selection brings up the established editing flow,
and closing it returns to reading without manufacturing review decisions.

A project containing every string in the asset can supply the current prototype's
translation rows. Setting those rows to `DECIDED` simply to enable preview is
unnecessary and would incorrectly record review work. Existing decisions should
only be retained when their actual source/translation revisions still match.

## Repository Content page

`/content` is an administrator-only Mojito page with a compact toolbar. Repository
and locale pickers reuse the Workbench and Repositories controls in single-selection
mode. Locale choices include display names and codes, with the source locale identified.
Branch and preview variants use the shared single-select dropdown.
Content uses the shared resizable master/detail layout: a borderless file explorer
on the left and a neutral document/translation workspace on the right. Repository,
branch, and language selectors stay together above both panes. The explorer reuses
the shared search field with a magnifier and clear control. Filtering
updates after a 300 ms typing pause; Enter applies it immediately. Search updates
replace the URL history entry and keep the current preview and translation draft.
Fresh searches use indexed path-prefix matching; existing URLs retain their explicit
search mode, including legacy substring searches. Files appear under their directories, starting
at `/`; opening a file preserves the explorer, folder position, and pagination.
The workspace does not repeat the selected filename or add a grey page background.
The top-level Content navigation entry is hidden by default. An admin
can enable **My Settings → Admin features → Show Content tab** and save; the
`contentNavigationEnabled` account preference follows them across browsers.
Only admins may change it. This preference controls navigation visibility, not
API availability: admins can still use direct Content URLs and browsing APIs
while it is off. Project managers and other roles cannot open Content or its
browsing APIs. CLI extraction/push/pull, the separate translation-candidate APIs,
and authorized Review Project previews keep their existing access rules.
Repository, branch, language, asset, directory, path search, and browse position
are carried in the URL.
There is no repository model, schema, or tag change; the opt-in uses existing
account preference storage.
Content tagging is deferred; any selected repository can be browsed for MDX.

`GET /api/repositories/{repositoryId}/content` lists active MDX assets from the
chosen branch's extraction membership, including composition-only assets. It
returns pages of at most 100 assets and metadata for the first 100 branches, plus
the selected branch when it falls outside that set. This bound applies to both
cursor and offset requests. Path search (`q`) runs
before pagination: `searchMode=prefix|exact` compares the full stored asset path
without wrapping it in `lower()`, and `contains` retains the legacy
case-insensitive substring search. Matching follows the database collation for
exact/prefix mode; whitespace, `%`, `_`, and the escape character are literal
input. `recursive=false` restricts the listing to immediate files in `directory`
before pagination, including root files when the directory is empty. The default
remains recursive for existing callers and search. Listing does not fetch template blobs or infer page/module roles from
filenames. Omitting `branchId`
selects the existing null-name default branch; it never falls back to a different
named branch. No default branch yields an empty list; ambiguous defaults require
an explicit branch ID.

The browser uses `pagination=cursor`, with server-issued `after` or `before`
positions ordered by path and asset ID. Cursors are scoped to the repository,
resolved branch, search mode/query, directory, and recursive/direct-child scope;
invalid or mismatched cursors
return 400. They represent a position in current metadata, not a snapshot. Inserts
or deletions before that position do not shift subsequent results; concurrent
path changes can still move assets between pages. Legacy
offset API calls and old search URLs still work; an old offset URL switches to
cursors on the next navigation. Empty or stale pages offer First page recovery.

The browser caches each repository/branch/filter/position for 30 seconds and
expires inactive pages after 60 seconds. Search returns at most 100 matching files
per page in the explorer, with Previous/Next controls and a current-page count.
Browse and search use distinct cache/cursor scopes. Opening a file updates the
adjacent document workspace; its directory and cursors are retained in the URL.
Returning to the browser tab or reconnecting reloads the active asset list, expanded
directory pages, and preview, including during the cache freshness window. Inactive
folders stay cached; there is no background polling. Saving a translation refreshes
the preview. Listing reads metadata, not template blobs or translation rows.

`GET /api/repositories/{repositoryId}/content/directories` lists at most 50
immediate child directories under `directory`, with an optional `after` cursor.
It derives folder names from active MDX branch membership in a bounded scalar
query. Each expanded tree level fetches that directory page plus at most 100
immediate files using `recursive=false`. One expanded trail and at most eight
loaded levels bound fetched metadata and requests independently of repository size.
Deep links open a nearby ancestor window; controls move that window upward or
continue deeper without loading every ancestor. Selected ancestors remain visible
outside a sibling page. File and folder page changes replace their rows, rather
than accumulating the repository in memory. Both navigators flatten expanded
branches into rows and use the shared `FileTree` viewport, backed by Mojito's
existing `useVirtualRows` hook. Only the visible rows and a small overscan window
are mounted; selected files are revealed and keyboard navigation scrolls rows
into view. Loading stays in the Content adapter. Review Project supplies its
in-memory, project-scoped tree to the same viewport; it never calls the
repository catalogue or broadens the review scope. Review document preview retains
its existing 100-document and 2,000-text-unit bounds and reports truncation.
Cursor responses include up to 100 branch choices plus an explicitly selected
branch; a warning identifies truncation. Large synthetic navigation tests establish
request/render bounds, not production database latency at 100,000 files.

The [content catalogue demo](../../examples/content-catalogue-demo/README.md)
provides 120 pages and 60 reused modules across 66 directory prefixes, with
partial French fixtures and 24 conditional previews. It exercises real CLI
push/import/pull and multi-page browsing; it is separate from the small Vite
website and is not a production-scale benchmark.

### Large repositories

The target includes repositories with thousands to hundreds of thousands of
assets. The catalogue is the management surface; the document navigator is local
context inside a review project, not a repository-wide asset tree. Fixed result
pages bound browser work, but do not establish acceptable database latency at
100,000 assets. Cursor queries avoid deep OFFSET scans and exact/prefix filters
leave the existing `(repository_id, path)` index usable. Database plans still
depend on branch membership and collation. Contains search can scan paths, and
the distinct immediate-folder query can scan/group metadata under its prefix.
Validate representative production query plans and latency before making that
scale claim. Routine regressions use production HQL against an in-memory database;
they do not substitute for production database measurements.

The next backend/browser increments are:

- Translation work queues and saved filter views. Existing URLs preserve branch,
  directory, locale, path filters, and cursor position. Full-text search, counts
  and status facets require queryable metadata;
  listing must never open every template or run one translation query per row.
- Explicit page/module roles and reverse references supplied by extraction or
  project-owned integration metadata. A filename or a directory called `modules`
  cannot universally determine what is a top-level page. Show module usage and
  open a specific parent context without expanding the entire dependency graph.
- Bulk review creation with explicit selection scope: selected rows versus all
  matching results. Resolve and limit the work on the server; do not accumulate
  100,000 IDs in the browser or mark strings reviewed merely to make previews work.

These remaining workflows are not implemented catalogue capabilities. They do not
require adding a content tag or composition logic to the Repository data model.

`GET /api/repositories/{repositoryId}/content/{assetId}?branchId=…&locale=…`
opens one bounded, expanded document through the shared inert renderer. The
backend independently enforces administrator access and validates
repository, branch, and configured locale. It reads current saved translations
using the exact successful extraction, source hash, logical name, and source text.
It does not infer membership from another branch's most recent asset extraction.
Both metadata transactions finish before external template fetching and target
queries remain bounded. Missing/stale mappings stay non-editable source context.

The source-language preview is read-only. Clicking a mapped target-language passage opens a compact
translation editor beside that occurrence without resizing or scrolling the
document. The editor stays within the viewport on narrow screens. Source text stays
visible above the translation. **Open side panel** moves the same mounted editor
into the shared resizable layout, with source, translation, actions, and AI chat
above the existing glossary, history, and metadata controls. AI chat stays in the
side panel; **Edit inline** returns to the compact editor without clearing its
conversation or cursor selection. The repository/branch/asset/language/search URL stays
in place. It reuses ordinary validation, save permissions, and the existing save
API. Text-unit detail editors send an explicit `expectedVariantId`, including null
for an untranslated baseline. The server compares that baseline and writes under
the same parent/current lock order as candidate imports. A conflict returns 409;
the editor retains the draft and refreshes current data so Reset can adopt it.
Exact feedback retries use the existing receipt before checking the baseline.
Legacy callers that omit the field retain their existing save semantics; extending
the explicit baseline to remaining clients is tracked as `TM-03`.
In-session drafts are retained by username, text-unit
ID, and locale when the editor closes or its selection changes. Escape closes the
editor and restores passage focus without scrolling; Cmd/Ctrl+Enter saves.
The shared bottom shortcut bar appears while the editor is open, outside the
compact editor. **Save & next** and Cmd/Ctrl+Shift+Enter advance only after a
successful save, skipping hidden passages and repeat occurrences of the current
string; **Next** advances an unchanged target without saving. The action stays
visible but disabled at the last passage, with a tooltip explaining why.
Closing or changing the selected occurrence cancels pending advancement. A refresh
that removes the selected passage closes the editor while retaining its draft.
Preview text always
comes from saved translations; it does not render unsaved drafts. A successful
save invalidates all Content previews for the repository so reused modules refresh.
The repository view never fabricates review rows or decisions, and it does not
show review-project progress. Creating a review from this scope is a separate
future action.

The shared renderer caches only valid parsed source templates in process, keyed
by repository, branch, asset, and successful source-content MD5. Concurrent loads
for the same key are coalesced. A 32 MiB estimated retained-weight limit, a 32 KiB
minimum entry weight, and 15-minute idle expiry bound the cache; the estimate is
not an exact heap measurement. Missing, invalid, corrupt, unsupported, or oversized
templates and storage errors are not retained. Authorization, current branch
membership and include resolution, target translations, variant status, and string
mappings are read for each request. Source-size and expansion limits also apply
on cache hits. No database payload, persisted cache, or repository model is added.

Review-project previews retain their own project ACL and row membership; included
strings outside that project remain source context. Opening either preview never
creates a project, changes assignments, or writes decisions.

## Generic composition boundary

The proposed integration boundary is project-defined preview scenarios, rather
than built-in market logic. An integration supplies scenario identities and
labels, the selected module/template revisions, and rendered-text-to-string
mappings. Its own rules resolve arbitrary context such as language, region,
audience, or product configuration. Mojito reviews the resulting content and
reuses shared string identities; it must not infer complete state coverage from
one rendered example. Integrations need to expose representative scenarios.

One authored MDX asset currently produces generated localized MDX files on pull;
resource-bundle output could use the same source and review model later. Localized
MDX is one supported export, not a mandatory website directory structure or
runtime. The example's versioned content adapter assembles locale trees for its
Vite build; another site can replace that adapter while keeping the Mojito flow.
The current implementation preserves the source structure and does not author
new localized modules or structural overrides in Mojito.

Dynamic components keep their code and message catalogs separate from MDX prose.
The example now demonstrates count/date controls using `mf2/javascript`. Its build
parses source and pulled catalogs into models and bundles them with a parser-free
formatter and Intl registry shared by prerendering and hydration. The CDN output
needs no Mojito connection.

The reserved self-closing `PreviewMessage` tag accepts a relative `*.mf2.json`
resource, a catalog key in `name`, and JSON primitive `args` (up to 32 entries).
Catalog templates use external blob storage under the existing asset/branch/hash
convention. The preview resolves the exact catalog in the same repository and
branch, applies the existing expansion budgets, and maps its source to the
catalog's ordinary TM/review row. `previewArgs` accompanies an `mf2` block;
`sourceLocaleTag` prevents source fallbacks from using target-language formatting.
Unknown references remain visible context. Catalogs are dependencies, not top-level
MDX pages in the navigator. Mixed MDX/MF2 projects default to Preview.

Mojito formats one declared sample through its bounded MF2 parser and Intl
registry. Formatted output is React text, never executable markup. The site can
provide interactive controls with the same initial values; arbitrary components
remain Git-owned. A sample does not establish coverage of all plural forms,
arguments, or runtime conditions. Direct MF2 syntax inside MDX, rich-markup
adapters, and general component schemas remain outside this slice.

The optional example translation worker snapshots exact asset/string identities,
source and extraction baselines, existing targets, and page/module context. It
reads the branch extraction ID and owning asset's content hash through a bounded
candidate-source metadata endpoint; text-unit search exposes the merged extraction.
It validates generated candidates before calling the fill-missing endpoint. The
endpoint locks the parent text unit and current pointer in existing review-save
order, verifies repository/branch/extraction scope, rejects existing target history,
enforces MDX/MF2 integrity, and saves `REVIEW_NEEDED`. It cannot replace human
targets. Import is per-row and reports partial completion on a conflict; the
ordinary fixture import and human save paths keep their existing semantics.
This worker is a replaceable generation example, not a new translation scheduler.

The broader website requirement includes translation, adaptation of content and
structure, and content authored directly for a locale or market. Mandatory
English reference copy was explored as a simplifying convention, but must not
be treated as the universal product requirement. A source-language module can
be unpublished in that language; an empty source module, however, supplies no
strings for the current extraction/translation flow.

A proposed boundary keeps component implementations, allowed content schemas,
and rendering/eligibility rules in Git while allowing Mojito to author localized
content and structured overrides. Project-defined schemas could permit adding,
removing, or reordering approved blocks without accepting arbitrary executable
MDX. This deliberately includes some CMS functionality; its scope needs to be
driven by real local-editor tasks. Locally authored content needs explicit
identity, revision, inheritance, and review semantics rather than invented
English source strings. These capabilities are design work, not implemented
features of the current static MDX prototype.

## Follow-ups

- Add an explicit Start review action from a selected Content scope; keep ordinary
  browsing independent of project creation and review decisions.
- Verify the external `asset-content` route in deployment; define
  reference-aware cleanup for superseded and orphaned source objects.
- Try representative marketing documents before widening the MDX subset.
- Define adapters for React component schemas, translatable props, `children`
  slots, and named/package imports when real documents require them.
- Define the project-owned preview-scenario contract, schema-constrained content
  overrides, and locale-authored modules. Keep market rules and executable
  component implementations in the integration; specify inheritance, revisions,
  and review semantics before adding localized authoring to Mojito.
- Define MF2 message boundaries, typed preview arguments, rich markup mappings,
  and review coverage for hidden forms before embedding MF2 in MDX syntax.
- Decide whether projects need immutable template snapshots and whole-document
  review revisions before adding page-level approval.

## Validation

Local checks cover extraction and reconstruction, external template storage,
repository and review-project access, and editing through the existing UI. The
integrated demo uses an isolated HSQL database and test blob provider. This is
local implementation evidence, not a production deployment or scale benchmark.

Verified locally on 2026-09-17:

- Root Spotless, frontend formatting, lint, TypeScript, and production build pass.
  All 1,661 frontend tests pass across 124 files, using two workers to avoid local
  test-runner contention. Coverage includes permissions, cursor navigation, the
  lazy folder tree, reused modules, variant selection, compact editing, retained
  drafts, focus through portal controls, and stale-save recovery.
- The combined Maven reactor run passes 234 focused tests: 41 common, 185 webapp,
  and eight CLI tests. Checks include blob-only template persistence, rejected
  database routes, corrupt/missing objects, bounded previews, do-not-translate
  mappings, cursor queries, permissions, candidate preservation, and save guards.
  The concurrency fixtures use real HSQL/JPA transactions and parent/current-row
  locks; they cover competing first saves, both candidate/human orderings, and
  recreation after deletion. Production database lock behavior still needs its
  own validation.
- The real CLI HTTP round trip creates a repository, pushes MDX and MF2, imports
  French fixtures, pulls localized files, and checks byte-exact reconstruction
  and nested/shared-string mappings. Mock external providers hold source and
  asynchronous job payloads; database payload checks run before and after import.
  The site builds from the actual pulled English/French files, with MF2 resource
  models included at build time and an interactive calendar in the example.
- A clean public-registry install and 35 site/MF2/translation-worker tests pass;
  three catalogue-generator tests also pass. The worker tests use a fake process
  and verify that Mojito credentials are excluded from the subprocess environment.
  No model call is required by a normal build or test run.
- Browser checks cover reading mode, optional double-click, passage-anchored
  editing without moving the selected text, draft retention across close/reopen
  and full details, real Save and next, and the structured MF2 editor. The compact
  editor fits 390px and 1280px viewports. Review previews cover nested and reused
  modules, alternatives, source fallback, warnings, and switching to List.
  Earlier synthetic review-decision checks are distinct from the real Content
  save flow. The optional AI-review backend is not configured in the demo.

Vite reports existing large-chunk and mixed-import warnings. Java checks report
existing deprecation, AspectJ, and logging configuration warnings. No live data
migration, external blob upload, push, or deployment is part of these checks.
Deployment routing, orphan cleanup, production concurrency, representative
content compatibility, and 100,000-asset query plans remain rollout follow-ups.
