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

MCP tools

- `glossary.list`
  - Read-only.
  - Lists glossaries visible to the current user.
  - Use before term operations when only the human glossary name is known.
- `glossary.create_or_update`
  - Mutating.
  - Creates a managed glossary with its backing repository, or updates an existing glossary by id.
  - Configures enabled state, priority, locales, repository scope, and exclusions.
- `glossary.term.search`
  - Read-only.
  - Searches terms in one glossary by `glossaryId` or exact `glossaryName`.
  - Can request specific locale columns with `localeTags`.
  - Returns metadata, creation/update timestamps, translations, and supporting references.
- `glossary.term.bulk_upsert`
  - Mutating, dry-run by default.
  - Accepts normalized JSON terms, not arbitrary files.
  - Supports source metadata, translations, and supporting references.
  - Supporting references can link existing product TUs by evidence `tmTextUnitId`.
- `glossary.term.review_plan`
  - Read-only.
  - Compares proposed terms against existing terms and returns create/update/duplicate guidance.
  - Use before `bulk_upsert` when bootstrapping from files or mined codebase terms.
- `glossary.term.link_references`
  - Mutating.
  - Appends supporting references to an existing term without replacing term metadata or existing references.
  - Use with `text_unit.search` to attach product TU usage evidence.
- `image.upload`
  - Mutating.
  - Uploads a base64 image or image data URL to Mojito image storage.
  - Returns an `imageKey` and `/api/images/...` URL that can be attached as screenshot evidence with `glossary.term.bulk_upsert` or `glossary.term.link_references`.
- `glossary.term.suggest_translations_from_tm`
  - Read-only.
  - Searches product TM for exact source-term matches and returns observed target-term suggestions by locale.
  - Clients should review the suggestions and then write selected targets through `glossary.term.bulk_upsert`.

Bootstrap workflow

1. An MCP client reads Excel/CSV locally and normalizes it into JSON terms.
2. The client uses `glossary.list`, or `glossary.create_or_update` when the destination glossary does not exist.
3. The client uses `glossary.term.search` and `glossary.term.review_plan` to detect duplicates and likely merge work.
4. The client calls `glossary.term.bulk_upsert` with `dryRun: true`.
5. A human or higher-level agent reviews the plan.
6. The client repeats the same call with `dryRun: false`.
7. The client optionally calls `glossary.term.suggest_translations_from_tm` and writes reviewed translations through `bulk_upsert`.

Codebase mining workflow

1. The client uses the raw term index refresh/review flow once available, or uses local code/search tooling to prepare terms.
2. The client uses `text_unit.search` to find concrete Mojito text units for observed usage.
3. The client calls `glossary.term.review_plan` and then `glossary.term.bulk_upsert`.
4. The client calls `glossary.term.link_references` to append product-string references discovered after the term already exists.
5. If the client captured screenshot context, it calls `image.upload` first, then links the returned `imageKey` as `SCREENSHOT` evidence, optionally with `tmTextUnitId` and crop coordinates.

Why not parse Excel in Mojito MCP

- MCP clients can use local file tooling for CSV/XLSX cleanup and schema inference.
- Keeping MCP input as normalized JSON avoids server-side spreadsheet parsing dependencies and ambiguous column mapping.
- The JSON payload is also the reusable contract for codebase mining, spreadsheet imports, and future AI-assisted review.

Current limitations

- `glossary.translation.propose`: submit locale-specific translation proposals when the client should not directly write approved translations.
- The remote endpoint is authenticated with the existing Mojito web security stack. Codex clients need a configured auth header or an authenticated local bridge; no MCP-specific token model exists yet.
- Candidate discovery should move to the glossary-neutral raw term index in `dev-docs/design/013-glossary-raw-term-index.md`; refresh runs are audit/progress, not candidate ownership.
