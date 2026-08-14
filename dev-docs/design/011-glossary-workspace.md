# Glossary Workspace

## Purpose

Mojito now has a first-class glossary system for terminology management across
translation, review, and AI-assisted workflows. A glossary is the user-facing
terminology object; its source and localized term strings are stored on normal
Mojito TM primitives through a managed backing repository and one canonical
virtual asset.

This document is the current design note for that system. It replaces the older
foundation, workspace, and readiness snapshots that were written while the
feature shape was still moving.

## Current Shape

### Product surfaces

- `/glossaries` is the shared glossary directory.
- `/glossaries/:glossaryId` is the daily term workspace.
- `/settings/system/glossaries` remains the admin lifecycle surface.
- `/glossaries/:glossaryId/settings` owns name, description, enabled state,
  locales, and applicability.

The workspace owns term operations. Settings should not duplicate term curation.

### Workspace layout

The glossary workspace is a two-pane surface:

- left pane: searchable, selectable term table and extraction queue
- right pane: selected term detail editor
- split handle: resize, collapse detail, and restore table focus
- mobile/narrow layout: stacked instead of resizable

The term table supports:

- text search that defaults to source terms, with a field selector for source,
  target, definition, references, or all
- status filtering
- multi-locale columns
- an automatic visible-locale cap of five columns
- manual column-limit override for larger locale selections
- result limit controls
- import/export actions in the secondary bar
- compact row metadata under the source term
- row-level Workbench deep links scoped to the clicked term

Source and translation column sizing changes with the visible locale count so a
single-locale table does not waste the translation area.

### Import and export

Glossary exchange is JSON-only.

- `GET /api/glossaries/{id}/export?format=json`
- `POST /api/glossaries/{id}/import`

The UI exposes import/export from the glossary workspace, not settings. JSON is
the canonical round-trip format because it can preserve one term with multiple
translations without flattening data into one row per locale.

The exported shape is term-centric:

```json
{
  "glossary": {
    "id": 4,
    "name": "g4",
    "description": null,
    "enabled": true,
    "priority": 0,
    "scopeMode": "GLOBAL"
  },
  "terms": [
    {
      "termKey": "github",
      "source": "GitHub",
      "definition": null,
      "partOfSpeech": null,
      "termType": "BRAND",
      "enforcement": "SOFT",
      "status": "CANDIDATE",
      "provenance": "MANUAL",
      "caseSensitive": false,
      "doNotTranslate": true,
      "translations": [
        {
          "localeTag": "fr-FR",
          "target": "GitHub",
          "targetComment": null
        }
      ]
    }
  ]
}
```

Import still accepts the older `entries` array shape for compatibility, but new
exports should use `terms`.

CSV was intentionally removed from this slice. If spreadsheet editing becomes
important later, it should be added as an explicit flattened utility format, not
as the primary interchange contract.

## Data Model

### Glossary identity

`Glossary` stores:

- name, description, enabled state, priority, source locale
- scope mode: global or selected repositories
- included and excluded consuming repositories
- managed backing repository
- canonical glossary asset path

The backing repository is visible and inspectable as a normal Mojito repository,
but glossary UX stays primary.

### Term storage

Term strings live in the backing repository:

- source term: source `TMTextUnit`
- localized term: current `TMTextUnitVariant`
- canonical asset: one glossary virtual asset per glossary

Structured term metadata lives outside TM text-unit comments:

- definition
- part of speech
- term type
- enforcement
- status
- provenance
- case-sensitive flag
- do-not-translate flag

This keeps glossary strings compatible with existing TM storage while avoiding a
second translation store.

### References

The backend model is still named `GlossaryTermEvidence`, but the UI should prefer
"References" where the user-facing label is not constrained by API names.

Supported reference types:

- screenshot
- string usage
- code reference
- note

These records explain why a term exists or where it was observed. They are not
part of the translation-storage model.

The term editor presents these as a compact `References` section. Users can add
notes, observed usage, code references, or attach screenshots without exposing
raw backend enum names or a prominent screenshot dropzone.

## Term Lifecycle

### Status

Terms use explicit status values:

- `CANDIDATE`
- `APPROVED`
- `DEPRECATED`
- `REJECTED`

New manual terms start as `CANDIDATE`. Candidate terms can be reviewed in the
workspace and then approved, rejected, deprecated, or edited.

### Roles

- Admin: lifecycle settings, direct edits, extraction, import/export
- PM: direct term edits, extraction, candidate review
- Translator/linguist: read glossary workspace and propose candidate terms with
  translations for locales they can edit

Locale edit checks reuse existing Mojito locale permissions.

## Extraction

Candidate extraction has two layers:

- deterministic recall scans selected repositories, groups normalized source
  candidates, counts occurrences, and collects examples
- AI refinement filters generic noise and suggests term metadata when the review
  AI client is configured

If AI is unavailable, extraction still returns deterministic candidates.

Extraction results are review input, not auto-approved glossary content.

## Matching And Review Integration

Glossary matching is shared by MT and review surfaces.

Current matching is lexical:

- exact / case-sensitive behavior when requested
- case-insensitive matching
- matched spans and matched text are carried in the result

The review/workbench match endpoint resolves the enabled glossaries for each
repository, then reuses a process-local compiled matcher keyed by the ordered
effective glossary ids, their existing last-modified dates, and the target locale.
Repositories linked to the same glossaries and explicit lookups of one managed
glossary therefore share the same trie. Legacy repository-name lookup keeps a
separate repository+locale cache scope. The Caffeine cache holds at most 128
entries, defaults to a ten-minute lifetime, and coalesces concurrent loads. The
lifetime can be configured with `l10n.glossary.cache.ttl` or the
`L10N_GLOSSARY_CACHE_TTL` environment variable.

Glossary configuration edits already update the glossary's last-modified date,
which the existing glossary-resolution query observes on every pod. Creating,
deleting, or changing glossary scope naturally changes the resolved glossary-id
list. Term, translation, and evidence changes are reconciled independently for
each glossary and target locale within the configured cache lifetime; changes
that do not modify the glossary itself may take up to the TTL to appear on
another pod. There is no glossary revision column, additional database write,
shared-row lock, global eviction, transaction callback, pub/sub dependency, or
AspectJ-dependent cache invalidation. The existing last-modified date has
one-second precision, so the bounded TTL remains the correctness backstop for
rapid configuration edits.

Managed-glossary cold loads hydrate source and target text-unit DTOs from the
existing `TEXT_UNIT_DTOS_CACHE` JSON/blob snapshots and refresh them with
`UpdateType.ALWAYS`, which incrementally applies the existing asset-version and
locale-specific `(last_modified_date, current_variant_id)` translation-watermark
checks before rebuilding the in-memory trie. Root-locale matching reuses its
already loaded source snapshot rather than fetching the same snapshot twice.
Glossary approval metadata and evidence remain authoritative MySQL reads. Legacy
repository-name lookups or missing locale metadata keep the existing
direct-search fallback.
`GlossaryWS.matchDuration` captures request timing, while
`GlossaryService.cache.lookup` and `GlossaryService.cache.loadDuration` measure
bounded-scope cache reuse and cold-load duration. Legacy AI translation batches
skip glossary loading entirely when the locale has no strings to translate.

Dedicated serialized trie snapshots are intentionally not used: the existing
JSON/blob DTO cache already supports cross-pod hydration and database delta
refresh without introducing a second serialization or invalidation contract.

Review project detail and text-unit detail can show matched glossary terms and
include glossary context in AI review requests.

### Database Load And Latency Impact

Previously, every review/workbench glossary-match request and every AI
translation repository/locale run searched MySQL for the complete glossary
source and target text units, loaded approval metadata and evidence, and rebuilt
the same trie. Reviewing 500 strings against one glossary and locale could
therefore trigger roughly 500 complete glossary rebuilds. Multiple reviewers
multiplied the same database work, and interactive AI review waited for glossary
matching before submitting its model request.

Now those 500 requests normally share one compiled trie per pod, glossary scope,
and locale within the cache lifetime: roughly 499 of 500 complete rebuilds are
avoided, or 99.8% fewer full glossary rebuilds in that example. Concurrent
requests for the same scope share a single load. Translating 65 locales does not
update a shared glossary row or serialize the locale transactions; each locale
refreshes independently through its existing DTO-cache watermark. Increasing the
default TTL from 30 seconds to ten minutes cuts time-based rebuilds by up to 20
times per active pod and locale. Managed-glossary cold loads reuse shared
JSON/blob text-unit snapshots instead of directly executing the former full
source and target text-unit searches.

If stricter cross-pod freshness is later needed, a best-effort pub/sub
notification can invalidate only the affected glossary+locale after a committed
write. The existing TTL and DTO-cache watermark must still reconcile missed
notifications; pub/sub is a latency optimization, not the source of truth.

This does not eliminate all database traffic: each request still resolves enabled
glossary/repository links, and a cold load still checks snapshot freshness and
loads authoritative approval metadata and evidence. The savings are the repeated
wide glossary scans, metadata/evidence hydration, and trie construction that
previously scaled with reviewed-string count and concurrent reviewers.

Future semantic matching should retrieve candidates and rerank them. It should
not silently enforce terminology without an approved glossary term.

## Workbench Relationship

Workbench remains the low-level repository/TM inspector. Glossary is the primary
terminology UX.

The glossary table links a clicked term into Workbench with:

- `tmTextUnitId`
- backing repository id
- selected locale tags

This keeps inspection precise without making users manage glossary content as raw
TM rows.

Workbench also detects rows from glossary backing repositories and shows a
compact glossary-term affordance that opens the clicked row back in the glossary
workspace.

When selected Workbench repositories include a glossary backing repository, the
standard Workbench filter includes a glossary status facet. It defaults to
approved terms, can be changed to all glossary statuses, and filters by
`GlossaryTermMetadata.status`; the all state does not require joining glossary
metadata.

## Follow-Ups

- Add readiness/audit signals: missing locale coverage, pending candidates,
  recent changes, and review state.
- Add configurable extraction noise controls and confidence thresholds.
- Cluster duplicate candidate proposals.
- Add semantic retrieval/reranking behind the existing match contract.
- Decide whether a flattened CSV utility export is worth supporting later.
