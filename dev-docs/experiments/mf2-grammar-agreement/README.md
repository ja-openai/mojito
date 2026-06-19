# MF2 Grammar Agreement Extension Proposal

This exploration defines a portable grammar-agreement layer for MessageFormat 2
(MF2) runtimes, resource bundles, and TMS tooling.

The current direction is **term-pattern first**:

```text
message MF2 pattern
+ term-level MF2 form pattern
+ optional grammar metadata
+ locale profile
= formatted output
```

The earlier metadata-first idea (`gender`, `phonology.elides`, `forms.plural`
as the primary runtime truth) was useful for discovery but is not strong enough
for real agreement. Rendered term forms should be explicit MF2 patterns when
correctness matters. Metadata remains valuable for editor controls,
validation, search, prefill, fallback heuristics, and compact compilation.

## Canonical Example

Message:

```mf2
Vous avez ramassé {$item :term article=definite}.
Vous avez trouvé {$count :count of=$item}.
```

Term:

```json
{
  "text": "épée",
  "forms": {
    "default": ".input {$usage :string}\n.input {$number :string}\n.input {$count :number}\n.match $usage $number $count\nbare singular * {{épée}}\nbare plural * {{épées}}\ndefinite singular * {{l'épée}}\ndefinite plural * {{les épées}}\nindefinite singular * {{une épée}}\nindefinite plural * {{des épées}}\ncount * one {{une épée}}\ncount * other {{{$count} épées}}\n* * * {{épée}}"
  },
  "morphology": {
    "partOfSpeech": "noun",
    "gender": "feminine",
    "number": "singular"
  }
}
```

The formatter maps message options to term inputs:

```json
{
  "usage": "definite",
  "number": "singular",
  "count": "*"
}
```

Then it evaluates the term pattern. French elision, German case articles,
Russian counted forms, Welsh mutation, and game-specific term variants are all
represented as term forms rather than hardcoded global heuristics.

## Design Rules

- Use MF2 for values that render localized text.
- Use JSON metadata for facts about that text.
- Keep terms separate from messages so one entity can be reused across many
  strings.
- Keep resource access KV-only: storage can be nested JSON, flat JSON, Android
  XML, Java properties, SQLite, remote config, or a binary pack.
- Compile MF2 patterns for production hot paths; do not parse raw strings on
  every format call.
- Locale profiles define allowed selector dimensions, option values, fallback
  policies, and compact encodings.

## Why Term Patterns

Explicit term-level MF2 patterns handle cases that metadata fields do not model
cleanly:

- French: `l'épée`, `une épée`, `3 épées`.
- German: `der Schild`, `den Schild`, `dem Schild`.
- Russian: count and case forms that are not derivable from one plural field.
- Arabic: gender/number/person-sensitive verb or adjective forms.
- Welsh/Irish/Gaelic: mutation triggered by construction.
- Japanese/Korean/Chinese: classifier or politeness-sensitive forms.
- Games: named entities, inventory terms, loot phrases, and domain-specific
  usage forms.

Metadata is still useful, but it is not sufficient as the only runtime source
of truth.

## Runtime Shape

The formatter receives:

```ts
format(messageId, args, context)
```

Arguments may contain `TermRef`, `PersonRef`, scalar values, or already-resolved
runtime values. The resolver sits on top of a dumb KV store:

```ts
type ResourceStore = {
  getString(key: string): string | undefined;
  getJson<T = unknown>(key: string): T | undefined;
  getBytes?(key: string): Uint8Array | undefined;
};
```

Typical keys:

```text
messages/inventory.pickup/value
terms/item.sword/text
terms/item.sword/forms/default
terms/item.sword/morphology/gender
profiles/fr-v1
```

The formatter only sees resolved values:

```ts
type TermValue = {
  id: string;
  text: string;
  forms?: { default?: string | CompiledTermMatcher };
  morphology?: Record<string, unknown>;
};
```

## Storage Strategy

Authoring and TMS interchange should use canonical JSON. Platform exports may
choose a generated shape:

- nested JSON for debugging and authoring;
- flattened KV JSON for dumb stores;
- Android `strings.xml` values containing JSON or generated string keys;
- compact JSON with compiled matcher references;
- binary pack with string pool, term index, and sparse matcher tables.

The compact path should compile term patterns into selector IDs, key IDs, and
string-pool offsets. Source MF2 is best for authoring; compiled matchers are
best for hot rendering.

## TMS Model

The TMS should expose structured term-form editing, not raw platform storage.

Recommended objects:

- **Message**: source/localized MF2 pattern, comments, screenshots.
- **Term**: stable product key, localized text, `forms.default`, metadata,
  provenance, review state.
- **Locale profile**: supported dimensions, default usage axes, validation
  rules, compact encoding.
- **Diagnostics**: missing term forms, invalid selector values, raw string used
  where a term was expected, unsupported options.

The term editor should render table-like controls such as:

```text
usage       number     count     output
bare        singular   *         épée
definite    singular   *         l'épée
count       *          one       une épée
count       *          other     {$count} épées
```

Then Mojito generates the MF2 matcher.

## Current Prototype

Executable artifacts:

- [grammar_bundle_loader.py](grammar_bundle_loader.py): Python fixture runner
  with a small term-level MF2 matcher.
- [js/grammar_fixture_runner.mjs](js/grammar_fixture_runner.mjs): JavaScript
  fixture runner for the same suite.
- [js/grammar_perf_runner.mjs](js/grammar_perf_runner.mjs): JavaScript
  prototype hot-loop benchmark for term matcher rendering.
- [js/compiled_matcher_perf.mjs](js/compiled_matcher_perf.mjs): JavaScript
  benchmark for a precompiled sparse term matcher.
- [resource_bundle_exporter.py](resource_bundle_exporter.py): flattened KV and
  compact JSON exporter with round-trip validation.
- [profile_requirement_validator.py](profile_requirement_validator.py):
  profile-driven requirement checks.
- [apple_morphology_probe.swift](apple_morphology_probe.swift) and
  [apple_comparison_runner.py](apple_comparison_runner.py): local Apple
  Foundation morphology comparison and timing probe.
- [ui](ui): static browser playground for message -> bundle -> term matcher ->
  output.

Validation:

```bash
python3 dev-docs/experiments/mf2-grammar-agreement/profile_requirement_validator.py
python3 dev-docs/experiments/mf2-grammar-agreement/grammar_bundle_loader.py --include-planned
node dev-docs/experiments/mf2-grammar-agreement/js/grammar_fixture_runner.mjs
node dev-docs/experiments/mf2-grammar-agreement/js/grammar_perf_runner.mjs \
  dev-docs/experiments/mf2-grammar-agreement/fixtures/de/case-articles.json \
  100000
node dev-docs/experiments/mf2-grammar-agreement/js/compiled_matcher_perf.mjs \
  dev-docs/experiments/mf2-grammar-agreement/fixtures/de/case-articles.json \
  1000000
python3 dev-docs/experiments/mf2-grammar-agreement/resource_bundle_exporter.py \
  dev-docs/experiments/mf2-grammar-agreement/fixtures/fr/inventory.json \
  --out-dir /private/tmp/mf2-grammar-export

python3 dev-docs/experiments/mf2-grammar-agreement/apple_comparison_runner.py \
  --iterations 5000
```

Browser demo:

```text
http://127.0.0.1:8787/
```

## Documents

- [term-pattern-model.md](term-pattern-model.md): canonical model,
  MF2-vs-JSON tradeoffs, and performance analysis.
- [mf2-integration.md](mf2-integration.md): MF2 function semantics and
  validation model.
- [resource-bundle-api.md](resource-bundle-api.md): KV resource API and storage
  shapes.
- [language-feature-inventory.md](language-feature-inventory.md):
  cross-language grammar challenges.
- [mojito-data-model-notes.md](mojito-data-model-notes.md): proposed Mojito
  storage model.
- [mojito-implementation-plan.md](mojito-implementation-plan.md): staged Mojito
  rollout plan.
- [multi-runtime-plan.md](multi-runtime-plan.md): JS, Java, and Rust plan.
- [status.md](status.md): current state and next work.
- [apple-comparison](apple-comparison): local Apple Foundation comparison
  fixture, notes, and performance observations.

## Remaining Work

- Replace regex prototypes with real MF2 parser bindings.
- Compile term matchers instead of evaluating raw source patterns.
- Implement Java/Mojito services and fixture tests.
- Build the TMS editor that generates sparse term matchers.
- Decide when Rust binary pack work is worth doing.
