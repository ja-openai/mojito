# Workbench Import / Export Port

Context

- The legacy workbench exposes `Export` and `Import` actions directly in the main workbench toolbar.
- Export lets users dump the current search result set to CSV or JSON with selectable fields.
- Import lets users upload CSV or JSON and batch-apply translations through `/api/textunitsBatch`.
- The new frontend workbench already has the core search, workset, bulk-edit, share, and collection surfaces, but it does not yet expose import/export.
- The backend endpoint and legacy client flow already exist, so this is primarily a product and frontend integration decision rather than a net-new backend invention.

Goals

- Port the legacy workbench import/export capability into the new frontend workbench.
- Keep the first iteration close to legacy behavior so users do not lose a known workflow.
- Make the feature feel native to the new workbench instead of a legacy modal transplanted unchanged.
- Reduce accidental blast radius for bulk import, especially while the new frontend surface is still settling.

Non-Goals

- Re-designing the import file format in v1.
- Replacing the existing batch import backend in v1.
- Adding server-side export jobs in v1.
- Expanding import/export into a full translation-kit workflow.

Legacy Behavior Summary

Export in legacy workbench:

- starts from the current search parameters
- fetches all pages up to a user-specified limit
- supports CSV and JSON
- supports field selection
- can split exported output by locale
- performs export fully client-side after reading search results

Import in legacy workbench:

- accepts CSV or JSON
- validates required fields on the client
- normalizes rows into `TextUnitDTO`-like payloads
- posts the batch to `/api/textunitsBatch`
- waits for the pollable task to finish

Relevant existing files:

- legacy export modal: `webapp/src/main/resources/public/js/components/workbench/ExportSearchResultsModal.js`
- legacy import modal: `webapp/src/main/resources/public/js/components/workbench/ImportSearchResultsModal.js`
- batch import endpoint: `webapp/src/main/java/com/box/l10n/mojito/rest/textunit/TextUnitWS.java`
- batch import payload: `webapp/src/main/java/com/box/l10n/mojito/rest/textunit/ImportTextUnitsBatch.java`
- new workbench actions area: `webapp/new-frontend/src/page/workbench/WorkbenchWorksetBar.tsx`

Product Recommendation

- Port both export and import together under one "Import / Export" workbench effort.
- Keep export close to legacy behavior in v1.
- Keep import close to legacy behavior in v1, but gate it more tightly than legacy.

Access Recommendation

Initial recommendation:

- export: keep available to authenticated users who already have workbench access
- import: restrict to admins for v1

Rationale:

- export is read-only and low risk
- import performs bulk writes and is easier to misuse
- the current backend broadly allows translators, PMs, and admins via `/api/textunits/**`, but the new frontend does not need to inherit that exposure immediately
- we can widen import access later once usage patterns and guardrails are validated

If product prefers simpler consistency, both actions can be shown only to admins in v1. That is not required technically, but it is an acceptable product tradeoff.

UI Placement

Recommendation:

- place export and import actions in the new workbench workset bar, grouped with `Share` at the end of the bar

Why:

- these are search-result level actions, not single-row actions
- import, export, and share are all secondary result-set actions and should stay out of the main search/edit controls
- adding the actions there avoids overloading the already dense filter header

Suggested v1 controls:

- `Export`
- `Import`

These should open dedicated modal dialogs rather than navigate away from the workbench.

V1 Export Scope

- export the active search result set, not collections-only unless the active search itself is collection-backed
- support CSV and JSON
- support the existing legacy field set
- support result limit input
- support locale-split export
- generate files client-side

Implementation notes:

- reuse the new typed search API in `webapp/new-frontend/src/api/text-units.ts`
- add a dedicated export helper that pages through `searchTextUnits(...)` until the requested limit is reached
- keep the default field set aligned with legacy unless product wants to simplify it
- preserve stable column names so legacy import templates and downstream consumers do not break

V1 Import Scope

- support CSV and JSON upload
- preserve the current legacy-required fields:
  - `repositoryName`
  - `assetPath`
  - `targetLocale`
  - `target`
  - one identifier: `tmTextUnitId` or `name`
- preserve support for optional fields already accepted by the legacy importer
- perform client-side validation before upload
- submit to `/api/textunitsBatch`
- wait for the pollable task and show success/failure state in the modal

Import UX recommendation:

- provide drag-and-drop plus file picker
- provide downloadable CSV template
- show parsed row count and skipped/invalid row count before submit
- show validation errors inline before upload
- keep the modal open on server error

Backend Recommendation

V1 should reuse the existing backend endpoint and payload shape.

Minimal backend additions that may still be worthwhile:

- add explicit role gating for `/api/textunitsBatch` if we decide import is admin-only in the new frontend and want backend enforcement, not just hidden UI
- document the accepted import fields and semantics more clearly in code or API comments

Typed frontend API additions:

- `exportSearchTextUnits(...)` helper built on top of `searchTextUnits(...)`
- `importTextUnitsBatch(...)` helper that posts `ImportTextUnitsBatch`
- pollable task handling reused from existing new-frontend polling patterns or extracted into a small shared helper

Behavioral Notes / Risks

- Client-side export is simple but can be slow or memory-heavy for very large result sets.
- The legacy export default limit is `10000`; keeping that default in v1 is reasonable.
- Import currently rides on legacy `TextUnitDTO` semantics and accepts more fields than a minimal translator-friendly format would require.
- Import can overwrite many translations quickly, so access control and clear pre-submit feedback matter more than visual polish.

Future Follow-on Work

- Add server-side export jobs for very large result sets.
- Offer an import dry-run mode with server-side validation only.
- Provide richer import result summaries: updated count, rejected count, integrity-check failures.
- Consider allowing import/export against saved collections directly as a separate flow.
- Revisit whether import access should widen from admin-only to PMs or locale-scoped translators.

Implementation Plan

1. Add typed new-frontend import/export API helpers.
2. Add export modal and import modal components in the new workbench area.
3. Add workset bar actions and role-based visibility.
4. Reuse the existing backend endpoint for import.
5. Decide whether `/api/textunitsBatch` needs stricter backend authorization in the same change.

Open Questions

- Should export be available to all workbench users or admins only for consistency with import?
- Should import stay on the legacy broad `/api/textunits/**` permission model, or should `/api/textunitsBatch` be tightened specifically?
- Do we want to port the full legacy export field matrix, or trim it to the fields that users actually need?
