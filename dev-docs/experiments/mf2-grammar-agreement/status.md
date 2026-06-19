# MF2 Grammar Agreement Status

Last updated: 2026-05-24

## Current State

This exploration has been corrected from a metadata-first model to a
term-pattern-first model. It is not production code, but the architecture now
has executable fixtures, a browser demo, and a clearer storage/performance
direction.

## Completed Artifacts

### Architecture

- [README.md](README.md): proposal overview.
- [term-pattern-model.md](term-pattern-model.md): revised canonical model using
  term-level MF2 form patterns, plus MF2-vs-JSON tradeoffs and performance
  analysis.
- [language-feature-inventory.md](language-feature-inventory.md): cross-language
  feature inventory.
- [mf2-integration.md](mf2-integration.md): function semantics, fallback, and
  diagnostic model.
- [resource-bundle-api.md](resource-bundle-api.md): resource-store abstraction,
  storage shapes, compact/binary direction.
- [mojito-data-model-notes.md](mojito-data-model-notes.md): Mojito data-model
  concepts.
- [mojito-implementation-plan.md](mojito-implementation-plan.md): staged Mojito
  rollout with DDL/API/service sketches.
- [multi-runtime-plan.md](multi-runtime-plan.md): JS, Java, Rust target plan.

### Schemas And Profiles

- [schema/grammar-bundle.schema.json](schema/grammar-bundle.schema.json)
- [schema/grammar-diagnostic.schema.json](schema/grammar-diagnostic.schema.json)
- [schema/locale-profile.schema.json](schema/locale-profile.schema.json)
- [profiles](profiles): `fr`, `de`, `ru`, `ar`, `sw`, `ja`, `ko`, `cy`.

### Fixtures

Positive fixture families:

- French gender/article/adjective/count/plural.
- German gender/case article.
- Russian case/animacy.
- Arabic person/gender/number verb agreement.
- Swahili noun-class agreement.
- Japanese classifier counts.
- Korean politeness/formality.
- Welsh mutation.

Negative fixtures:

- missing morphology;
- invalid term-level MF2 pattern;
- invalid argument type;
- unsupported option value.

### Executable Prototypes

- [grammar_bundle_loader.py](grammar_bundle_loader.py): Python fixture runner.
- [js/grammar_fixture_runner.mjs](js/grammar_fixture_runner.mjs): JavaScript
  fixture runner.
- [js/grammar_perf_runner.mjs](js/grammar_perf_runner.mjs): JavaScript
  prototype term matcher benchmark.
- [js/compiled_matcher_perf.mjs](js/compiled_matcher_perf.mjs): JavaScript
  compiled sparse matcher benchmark.
- [resource_bundle_exporter.py](resource_bundle_exporter.py): flat/compact JSON
  exporter with round-trip validation.
- [profile_requirement_validator.py](profile_requirement_validator.py):
  profile-driven requirement validation.
- [apple_morphology_probe.swift](apple_morphology_probe.swift) and
  [apple_comparison_runner.py](apple_comparison_runner.py): local Apple
  Foundation morphology comparison, edge-case probe, and timing comparison.
- [ui](ui): static end-to-end browser demo for message pattern -> KV bundle ->
  term MF2 form selection -> formatted output.

## Validation Commands

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

## What Is Proven

- The same fixture suite can run in Python and JavaScript.
- Positive formatting works across eight grammar challenge families.
- French and German fixtures can render through explicit term-level MF2
  `forms.default` matchers.
- Structured diagnostics work for current negative fixtures.
- Invalid term-level MF2 patterns are covered by a negative fixture in both
  Python and JavaScript runners.
- Locale profiles can derive expected morphology requirements from
  `function + options`.
- Canonical fixtures can round-trip through flattened KV JSON.
- French fixtures can round-trip through flattened KV JSON. Explicit term
  patterns are preserved in compact export instead of being collapsed into lossy
  metadata flags.
- Local Apple Foundation comparison is repeatable. On the current machine,
  Apple reports inflection support for French and German; the probe changes the
  explicit German accusative example but leaves the tested French
  article/elision examples unchanged. Our explicit term patterns render the
  intended French and German outputs. A 5k-iteration run measured Apple
  Foundation at roughly 70 us/op and the Python prototype matcher at roughly
  6-7 us/op for the German comparison case.
- The JavaScript prototype matcher now has a repeatable benchmark. A local
  100k-iteration run measured roughly 2.8 us/op for the German explicit
  accusative case using the current raw-pattern prototype.
- A precompiled JavaScript sparse matcher benchmark measured roughly
  0.08 us/op for the same term-form selection, supporting the design direction
  of compiling `forms.default` for production runtimes.

## What Is Not Proven Yet

- Real Java implementation in Mojito `common`.
- Real Rust crate or `.gagp` binary encoder/decoder.
- Full MF2 parser integration; prototypes use limited expression parsing and a
  tiny `.input`/`.match` evaluator for term forms.
- TMS UI implementation.
- DB migrations and REST services.
- Profile-driven validation wired into the formatter rather than separate
  validator script.
- Android/iOS resource adapters.
- Real schema validation using a JSON Schema library.
- Apple comparison only covers local macOS Foundation behavior and should not be
  treated as portable conformance.

## Recommended Next Work

1. Move Java implementation into `common` with Jackson and fixture tests.
2. Add a real MF2 parser/binding instead of regex expression parsing.
3. Compile term `forms.default` patterns into sparse matcher ASTs.
4. Implement `GrammarPatternAnalyzer` in Java and feed Mojito diagnostics.
5. Add `grammar_term`/`grammar_term_locale` migrations behind a feature flag.
6. Build TMS term editor UI that generates term-level MF2 matchers.
7. Add canonical and compact JSON export endpoints.
8. Decide whether Rust binary pack work is needed now or after JSON runtime
   proves useful.

## Decision Point

The exploration cleanup is complete enough to stop the 5-minute monitor. The
term-pattern docs, fixture schemas, UI demo, Apple comparison, performance
probes, and compact export story consistently use the revised model. The next
step should be real implementation work in Java/Mojito or a TypeScript runtime
package, not more background cleanup.
