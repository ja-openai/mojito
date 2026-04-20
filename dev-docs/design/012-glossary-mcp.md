# Glossary MCP

Purpose

- Give MCP clients a controlled surface for glossary operations.
- Let clients inspect product code, massage bootstrap spreadsheets, and submit normalized glossary term changes without bypassing Mojito permissions or storage rules.
- Keep Mojito responsible for canonical term storage, translation imports, and text-unit links.

Existing model support

- Glossary terms are backed by Mojito text units in the glossary backing repository.
- `glossary_term_metadata` links one metadata row to one canonical glossary term through `tm_text_unit_id`.
- `glossary_term_evidence` can link supporting references to existing product text units through its own `tm_text_unit_id`.
- This means a glossary term itself should be stored as a canonical glossary text unit, while product strings that justify the term should be attached as supporting references.

Initial MCP tools

- `glossary.list`
  - Read-only.
  - Lists glossaries visible to the current user.
  - Use before term operations when only the human glossary name is known.
- `glossary.term.search`
  - Read-only.
  - Searches terms in one glossary by `glossaryId` or exact `glossaryName`.
  - Can request specific locale columns with `localeTags`.
  - Returns metadata, translations, and supporting references.
- `glossary.term.bulk_upsert`
  - Mutating, dry-run by default.
  - Accepts normalized JSON terms, not arbitrary files.
  - Supports source metadata, translations, and supporting references.
  - Supporting references can link existing product TUs by evidence `tmTextUnitId`.

Bootstrap workflow

1. An MCP client reads Excel/CSV locally and normalizes it into JSON terms.
2. The client uses `glossary.list` and `glossary.term.search` to find the destination glossary and detect duplicates.
3. The client calls `glossary.term.bulk_upsert` with `dryRun: true`.
4. A human or higher-level agent reviews the plan.
5. The client repeats the same call with `dryRun: false`.

Why not parse Excel in Mojito MCP

- MCP clients can use local file tooling for CSV/XLSX cleanup and schema inference.
- Keeping MCP input as normalized JSON avoids server-side spreadsheet parsing dependencies and ambiguous column mapping.
- The JSON payload is also the reusable contract for codebase mining, spreadsheet imports, and future AI-assisted review.

Future tools

- `glossary.term.suggest_from_text_units`: server-side candidate extraction for selected repositories, returning reviewable proposals only.
- `glossary.term.link_references`: attach additional product TU references to existing glossary terms without replacing all term metadata.
- `glossary.term.review_plan`: compare proposed terms against existing glossary entries and return duplicate/merge recommendations.
- `glossary.translation.propose`: submit locale-specific translation proposals when the client should not directly write approved translations.
