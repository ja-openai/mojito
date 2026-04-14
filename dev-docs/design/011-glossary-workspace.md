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

- `/n/glossaries` is the shared glossary directory.
- `/n/glossaries/:glossaryId` is the daily term workspace.
- `/n/settings/system/glossaries` remains the admin lifecycle surface.
- `/n/glossaries/:glossaryId/settings` owns name, description, enabled state,
  locales, and applicability.

The workspace owns term operations. Settings should not duplicate term curation.

### Workspace layout

The glossary workspace is a two-pane surface:

- left pane: searchable, selectable term table and extraction queue
- right pane: selected term detail editor
- split handle: resize, collapse detail, and restore table focus
- mobile/narrow layout: stacked instead of resizable

The term table supports:

- text search across source, definition, target, and references/evidence captions
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

### References / evidence

The backend model is still named `GlossaryTermEvidence`, but the UI should prefer
"References" where the user-facing label is not constrained by API names.

Supported evidence/reference types:

- screenshot
- string usage
- code reference
- note

These records explain why a term exists or where it was observed. They are not
part of the translation-storage model.

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

Review project detail and text-unit detail can show matched glossary terms and
include glossary context in AI review requests.

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

## Follow-Ups

- Rename user-facing "Evidence" labels to "References" where appropriate while
  keeping backend/API names stable unless a wider migration is justified.
- Add readiness/audit signals: missing locale coverage, pending candidates,
  recent changes, and review state.
- Add configurable extraction noise controls and confidence thresholds.
- Cluster duplicate candidate proposals.
- Add semantic retrieval/reranking behind the existing match contract.
- Decide whether a flattened CSV utility export is worth supporting later.
- Cache compiled glossary tries for repeated repository/locale match lookups.
