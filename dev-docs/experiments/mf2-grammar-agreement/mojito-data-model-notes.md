# Mojito Data Model Notes For Grammar Forms

This note sketches how Mojito could store explicit grammar form data without
forcing the core text-unit model to become language-specific.

See [mojito-implementation-plan.md](mojito-implementation-plan.md) for staged
DDL/API/service rollout details.

## Current Conceptual Gap

Classic localization stores:

```text
text unit -> localized string
```

Grammar-aware MF2 needs:

```text
message pattern
+ runtime arguments
+ reusable localized terms/entities
+ term-level MF2 form patterns
+ optional morphology metadata
+ locale adapter
= formatted output
```

The new data model should represent explicit forms and grammar metadata as
structured localization data, not as comments or ad hoc JSON hidden in strings.

## Proposed Entities

### Grammar Term

A reusable localized term/entity.

```text
grammar_term
  id
  repository_id
  term_key              // item.sword, person.role.admin, product.plan.pro
  source_text
  type                  // noun, adjective, person, verb, classifier, custom
  created_at
  updated_at
```

Term keys should be stable product identifiers, not localized text:

```text
item.sword
product.plan.enterprise
role.workspace.owner
unit.pound
```

This is how Mojito avoids word-level ambiguity. The French text `livre` is not
the entity; `item.book` or `unit.pound` is.

### Grammar Term Locale

Locale-specific display text, explicit form patterns, and optional morphology.

```text
grammar_term_locale
  id
  grammar_term_id
  locale_id
  text                  // épée
  forms_json            // MF2 term form patterns, e.g. forms.default
  morphology_json       // structured open-ended metadata
  compact_flags         // optional integer cache for hot metadata/index fields
  compiled_forms_blob   // optional generated compact matcher
  source                // tms, import, llm, dictionary, user
  confidence
  review_status         // inferred, confirmed, rejected, needs_review
  created_at
  updated_at
```

Example `forms_json`:

```json
{
  "default": ".input {$usage :string}\n.input {$number :string}\n.input {$count :number}\n.match $usage $number $count\nbare singular * {{épée}}\ndefinite singular * {{l'épée}}\ncount * other {{{$count} épées}}\n* * * {{épée}}"
}
```

Example `morphology_json`:

```json
{
  "partOfSpeech": "noun",
  "gender": "feminine",
  "number": "singular"
}
```

### Message Grammar Requirements

Derived and cached requirements for a text unit/MF2 pattern.

```text
text_unit_grammar_requirement
  id
  text_unit_id
  locale_id nullable
  argument_name         // item
  argument_type         // term, person, number, raw
  required_features_json
  fallback_policy
  derived_from_pattern_hash
  validation_status
```

This table can be rebuilt from MF2 patterns, so it should be treated as cache or
analysis output rather than the source of truth.

### Grammar Metadata Event

Optional audit/provenance table:

```text
grammar_metadata_event
  id
  grammar_term_locale_id
  event_type             // inferred, edited, reviewed, imported, rejected
  source                 // user, llm, dictionary, suffix_model, import
  previous_value_json
  new_value_json
  confidence
  actor_id nullable
  created_at
```

This is useful if LLM/dictionary suggestions are involved and we need to
distinguish "machine guessed" from "localizer confirmed".

### Grammar Pack Export

Generated resource bundle artifact.

```text
grammar_pack
  id
  repository_id
  locale_id
  format                // canonical_json, compact_json, binary
  profile               // fr-v1, de-v1
  content_hash
  generated_at
  blob_ref
```

## Storage Strategy

Use JSON for flexible form patterns and morphology first:

```text
grammar_term_locale.forms_json
grammar_term_locale.morphology_json
```

Then add compact caches only where useful:

```text
compact_flags
```

Rationale:

- language-specific form dimensions evolve quickly;
- JSON lets new locale adapters add fields without migrations;
- generated packs can still be binary/compact;
- Mojito can validate known fields while preserving unknown extension fields.

## TMS UI

For each localized term, show locale-profile fields:

```text
French noun:
  text
  forms.default as structured usage/number/count rows
  optional gender
  optional number
  review status
```

For German:

```text
gender
forms.default as structured usage/case/number rows
```

For Russian:

```text
gender
animacy
forms.default as structured case/count/animacy rows
declension class
```

The UI should be generated from locale capability profiles, not hardcoded per
table column. Translators should usually edit table-like rows; Mojito generates
the MF2 matcher text.

### Review States

Recommended states:

| State | Meaning |
| --- | --- |
| `missing` | Required field is absent. |
| `inferred` | Machine-filled, not reviewed. |
| `confirmed` | Human/project-confirmed. |
| `rejected` | Suggestion was rejected. |
| `not_applicable` | Field does not apply to this term/profile. |
| `ambiguous` | Term needs sense/context split. |

The exporter can enforce:

```text
release builds require confirmed or not_applicable for required forms and
required metadata
```

while development builds allow inferred/best-effort metadata.

## Import/Export

### Import

- Extract MF2 messages and parse grammar functions.
- Extract term references such as `term:item.sword`.
- Create missing grammar terms.
- Prefill morphology with available guessers/dictionaries/LLMs.
- Mark low-confidence entries as `needs_review`.

Import should also create validation diagnostics when a message uses a raw
string where the grammar function expects a term:

```text
{$item :xg:term article=definite}
```

requires the application to pass `TermRef("item...")`, not arbitrary text.

### Export

- Export normal translated messages.
- Export grammar terms as:
  - canonical nested JSON;
  - compact JSON;
  - platform key/value entries;
  - binary grammar pack.
- Include provenance and review status in authoring exports.
- Strip provenance from compact runtime exports unless requested.

## Validation

Validation should run at three levels:

1. **Pattern validation**
   - MF2 syntax;
   - grammar function names/options;
   - argument references.

2. **Term metadata validation**
   - required fields for locale profile;
   - valid enum values;
   - missing plural/case forms.

3. **Message-to-term validation**
   - every message argument that uses `:term` receives a term;
   - every term used by a message satisfies inferred required features;
   - fallback behavior is explicit.

4. **Bundle validation**
   - generated resource bundle contains every referenced message/term/person;
   - compact flags decode to the same metadata as canonical JSON;
   - binary pack round-trips against fixture examples.

## Migration Path

1. Add experimental `morphology_json` to glossary/term-like data or a parallel
   grammar-term table.
2. Add locale capability profiles (`fr-v1` first).
3. Add MF2 grammar-function analyzer that derives message requirements.
4. Add TMS UI for term metadata and review state.
5. Add canonical JSON grammar bundle export.
6. Add runtime formatter integration against the resource bundle API.
7. Add compact JSON export for platform KV stores.
8. Add binary pack export after runtime API stabilizes.
9. Add import/lint checks to block missing required metadata in release mode.

## API Sketch

Backend services:

```java
interface GrammarTermService {
  GrammarTerm getOrCreateTerm(long repositoryId, String termKey);
  GrammarTermLocale updateLocaleMetadata(
      String termKey,
      Locale locale,
      String text,
      JsonNode morphology,
      ReviewStatus reviewStatus);
}

interface GrammarValidationService {
  List<GrammarDiagnostic> validateTextUnit(long textUnitId, Locale locale);
  List<GrammarDiagnostic> validateRepository(long repositoryId, Locale locale);
}

interface GrammarPackExportService {
  GrammarPack export(long repositoryId, Locale locale, GrammarPackFormat format);
}
```

REST/resource shape:

```json
{
  "termKey": "item.sword",
  "locale": "fr",
  "text": "épée",
  "morphology": {
    "gender": "feminine",
    "number": "singular",
    "phonology": {
      "elides": true
    }
  },
  "reviewStatus": "confirmed"
}
```

## Relationship To Existing Mojito Concepts

Glossary terms and grammar terms overlap but are not identical:

- glossary terms are translation guidance and terminology control;
- grammar terms are runtime entities with morphology and stable IDs.

Possible implementation choices:

1. Extend glossary terms with runtime metadata.
2. Create grammar terms as first-class entities and optionally link to glossary.
3. Start with separate experimental grammar terms, then unify if the model
   proves useful.

The separate experimental entity is safer initially because runtime grammar
needs stable term keys and structured per-locale morphology, while glossary
entries may be looser.

## Open Decisions

- Whether grammar terms should extend Mojito glossary terms or be a separate
  first-class entity.
- Whether term IDs are globally scoped, repository scoped, or branch/scm scoped.
- How term metadata should interact with existing text-unit comments and
  screenshots.
- Whether LLM/dictionary suggestions should be stored as provenance events or
  only as current metadata fields.
- Whether compact flags should live in the DB or be generated only at export
  time.
