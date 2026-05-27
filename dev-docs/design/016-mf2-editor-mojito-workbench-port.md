# MF2 Editor Mojito Workbench Port

Context

- The MF2 editor prototype now has one Mojito-shaped React component demo at
  `mf2/editor-prototype/review-demo.html` and one library lab at
  `mf2/editor-prototype/lib-lab.html`.
- This note is a future port plan. No Mojito workbench changes are part of the
  MF2 foundation branch; any implementation should happen in the Mojito repo
  after backend message-format metadata and dependency decisions are explicit.
- The target Mojito surface is the real frontend workbench at
  `webapp/frontend/src/page/workbench`.
- The current workbench edits translations through a virtualized row table,
  `AutoTextarea`, and `useWorkbenchEdits`; saves already flow through the typed
  `/api/textunits` frontend API.
- The first port should not replace every text input. It should add an
  MF2-aware translation editor for rows whose source is explicitly MF2, while
  preserving the current text-area path for plain strings and ICU.

Current Mojito Shape

Relevant files inspected:

- `webapp/frontend/src/page/workbench/WorkbenchBody.tsx` renders the virtualized
  rows and owns the translation cell UI.
- `webapp/frontend/src/page/workbench/useWorkbenchEdits.ts` owns the current
  editing row, draft target, save validation dialog, optimistic update, and
  row refs.
- `webapp/frontend/src/page/workbench/workbench-types.ts` defines
  `WorkbenchRow` with source, target, locale, status, ids, and plural metadata.
- `webapp/frontend/src/api/text-units.ts` exposes source/target fields and
  plural form metadata, but no explicit message-format field yet.
- `webapp/frontend/package.json` is React 18, TypeScript 5.6, Vite 8, with no
  CodeMirror or ProseMirror dependencies.

Porting Principles

- Keep the editor host contract plain: `source`, `target`, `locale`,
  `documentKey`, `readOnly`, diagnostics, and target-change callbacks.
- Keep CodeMirror as an optional raw-mode adapter while the native raw textarea
  is evaluated. Do not leak CodeMirror types into the shared editor props.
- Do not add ProseMirror as a Mojito dependency until that dependency decision
  is explicit. The production-shaped prototype now has a ProseMirror-backed
  rich adapter, but the Mojito port can still start behind a demo/admin surface
  or remain parked while the dependency and bundle-size tradeoff is reviewed.
- Do not infer MF2 broadly from braces. Until Mojito has backend format
  metadata, gate the workbench editor behind a strict detection rule or feature
  flag that only accepts clear MF2 source forms such as `.input`, `.local`, or
  `.match`.
- Preserve existing workbench save semantics. The first integration should
  only change how the draft target is edited and validated before existing
  `saveTextUnit` calls.

Recommended Dependency Plan

1. Promote the MF2 JavaScript core and React editor into package boundaries
   with React as a peer dependency and no bundled React runtime.
2. Align the editor package with Mojito's React 18 and TypeScript 5.6 before
   importing it into `webapp/frontend`.
3. Port the shared model, diagnostics, keyboard, and rich-editor helpers first.
4. Add raw mode through an adapter boundary:
   - `native` adapter for no-library textarea raw editing
   - optional `codemirror` adapter only if raw editing fidelity requires it
5. If CodeMirror is kept for v1, lazy-load the raw adapter so plain workbench
   rows and rich MF2 rows do not pay the raw editor cost up front.

Workbench Integration Plan

1. Add a small `isMf2TextUnit(row)` helper near the workbench types or MF2
   adapter. In v1 it should require strict syntax signals or a temporary
   feature flag; later it should read backend message-format metadata.
2. Add `WorkbenchMf2TranslationEditor` next to the workbench page code. It
   should bridge `WorkbenchRow` plus the existing edit state to
   `Mf2TranslationEditor`:
   - `source={row.source ?? ""}`
   - `target={editingValue}`
   - `locale={row.locale}`
   - `documentKey={row.id}`
   - `onTargetChange={onChangeEditingValue}`
   - `readOnly={!isEditing || !row.canEdit}`
3. In `WorkbenchBody`, replace `AutoTextarea` only for editing MF2 rows. Keep
   the existing `AutoTextarea` for non-MF2 rows and for MF2 rows that fail the
   strict gate.
4. Continue rendering compact source text in the source column. The MF2 editor
   can show active-source comparison inside the translation cell only while the
   row is editing.
5. Wire editor diagnostics into the existing save affordance by disabling or
   warning before `Accept` when parser errors are present. Do not bypass the
   existing integrity-check dialog; treat MF2 diagnostics as an earlier local
   check.
6. Re-measure the virtualized row when the editor opens, mode changes, helper
   rows appear, or diagnostics change. This is required because the MF2 editor
   is taller than the current `AutoTextarea`.
7. Port the same editor into `TextUnitDetailPageView` after workbench rows work.
   The detail page has a simpler non-virtual layout and can reuse the same
   adapter.

Testing Plan

- Convert the prototype model and component smoke tests into Vitest tests under
  `webapp/frontend`.
- Add a workbench render test that verifies non-MF2 rows keep `AutoTextarea`,
  MF2 rows use the editor, `onChangeEditingValue` receives target updates, and
  parser-error diagnostics block local accept.
- Add a virtualization-focused test or targeted manual check for row height
  changes after switching rich/raw modes.
- Run the standard Mojito frontend checks:
  `source webapp/use_local_npm.sh`, `npm --prefix webapp/frontend run format`,
  `npm --prefix webapp/frontend run lint:fix`,
  `npm --prefix webapp/frontend run tsc`, and
  `npm --prefix webapp/frontend run test`.
- Use browser verification on `/workbench` with one plain row and one MF2 row.

Open Risks

- Mojito currently does not expose a first-class text-unit message format. A
  backend/API field is the clean fix; strict frontend detection is only a
  temporary guard.
- The prototype uses a local `@mojito-mf2/core` package and newer React/TS dev
  versions. Those must be aligned before the port, not worked around inside the
  Mojito app.
- CodeMirror is not currently a Mojito dependency. Shipping it is acceptable
  only if native raw editing falls short on undo, selection, syntax fidelity,
  bidi, or accessibility after focused testing.
- Rich editing must remain IME-safe and paste-safe in the real workbench, where
  rows are virtualized and can unmount.
- Local MF2 diagnostics must complement, not replace, Mojito's existing
  integrity checks and server-side save behavior.
