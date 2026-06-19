# Mojito Implementation Plan

This plan turns the grammar-agreement proposal into staged Mojito work. It is
intended to be incremental: Mojito can store explicit term-level MF2 form
patterns first, then use morphology metadata for editor support, validation,
prefill, indexing, and fallback behavior.

## Stage 0: Feature Flag And Profiles

Add feature flag:

```text
grammarAgreement.enabled
```

Add locale grammar profiles as static JSON resources first:

```text
fr-v1
de-v1
ru-v1
ar-v1
sw-v1
ja-v1
ko-v1
cy-v1
```

Profile fields:

```json
{
  "id": "fr-v1",
  "locale": "fr",
  "features": ["usage", "gender", "number", "article", "plural"],
  "required": {
    "term.article=definite": ["forms.default"]
  },
  "compactFlags": {
    "gender": { "bits": [0, 1] },
    "number": { "bits": [2, 3] },
    "hasExplicitForms": { "bit": 4 }
  }
}
```

## Stage 1: Data Model

Add first-class grammar terms rather than overloading text units.

### `grammar_term`

```sql
CREATE TABLE grammar_term (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  repository_id BIGINT NOT NULL,
  term_key VARCHAR(512) NOT NULL,
  source_text VARCHAR(2048),
  term_type VARCHAR(64) NOT NULL,
  created_date DATETIME NOT NULL,
  last_modified_date DATETIME NOT NULL,
  UNIQUE KEY UK_grammar_term_repo_key (repository_id, term_key)
);
```

### `grammar_term_locale`

```sql
CREATE TABLE grammar_term_locale (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  grammar_term_id BIGINT NOT NULL,
  locale_id BIGINT NOT NULL,
  text VARCHAR(2048) NOT NULL,
  forms_json JSON,
  morphology_json JSON NOT NULL,
  compact_flags BIGINT,
  compiled_forms_blob_ref VARCHAR(512),
  source VARCHAR(64) NOT NULL,
  confidence DOUBLE,
  review_status VARCHAR(64) NOT NULL,
  created_date DATETIME NOT NULL,
  last_modified_date DATETIME NOT NULL,
  UNIQUE KEY UK_grammar_term_locale (grammar_term_id, locale_id)
);
```

### `grammar_metadata_event`

```sql
CREATE TABLE grammar_metadata_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  grammar_term_locale_id BIGINT NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  source VARCHAR(64) NOT NULL,
  previous_value_json JSON,
  new_value_json JSON,
  confidence DOUBLE,
  actor_id BIGINT,
  created_date DATETIME NOT NULL
);
```

### `text_unit_grammar_requirement`

Derived cache from MF2 patterns:

```sql
CREATE TABLE text_unit_grammar_requirement (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  text_unit_id BIGINT NOT NULL,
  locale_id BIGINT,
  argument_name VARCHAR(255) NOT NULL,
  argument_type VARCHAR(64) NOT NULL,
  required_features_json JSON NOT NULL,
  fallback_policy VARCHAR(64),
  pattern_hash VARCHAR(128) NOT NULL,
  validation_status VARCHAR(64) NOT NULL,
  created_date DATETIME NOT NULL,
  last_modified_date DATETIME NOT NULL
);
```

## Stage 2: Backend Services

### `GrammarTermService`

Responsibilities:

- create/update grammar terms;
- resolve term by `(repository, termKey, locale)`;
- update `forms_json`, morphology JSON, and review status;
- write metadata events.

### `GrammarProfileService`

Responsibilities:

- return locale profile;
- validate term form selector names/values and morphology field names/values;
- encode/decode compact flags.

### `GrammarPatternAnalyzer`

Responsibilities:

- parse MF2 grammar functions;
- derive argument requirements and term usage contexts;
- parse term-level `forms.default` patterns;
- cache `text_unit_grammar_requirement`;
- report unsupported functions/options.

### `GrammarValidationService`

Responsibilities:

- validate one text unit;
- validate repository/locale;
- emit diagnostics compatible with JS/Rust runners:

```json
{
  "code": "missing-term-form",
  "severity": "error",
  "textUnitId": 123,
  "argument": "item",
  "termKey": "item.sword",
  "feature": "forms.default"
}
```

### `GrammarPackExportService`

Responsibilities:

- export canonical JSON bundle;
- export compact JSON bundle;
- precompile term form matchers for compact exports;
- eventually export binary `.gagp`;
- include only confirmed explicit forms/metadata for release mode unless
  configured otherwise.

## Stage 3: REST API

### Terms

```http
GET /api/grammar/terms?repositoryId={id}&locale=fr
POST /api/grammar/terms
GET /api/grammar/terms/{termKey}?repositoryId={id}&locale=fr
PUT /api/grammar/terms/{termKey}/locales/{locale}
```

Payload:

```json
{
  "termKey": "item.sword",
  "sourceText": "sword",
  "termType": "noun",
  "locale": "fr",
  "text": "épée",
  "forms": {
    "default": ".input {$usage :string}\n.input {$number :string}\n.input {$count :number}\n.match $usage $number $count\nbare singular * {{épée}}\ndefinite singular * {{l'épée}}\ncount * other {{{$count} épées}}\n* * * {{épée}}"
  },
  "morphology": {
    "gender": "feminine",
    "number": "singular"
  },
  "reviewStatus": "confirmed"
}
```

### Validation

```http
POST /api/grammar/validate/text-unit/{id}?locale=fr
POST /api/grammar/validate/repository/{id}?locale=fr
```

### Export

```http
GET /api/grammar/export/{repositoryId}/{locale}?format=canonical-json
GET /api/grammar/export/{repositoryId}/{locale}?format=compact-json
GET /api/grammar/export/{repositoryId}/{locale}?format=binary
```

## Stage 4: Frontend/TMS UI

Pages/components:

- Grammar terms list.
- Term locale editor.
- Term form matcher editor generated from locale profile.
- Morphology field editor generated from locale profile.
- Validation diagnostics panel.
- MF2 message preview with runtime args.

UI behavior:

- show required fields inferred from message patterns;
- show required term usages inferred from message patterns;
- show review state badges;
- mark inferred machine metadata separately from confirmed metadata;
- generate sparse MF2 matchers from structured form controls;
- allow bulk review for high-confidence inferred terms;
- block release export when required fields are missing.

## Stage 5: Import/Export Integration

Import sources:

- existing glossary/term files;
- app-provided term corpus;
- MF2 message files;
- optional suggestion files from LLM/dictionary pipelines.

Export targets:

- canonical grammar JSON;
- compact grammar JSON;
- Android key/value resources;
- JS resource bundle;
- future `.gagp` binary pack.

## Stage 6: Testing

Backend tests:

- profile validation;
- morphology JSON validation;
- MF2 pattern analysis;
- diagnostics;
- canonical/compact export round trips.

Fixture conformance:

```text
Use dev-docs/experiments/mf2-grammar-agreement/fixtures/** as shared test data.
```

Frontend tests:

- term editor profile fields;
- validation diagnostics rendering;
- MF2 preview output.

## Stage 7: Rollout

1. Hidden experimental flag.
2. Read-only import and validation on sample repositories.
3. Editable grammar terms for French pilot.
4. Canonical JSON export.
5. Compact JSON export.
6. Runtime integration with JS formatter.
7. Add Java backend formatter only where server-side preview/export needs it.
8. Add Rust/binary pack when size pressure is real.

## Open Risks

- Grammar terms overlap with glossary terms but have stricter runtime identity.
- Locale profiles may grow too quickly if not versioned.
- Translators may find morphology fields overwhelming without profile-specific UI.
- Runtime apps must pass `TermRef`, not raw translated strings, for high-quality
  formatting.
- Binary format should not be stabilized before JSON/resource API settles.
