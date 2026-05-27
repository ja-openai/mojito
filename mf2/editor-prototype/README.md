# MF2 Translation Workbench Prototype

Browser prototype for a compact Mojito translator workbench backed by the
public `@mojito-mf2/core` JavaScript parser/runtime package. The Rust
parser/runtime remains available as an optional comparison endpoint when the
local Node server is running.

The production-shaped component demo is React + TSX so it can move toward the
Mojito frontend without a rewrite. The library lab remains plain browser code
for low-level parser/runtime, CodeMirror, ProseMirror-style, and bidi experiments.
Both parser/preview paths are real and run in the browser through the native
JavaScript core.

Current prototype:

- translator-oriented React component playground
- JavaScript-core parse diagnostics, rendered preview, and formatted parts
- target editor direction derived from the selected locale
- independent rendered preview direction, so output can follow the target locale
  or be forced to LTR/RTL/auto for bidi QA
- opt-in Rust comparison diagnostics from the same local server
- structured variant editing for `.input` + `.match` messages
- mechanical plural template insertion
- fixed exact numeric variant row insertion and preview for values such as `0`,
  with UI copy and generated key previews that distinguish exact keys from
  CLDR categories, show surrounding multi-selector fallback keys, and offer
  source-context exact-row shortcuts
- locale/argument preview
- compact source-contract summary, with raw source MF2 available from a drawer
  and raw target MF2 available through the editor's raw-mode switch
- target selector checks against source selector names, order, and annotations,
  without duplicating those selector mismatches as generic placeholder issues
- target variant coverage checks against source variant keys; variant row order
  may differ, while selector order remains significant because keys are
  positional and can decide which overlapping row wins
- target placeholder checks against the raw source contract
- target markup checks against source markup names, open/close/standalone shape,
  and option/attribute prop names
- source/target scenario matrix in the component playground for plural and
  multi-selector coverage, with status and reason columns for coverage gaps
  plus bulk actions for source-defined missing rows, target-locale CLDR rows,
  specific target-locale multi-selector row hints, and target-specific row
  cleanup
- single Mojito-shaped component demo at `/review-demo.html`:
  - React/TSX `Mf2TranslationEditor` component shared by the production-shaped
    page rather than duplicated review/workbench shells
  - compact project-review layout with literal source placeholders, target
    language selection, rich editing, raw mode, preview, and diagnostics
  - dense embed configuration that lets the host review page own the source
    card and hide sample-argument inputs, while the library lab can still show
    the richer inspection surface
  - placeholder autocomplete from `{` or `$`, with filtering examples using
    `count`, `count2`, `country`, `coupon`, and `customer`; rich completion
    consumes the live typed trigger/query when accepted, keeps the popup to
    matching active-source-form placeholder options/status only when source and
    target selector names, order, and annotations still match, without adding a
    second keyboard tab path, and raw CodeMirror uses the same source-constrained filtering
    instead of a second label-based filter that can hide `$`-triggered matches;
    raw completion closes and recomputes when form navigation changes the active
    source contract
  - compact Insert special helper in the active editor shortcut strip for
    no-break spaces, bidi marks/isolates, and a typographic apostrophe; helper
    controls preserve the active rich-editor selection so insertion and repair
    actions do not jump to the end of the form
  - stable placeholder-chip deletion from either adjacent caret positions or an
    exact selected variable chip, including browser caret placements on either
    side of the invisible boundary around a chip
  - raw CodeMirror updates that avoid rewriting the focused document on each
    input, preserve undo/redo behavior on the production-shaped page, and keep
    the raw selection clamped in place across host-driven replacements
  - raw helper actions dispatch through CodeMirror transactions, so Insert
    special and Restore placeholders remain part of native undo/redo history
  - rich-mode controlled `target` updates flow through the ProseMirror-backed
    adapter, preserving local edit history when the host echoes the same pattern
    while still repainting for external host updates and structured-row changes
  - raw-mode shortcut strip for placeholder completion, undo/redo, special text
    insertion, source-placeholder restore, and inline parser-error highlighting,
    while keeping the source contract and literal source comparison visible when
    the host enables the source panel
  - rich editor min-height follows the larger of the active source and target
    form line counts so blank generated locale rows do not collapse below the
    source form they are translating
  - target-locale CLDR helpers normalize underscore locale IDs such as `pt_BR`
    before using `Intl.PluralRules`, and fall back safely if a host supplies an
    invalid locale string
  - target-locale row helper summaries group recommended CLDR categories by
    selector, so multi-selector messages stay scannable
  - row-helper insertion blanks source-derived fallback text but can reuse an
    existing translated fallback, so the action label does not promise blank
    rows when the generated content is intentionally prefilled
  - exact numeric rows only suppress CLDR category suggestions for matching
    surrounding selector keys, so a specific `gender: male / count: 1` row does
    not hide the generic `count: one` target-locale helper
  - rich-mode model printing preserves expression annotations plus markup
    options/attributes, so editing `{#link href=... @title=...}` does not
    silently strip the source contract metadata
  - normalized rich-model printing escapes quoted literal option values and
    preserves quoted variant keys such as `|needs review|`, so selector values
    with whitespace or syntax characters remain parseable after editing
  - normalized rich-model printing also escapes literal pattern text braces and
    backslashes while preserving existing placeholder and markup parts, including
    escaped placeholder-looking text such as `\{$name\}`
  - `.input` and `.local` declaration expressions preserve attributes and
    literal `.local` operands in the normalized rich-model round trip, so
    contract metadata stays visible in the source summary and raw output
  - rich and raw placeholder insertion use the source expression shape when a
    placeholder carries function options or attributes, and rich placeholder
    chips round-trip that exact MF2 token while showing compact `{$name}`
    labels and remaining single delete targets; repeated source placeholders
    prefer the richer expression shape for completion when a later occurrence
    carries options or attributes; quoted literal options
    with escaped pipes or trailing backslashes still keep the full source
    expression in the contract; declaration-only `.input` placeholders prefer
    the original source declaration expression, including bidi-wrapped variable
    syntax, for completion/restore; completion/restore cover
    declaration/option-only source variables while excluding selector-only
    variables unless the selector is reused in an option; completion, restore,
    and row-scoped missing placeholder diagnostics use the same active-form
    contract, keeping declaration-only placeholders available for the current
    target form while avoiding placeholders and option variables that belong
    only to other source forms or to source markup omitted from the target form;
    source declaration
    rows are matched by declaration name rather than declaration type/order; `.local`
    placeholders insert and restore as their local variable reference without
    offering the local declaration's operand variables, including input-option
    dependencies, as separate target text or omission warnings; target-added
    diagnostics ignore those operands inside copied local declarations, even
    when the local reference itself is omitted, but flag them if they appear in
    target text
  - source/target placeholder contracts include selector-only variables and
    variables referenced from function and markup option values, even when those
    variables are not rendered as separate rich chips; selector variables reused
    in options are still checked as option placeholders, and target-added option
    operands are flagged only when they are rendered directly or belong to an
    owning source placeholder, selector, or markup node kept by the target
  - raw CodeMirror mode preserves native undo/redo and supports the same
    Shift+Up/Shift+Down form navigation plus optional Command/Ctrl+Enter
    next-form navigation as the rich editor without taking over Tab or modifier
    text-selection shortcuts; raw-mode document-changing helpers, including
    Insert special, Restore placeholders, target-locale row insertion, and
    invalid-row cleanup, dispatch CodeMirror transactions instead of remote
    React rewrites; the same target-locale row helper is visible in raw mode
    as well as rich mode, and commands queued during the first lazy raw editor
    mount replay once the CodeMirror view is ready
  - raw CodeMirror placeholder completion stays source-filtered while the query
    changes, so continuing to type after `{` or `$` narrows the same list and
    acceptance replaces the full typed token; braced completions absorb the
    typed closing brace, tolerate optional whitespace after `{`, `$` completions
    handle dotted, `+`, Unicode, bidi-wrapped, and longer variable names without
    a fixed lookbehind cutoff, leave following literal text alone, malformed
    `${...`/`{{...` prefixes stay literal, and escaped trigger characters stay
    literal
  - raw parser diagnostics call out inline `.match {$name ...}` selector
    expressions directly; the current model expects declared selector variables
    such as `.input {$name :string}` followed by `.match $name`
  - rich-mode Shift+Up/Shift+Down and Command/Ctrl+Enter form navigation keep
    priority even while the placeholder menu is open; plain Up/Down and plain
    Enter operate suggestions, focused collapsed rows treat only plain Enter or
    Space as row activation, Escape closes the placeholder menu without
    reopening it for the same token, and modifier text-navigation keys remain
    native
  - rich-mode editing is constrained by the ProseMirror schema so pasted HTML
    cannot reshape the target pattern outside text and placeholder atom nodes
  - row issue badges and active inline diagnostics use structured form metadata
    for target-only, missing-source-row, missing-placeholder, and overlap issues
    instead of matching translated diagnostic prose by substring; target-only
    rows with no matching source row or selector name/order/annotation
    mismatches no longer borrow the first/key-compatible source row for active
    comparison or source-scoped helpers; only parser/runtime
    diagnostics without form metadata still appear beside the active editor,
    while target-locale missing-row warnings stay in the row helper and full
    diagnostic list instead of repeating beside every form; row helpers and
    rich-mode mutations require the current target to parse so stale last-valid
    raw models are not mutated
- library comparison lab at `/lib-lab.html`:
  - CodeMirror 6 raw source and raw target MF2 editors
  - CodeMirror lint diagnostics from the JavaScript parser/runtime
  - native no-library raw textarea prototype beside CodeMirror, sharing the
    same target model with parser/contract diagnostics, source-placeholder
    completion from `$` or `{`, lightweight MF2 token highlighting, Restore
    placeholders, special text/bidi helpers, target direction, and undo-friendly
    `setRangeText` edits for dependency evaluation
  - compact WYSIWYG-first default that keeps raw CodeMirror, plural helpers,
    scenario review, and parts/bidi inspection collapsed until needed
  - visible source sample picker for exercising plural, offset, markup, and
    multi-placeholder contracts without opening the raw editor
  - read-only source prose preview matched to the active target form, keeping
    placeholders literal so the translator compares against the source contract
    without reading raw MF2 or confusing sample-rendered values for source text
  - ProseMirror WYSIWYG variant editor with advisory source placeholder chips,
    inline parser/runtime diagnostics, and IME-safe composition handling before
    syncing text back into the MF2 pattern
  - target-language selector beside the prose editor, synced with preview
    locale and target direction
  - compact form rows where the selected row expands into the prose editor
    inline with the form key and stacks under the key on narrow screens;
    Shift+Up/Shift+Down and Command/Ctrl+Enter move across forms from inside
    the editor, while Tab and modifier text-navigation keys remain native
  - visible row-helper strip with one-click target-locale plural row creation,
    so languages such as Arabic can add all missing CLDR forms without opening
    the full row tools drawer and without copying source-derived English text;
    `other` is still surfaced when a target has no fallback row, but skipped
    when `*` already covers it, and target-only generic `other` rows are flagged
    for review when they duplicate fallback coverage; row-helper buttons carry
    specific action labels with the affected selector/category summary and
    count distinct forms for multi-selector invalid-row cleanup
  - rich editor height starts at the active source/target form's line count and
    grows with typed target content instead of defaulting to a large multiline box
  - visible source contract summary for allowed inputs/selectors/placeholders
    and per-variant requirements, plus a single compact source-contract row at
    the top of the form list so placeholders are not repeated in every row
  - placeholder autocomplete constrained to the source-approved placeholder set,
    opened from the prose editor by typing `$` or `{`, filtered by continuing to type
    the placeholder name, and committed as a placeholder chip with Enter or click;
    raw CodeMirror target/source editors offer the same `$`/`{` placeholder completion
  - compact rich and raw shortcut strips under the active target editor for
    placeholder autocomplete, form navigation, Insert special no-break/bidi/apostrophe
    helpers, and count-aware source-placeholder restore only when a placeholder occurrence is missing
  - placeholder hinting and uniform placeholder chips for source placeholders
    that may be intentionally omitted in target variants
  - markup insertion constrained to source-approved markup names, shape, and props
  - structured active-variant key editor for multi-selector messages
  - structured row creation for new non-plural selector values and a compact
    repair action for empty target-only plural forms that do not belong to the
    selected target locale
  - generated-row preview before inserting new selector values
  - offset matcher sample support for `.local` selectors such as
    `$like_count - 1` or `$like_count - $hidden_count`, with exact,
    plural-category, and fallback scenario samples chosen so they exercise the
    shifted selector without accidentally matching fixed or CLDR-category
    offset rows; the component playground and library lab include literal and variable
    offset samples for this path
  - CLDR plural category buttons with non-exact example numbers for the
    current locale, plus separate exact numeric row insertion
  - fallback-aware plural helper row generation that does not offer a redundant
    `other` category row when the existing `*` row already covers fallback
  - fixed-row summaries beside plural category examples so exact values do not
    look like CLDR category samples
  - fixed numeric rows preview with selector-match priority over fallback rows
  - diagnostics for exact numeric rows that overlap locale plural-category rows
    with the same surrounding selector keys, plus scenario-matrix row-order
    notes so reviewers can see which physical row wins
  - selector-priority overlap diagnostics for rows such as `* exact` and
    `exact *`, where both can match and the first differing selector decides
    the winner
  - source/target scenario matrix that renders unioned source and target
    variant form coverage side by side with literal placeholders, explicit
    source-row-missing, target-specific-row, and target-locale-CLDR-row badges
    plus row-kind, key/sample labels, summary
    counts, filters, selector chips that distinguish fixed values, CLDR categories,
    fallback rows, and context values, target-locale cardinal/ordinal plural
    coverage chips that show when fixed exact rows already cover a category sample,
    row-level review reasons, and
    target warnings/actions for variant coverage gaps, including locale-aware
    CLDR plural row recommendations with bounded selector-aware multi-selector
    specific-row hints plus row actions that distinguish source-derived fixed/CLDR/context
    rows from target-locale CLDR rows and specific hinted rows, bulk actions to
    add source-defined target rows or target-locale wildcard CLDR rows, and remove non-locale
    target-specific rows, plus visible notices that split true row-order ties
    from selector-priority overlaps so exact/CLDR/fallback conflicts are not
    confused with first-differing-selector behavior
  - independent preview direction control, so target editors can follow the locale while rendered output can be checked as LTR/RTL/auto
  - debug-gated structured `formatToParts` preview plus escaped bidi-control
    markers, HTML-node preview using `<bdi>`, source/target scenarios, model
    dump, quality panel, and optional Rust comparison
  - bidi typing lab for independent textarea/preview direction, visible resolved-direction summaries, compact sample buttons with intended-sentence notes, LTR/RTL samples, modern Unicode bidi controls, selection wrapping with LRI/RLI/FSI, HTML `<bdi>` and plain-text isolation recipes, isolate-balance checks, collapsed legacy/debug controls, and logical/visual order strips
  - opt-in Rust comparison against the shared parser/runtime preview endpoint

Run:

```sh
npm install --cache /private/tmp/mojito-mf2-npm-cache
npm run dev
```

Open:

```text
http://127.0.0.1:8788/
```

The root path redirects to the single React/TSX component playground so editor
work happens against one production-shaped surface.

Open the editor library comparison:

```text
http://127.0.0.1:8788/lib-lab.html
```

Open the Mojito-shaped React component demo:

```text
http://127.0.0.1:8788/review-demo.html
```

`/workbench-demo.html` also redirects to the component demo so the production UI
does not drift across duplicate pages. The top nav intentionally links only to
the component demo and the library/bidi lab.

The dev server is Vite, so HTML pages load source modules directly and hot reload
while editing. The same `/api/format` and `/api/plurals` Rust comparison endpoints
are installed as Vite middleware.

React component API notes:

- `Mf2TranslationEditor` is the shared React/TSX editor intended to move toward
  Mojito. It can run uncontrolled with `source` plus optional `initialTarget`,
  or controlled with `target` and `onTargetChange`.
- The review demo uses the controlled path and keeps target drafts, selected
  target locale, and raw/rich mode per text unit, matching the integration shape
  expected in Mojito, while reusing the editor component across rows.
- When `locale` is provided, it is controlled like `target` and `mode`; omitted
  locale defaults to an internal `en` draft. Host updates to `locale` or
  `initialMode` sync the visible editor controls before paint without resetting
  the target draft; source or `initialTarget` changes still reset the active
  form and draft target.
- `onChange` receives `{ target, locale, mode, diagnostics }` so a host can track
  draft text, active target locale, rich/raw mode, and parser/contract health
  from one callback. Contract diagnostics include source-vs-target variant
  coverage, row-order overlap warnings, source markup shape/prop checks, and
  target-locale CLDR plural form hints using the active locale. Parser
  diagnostics keep source offsets when available so hosts can align issue
  summaries with raw editor highlights.
- `localeOptions`, `onLocaleChange`, `initialMode`, and `onModeChange` let the
  host wire real Mojito locale lists and preserve raw/rich editor state.
- Display knobs keep the same component usable in dense review rows and richer
  labs: `showSource`, `showActiveSourceComparison`, `showPreview`,
  `showArgumentInputs`, and `showDebugTools`.
- `showActiveSourceComparison` places the matched source form directly above the
  active target editor, using the shared source-match helper so wildcard keys in
  middle selector positions resolve to the same source row as diagnostics and
  preview matching.
- Rich and raw modes both surface a compact inline issue strip near the active
  editor while keeping target-locale row-helper warnings in the helper/full list
  and retaining the full diagnostic list below the component.
- Raw mode renders only the CodeMirror repair surface plus raw-mode helpers, not
  a hidden rich editor tree, and names the raw target textbox
  with the active form label for assistive tech.
- The raw CodeMirror instance is created lazily the first time raw mode opens
  and then remains mounted so raw undo history can survive mode switches; set
  `documentKey` when a host reuses one component across text units so raw undo
  history resets at document boundaries.
- Form rows show small issue badges for diagnostics tied to that form so
  collapsed rows are scannable without repeating full placeholder contracts.
- `className` allows host-specific layout hooks, and `readOnly` keeps
  diagnostics, preview, target-locale switching, form selection, and raw/rich
  viewing available while blocking target mutations and helper actions.
- When the current target has parser errors, rich mode keeps the last valid
  structure for the current source visible as a visually read-only comparison
  surface while raw mode remains the repair path; structured helpers re-enable
  after the target parses again.
- `args` is optional. When provided, sample values drive rendered preview and
  diagnostics; when omitted, the component still edits and validates the MF2
  contract.

Static build and serve:

```sh
npm run build
npm run serve
```

The static server serves `dist/` and keeps the same optional Rust comparison
endpoints for built previews.

Smoke tests:

```sh
node mf2/editor-prototype/smoke-test.mjs
node mf2/editor-prototype/model-smoke-test.mjs
node mf2/editor-prototype/component-smoke-test.mjs
npm --prefix mf2/editor-prototype run check
```
