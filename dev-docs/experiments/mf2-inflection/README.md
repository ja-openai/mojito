# MF2 Inflection Experiments

Small experiments for the Mojito/MF2 inflection design. These are intentionally
generator-side tools, not runtime APIs.

## Smoke Pipeline

Run the fixture-only smoke pipeline:

```bash
python3 dev-docs/experiments/mf2-inflection/run_smoke.py
```

It validates the supplemental dictionary parser, noun-pack estimator, Serbian
case-pack estimator, the cross-runtime `:term` option contract, `:term`
requirement scanner, term-pack compiler, and renderer outputs, the pinned M2IF
binary fixture decoder, release-validation fixture-bundle materialization and
manifest/report conformance, plus the low-inflection locale audit. It does not
require the full Unicode Git LFS dictionary.

## M2IF Binary Fixture Decoder/Encoder

`m2if_decode_fixture.py` is a tiny non-Java decoder for pinned `.m2if.hex`
fixtures. The default Portuguese agreement fixture is provenance-only, the
Spanish article fixture covers provenance-only stressed feminine article
composition, the Italian article fixture covers provenance-only `lo/gli` and
elision-class composition, while the Danish and Swedish genitive/definiteness
fixtures carry embedded provenance plus automatic `exportPolicy`, and the
Arabic explicit-form fixtures carry
embedded provenance plus review-required and approved `exportPolicy` shapes. The Hebrew
construct-state fixture adds a second review-required policy shape for a
different grammar axis, the Malayalam multi-case fixture covers the same
review-required packaging path for richer case inventories, the Malayalam
approved multi-case fixture covers the approved automatic-export path for that
fixture including vocative rows, and the German article/case fixture covers
provenance-only case/article rows. The Hindi
case-form fixture adds a Devanagari direct/oblique/vocative target, the
Russian case-form fixture adds a Cyrillic, multi-case provenance-only target,
the Serbian case-form fixture adds a second Slavic, multi-case provenance-only
target, the Turkish suffix fixture covers provenance-only compact suffix
metadata, and the Turkish explicit-template fixtures cover provenance-only
hand-picked and generated candidate-selection rows for consonant mutation and
irregular plurals.
The decoder validates byte length, SHA-256, little-endian header, section
directory, string pool, row tables, embedded metadata, closed-world term/form
invariants, and then renders a sample message through the Python renderer.

```bash
python3 dev-docs/experiments/mf2-inflection/m2if_decode_fixture.py --fixture-kind all
python3 dev-docs/experiments/mf2-inflection/m2if_decode_fixture.py
python3 dev-docs/experiments/mf2-inflection/m2if_decode_fixture.py --fixture-kind es-article
python3 dev-docs/experiments/mf2-inflection/m2if_decode_fixture.py --fixture-kind it-article
python3 dev-docs/experiments/mf2-inflection/m2if_decode_fixture.py --fixture-kind da-export-policy
python3 dev-docs/experiments/mf2-inflection/m2if_decode_fixture.py --fixture-kind sv-export-policy
python3 dev-docs/experiments/mf2-inflection/m2if_decode_fixture.py --fixture-kind ar-review-required
python3 dev-docs/experiments/mf2-inflection/m2if_decode_fixture.py --fixture-kind ar-approved
python3 dev-docs/experiments/mf2-inflection/m2if_decode_fixture.py --fixture-kind he-review-required
python3 dev-docs/experiments/mf2-inflection/m2if_decode_fixture.py --fixture-kind ml-review-required
python3 dev-docs/experiments/mf2-inflection/m2if_decode_fixture.py --fixture-kind ml-approved
python3 dev-docs/experiments/mf2-inflection/m2if_decode_fixture.py --fixture-kind de-article-case
python3 dev-docs/experiments/mf2-inflection/m2if_decode_fixture.py --fixture-kind hi-case-form
python3 dev-docs/experiments/mf2-inflection/m2if_decode_fixture.py --fixture-kind ru-case-form
python3 dev-docs/experiments/mf2-inflection/m2if_decode_fixture.py --fixture-kind sr-case-form
python3 dev-docs/experiments/mf2-inflection/m2if_decode_fixture.py --fixture-kind tr-suffix
python3 dev-docs/experiments/mf2-inflection/m2if_decode_fixture.py --fixture-kind tr-explicit-template
python3 dev-docs/experiments/mf2-inflection/m2if_decode_fixture.py --fixture-kind tr-explicit-template-auto
```

Native ports can use this script as a compact parity target before implementing
a full renderer.

`m2if_encode_fixture.py` mirrors the V0 Java binary layout for generated
compiled term-pack JSON fixtures. Use it only for checked-in fixture refreshes;
Java remains the runtime source of truth, and the decoder should reproduce the
same bytes before a new hex fixture is accepted.

Native reader conformance should use the seventeen pinned fixtures as levels:

- Level 0 decode: read all hex fixtures, validate exact byte length, SHA-256,
  header counts, section offsets/lengths, 4-byte section alignment, no overlap,
  no trailing bytes, strict UTF-8 strings, NUL terminators, all string indexes,
  empty V0 bindings, closed-world term/form ownership, and duplicate term/form
  rejection behavior.
- Level 1 metadata/render: preserve Portuguese, Spanish, and Italian
  provenance, preserve Danish and Swedish provenance plus automatic `exportPolicy`,
  preserve Arabic provenance plus review-required and approved `exportPolicy`, preserve
  Hebrew provenance plus
  review-required construct-state `exportPolicy`, preserve Malayalam provenance
  plus review-required and approved multi-case `exportPolicy`, preserve German
  provenance-only article/case metadata with absent `exportPolicy`, preserve
  Hindi, Russian, and Serbian provenance-only case-form metadata with absent
  `exportPolicy`, preserve Turkish provenance-only suffix metadata plus manual
  and automatic explicit-template exception metadata with absent `exportPolicy`, reject
  malformed export-policy reason counts or review reasons that drift from structured
  diagnostics, and render the Portuguese, Spanish,
  Italian, Danish, Swedish, Arabic, Hebrew, Malayalam, German, Hindi, Russian,
  Serbian, and Turkish sample messages exactly as this decoder does.
- Level 2 encode, if the native runtime writes M2IF: re-emit deterministic bytes
  for embedded-metadata fixtures and keep row-only output free of cold metadata.
  A true `mmap` implementation is not required for conformance; equivalent
  bounds-checked byte-buffer reads are fine.

## Locale Data Survey

`locale_data_survey.py` inventories the Unicode inflection checkout without
parsing large dictionaries. It records supported locale groups, dictionary/XML
presence, Git LFS object sizes and hashes, local cache materialization, pronoun
CSV availability, and whether Mojito already has a runtime prototype for that
locale.

```bash
python3 dev-docs/experiments/mf2-inflection/locale_data_survey.py \
  --out dev-docs/experiments/mf2-inflection/locale_data_survey_fixture.json
```

The current survey covers 25 locale groups. Fourteen already have runtime
prototypes (`ar`, `da`, `de`, `es`, `fr`, `he`, `hi`, `it`, `ml`, `pt`, `ru`, `sr`, `sv`, `tr`),
fourteen have dictionary/XML data materialized in
`/Users/ja/.cache/mf2-inflection-data`, one has dictionary/XML materialized
directly in the checkout (`sr`), and all 25 have pronoun CSV files. Arabic is
now materialized in the local cache and has the first explicit-form fixture for
its new grammar family; its cached dictionary and XML hashes match the Unicode
Git LFS object IDs from the checkout. Hebrew is also materialized in the local
cache and has a first construct-state runtime fixture. Malayalam is
materialized in the local cache and has a first multi-case runtime fixture.
Danish, Norwegian Bokmål, Dutch, and Swedish are now materialized in the local
cache and have a first cross-locale pack-shape audit. Danish and Swedish also
have the first Nordic explicit genitive/definiteness runtime fixtures.
Norwegian Bokmål now has a metadata-first validation fixture for gender,
number, and definiteness, but no rendered noun-case runtime.
Dutch now has a metadata/diminutive validation fixture for gender, number, and
diminutive metadata, but no rendered case or definiteness runtime.

`low_inflection_locale_audit.py` separately classifies the remaining
low-inflection locale groups for MF2 profile/no-op behavior:

```bash
python3 dev-docs/experiments/mf2-inflection/low_inflection_locale_audit.py \
  --out dev-docs/experiments/mf2-inflection/low_inflection_locale_audit_fixture.json
```

The current audit covers `en`, `id`, `ja`, `ko`, `ms`, `th`, `vi`, `zh`, and
`yue`. English and Korean are marked `data-materialization-required` because
their dictionary or inflectional sources are still Git LFS pointers. Indonesian,
Japanese, Malay, Thai, Vietnamese, Chinese, and Cantonese are marked
`profile-only-noop`: the current Unicode checkout has no noun dictionary or
inflectional XML rows to justify a runtime term-inflection pack, though all
nine locales have pronoun inventories that can stay as profile metadata.
The survey and low-inflection audit currently regenerate byte-for-byte against
their fixtures.

`pronoun_profile_pack.py` derives the first profile/no-op pronoun metadata pack
from that audit:

```bash
python3 dev-docs/experiments/mf2-inflection/pronoun_profile_pack.py \
  --out dev-docs/experiments/mf2-inflection/pronoun_profile_pack_fixture.json
```

The pack deliberately carries only materialized pronoun CSV metadata and the
source-data gate for each locale: 9 locales, 162 pronoun rows, 137 unique
pronoun values, and `runtimeTermInflection=false`. It keeps English and Korean
as `data-materialization-required` because their noun data is still unavailable
or incomplete, and keeps Indonesian, Japanese, Malay, Thai, Vietnamese,
Chinese, and Cantonese as profile-only/no-op for term rendering.
It is intentionally generator/test-only today: the Java loader remains
package-private `@GeneratorSupport`, and no REST/MCP or runtime API should
consume it until a concrete authoring workflow needs pronoun-profile previews.

Remaining-language V0 decision: do not add runtime term-rendering packs for
`nb`, `nl`, `en`, `ko`, `id`, `ja`, `ms`, `th`, `vi`, `zh`, or `yue` before
broader rollout. Norwegian Bokmal and Dutch stay metadata-only authoring inputs
because their fixtures validate useful source-backed metadata but not a broad
runtime form table. English and Korean stay profile-only with data
materialization deferred until a concrete source-preview, TTS/SSML, or
product-copy use case justifies the raw dictionary inputs. Indonesian,
Japanese, Malay, Thai, Vietnamese, Chinese, and Cantonese stay profile-only
no-op for term rendering because the current checkout has pronoun inventories
but no noun dictionary or inflectional XML rows that justify a runtime pack.
Java/common validation and the Workbench requirement extractor now return
`unsupported-locale-runtime-term-inflection` for option-bearing `:term` usages
in these metadata/profile locales, so authoring reports fail on the locale
policy before they cascade into impossible missing-form diagnostics. Bare
`:term` usages remain on the existing binding/form validation path.
Binding-manifest validation and render-preview preflight surface the same policy
as an `unsupported-locale-runtime-term-inflection` binding status, so product UX
does not ask users to fix missing term bindings for a locale that intentionally
has no runtime term-form pack.

## Source Data Packaging Policy

Generated runtime packs are the release boundary. Raw Unicode
`dictionary_*.lst`, `inflectional_*.xml`, and pronoun/supplemental CSV inputs
are generator inputs only, even when they are small enough to check out
directly. Release builds should either consume already generated JSON/M2IF
packs or regenerate them from a pinned source manifest and byte-compare the
output before publishing.

The Java-side `Mf2InflectionReleaseValidator` is the current product/build
preflight facade for these generated artifacts. It validates compiled term-pack
JSON, compiled M2IF bytes, and the Hindi pronoun agreement JSON sidecar and can
emit a deterministic `mojito-mf2-inflection/release-validation-report/v0`
summary with stable artifact failure codes. Build jobs can either pass
artifacts directly or pass a
`mojito-mf2-inflection/release-validation-manifest/v0` manifest containing
`artifactId`, `kind`, and relative `path` entries under an explicit base
directory; unreadable files and path escapes are reported as structured
artifact failures.

`release_validation.py` mirrors that manifest/report contract for native and
build-script conformance. It validates the compiled JSON pack shell plus
generated-summary term/form counts, form-key alignment, source-row maps,
positive export-policy reason counts, source-backed provenance, and review
diagnostics; it rejects malformed compiled-pack diagnostics, checks decoded
M2IF provenance, validates Hindi sidecar provenance/pack-shape/rows/summary
arithmetic/byte estimates, sorts report artifacts deterministically, and exits
non-zero on failed artifacts unless
`--allow-failures` is set.

The artifact-level failure-code set is intentionally small and mirrored by the
Java and native validators:

| Code | Emitted when |
| --- | --- |
| `invalid-release-artifact-path` | a manifest artifact path is absolute or escapes `baseDirectory` |
| `unreadable-release-artifact` | a manifest artifact cannot be read from disk |
| `invalid-compiled-term-pack-json` | a compiled JSON pack fails schema, provenance, generation-summary, export-policy, or diagnostics validation |
| `invalid-compiled-term-pack-m2if` | a compiled M2IF pack fails binary decode or decoded provenance validation |
| `invalid-hindi-pronoun-agreement-pack-json` | the Hindi pronoun sidecar fails schema, provenance, row, summary, or byte-estimate validation |

`release_fixture_bundle.py` materializes a CI-ready fixture release bundle from
the checked Java test resources: 17 compiled JSON packs, 17 binary M2IF packs
converted from checked `.m2if.hex` fixtures, and the Hindi pronoun sidecar. It
writes `release-validation-manifest.json` and, with `--validate`, writes the
matching validation report. The materializer rejects artifact-count drift,
duplicate IDs/paths, unexpected JSON/M2IF source-target suffixes, source paths
outside the checked fixture resource root, and symlinked output paths that would
write outside the bundle. The shared conformance wrapper pins the exact
manifest/report artifact IDs, kinds, and manifest paths, not only the
35-artifact count.
The shared MF2 conformance gate also runs this contract through
`mf2/conformance/validate_inflection_release_fixture.py`, which materializes
the bundle in a temporary directory. The Python MF2 package gate exposes the
same contract as `sh run.sh inflection-release`, and `mf2/check.sh` routes
through that package command.

```bash
python3 dev-docs/experiments/mf2-inflection/release_fixture_bundle.py \
  --out-dir /tmp/mf2-release-fixture-bundle \
  --validate

python3 mf2/conformance/validate_inflection_release_fixture.py

(cd mf2/python && sh run.sh inflection-release)

python3 dev-docs/experiments/mf2-inflection/release_validation.py \
  --manifest path/to/release-manifest.json \
  --base-dir path/to/release-artifacts \
  --out /tmp/mf2-release-validation-report.json
```

The regeneration source manifest for any source-backed pack must include the
stable `sourceLabels` key, source path, byte size, SHA-256, Git LFS pointer
state, upstream revision, and license. The release-artifact gate validates the
embedded provenance block and fails if license/generator/source labels/sources
are missing or incoherent, source labels drift from source records, source
hashes are malformed, byte sizes are negative, or Git LFS flags are not
boolean. It does not read raw Unicode inputs during package validation;
materialized input hash/size comparisons happen in regeneration before
byte-comparing generated JSON/M2IF output. Unicode-derived packs use
`Unicode-3.0`; Turkish supplemental exception data keeps `CC0-1.0 dictionary
data; Unicode-3.0 repository packaging`.

Keep large materialized Unicode files in the local or CI cache, for example
`/Users/ja/.cache/mf2-inflection-data`, and keep `/private/tmp` reports as
scratch outputs. The checked Java fixtures and experiment fixtures are the
auditable artifacts. English and Korean dictionary LFS inputs should not be
materialized without a concrete product need, and Polish remains a separate
source-data acquisition question because the pinned local Unicode checkout used
for this survey has no `locale.group.pl`, `dictionary_pl.lst`,
`inflectional_pl.xml`, or cached Polish data.

## French Dictionary Report

`fr_dictionary_report.py` parses Unicode inflection-style French dictionary
`.lst` files and emits a JSON report with:

- source file metadata and Git LFS pointer detection;
- entry counts and feature counts;
- gender, number, part-of-speech, and flag summaries;
- ambiguous surface detection;
- skipped metadata/footer lines.

Run it against the small checked-in Unicode French supplement:

```bash
python3 dev-docs/experiments/mf2-inflection/fr_dictionary_report.py \
  --dictionary /Users/ja/code/inflection/inflection/resources/org/unicode/inflection/dictionary/supplemental_fr.lst \
  --out /private/tmp/fr-dictionary-report.json
```

The full Unicode French dictionary is currently a Git LFS pointer in the local
checkout. Once materialized, the same command can take both the main dictionary
and supplement by repeating `--dictionary`.

Without `git lfs`, the pinned upstream object can be materialized through
GitHub's media endpoint:

```bash
curl -L --fail \
  -o /private/tmp/inflection-dictionary-fr.lst \
  https://media.githubusercontent.com/media/unicode-org/inflection/9436c9ad637722826f4d8f588c64f6b7efbc743a/inflection/resources/org/unicode/inflection/dictionary/dictionary_fr.lst
```

Then run the full report:

```bash
python3 dev-docs/experiments/mf2-inflection/fr_dictionary_report.py \
  --dictionary /private/tmp/inflection-dictionary-fr.lst \
  --dictionary /Users/ja/code/inflection/inflection/resources/org/unicode/inflection/dictionary/supplemental_fr.lst \
  --out /private/tmp/fr-dictionary-report-full.json
```

The first full run parsed 254,502 entries and 254,136 unique surfaces. It found
4,490 surfaces with at least one ambiguity reason, including 395 surfaces with
multiple gender tags.

## French Noun Pack Report

`fr_noun_pack_report.py` narrows the same inputs to noun surfaces and estimates
rough compact lookup sizes for an MF2 French article/gender pack.

```bash
python3 dev-docs/experiments/mf2-inflection/fr_noun_pack_report.py \
  --dictionary /private/tmp/inflection-dictionary-fr.lst \
  --dictionary /Users/ja/code/inflection/inflection/resources/org/unicode/inflection/dictionary/supplemental_fr.lst \
  --out /private/tmp/fr-noun-pack-report-full.json \
  --sample-pack-out /private/tmp/fr-noun-metadata-sample-pack-full.json \
  --sample-pack-limit 0 \
  --sample-pack-ambiguity-limit 0 \
  --suffix-rule-pack-out /private/tmp/fr-gender-suffix-rule-pack-50.json \
  --suffix-rule-limit 50
```

The first full noun-focused run found:

| Metric | Count |
| --- | ---: |
| noun entries | 18,610 |
| noun surfaces | 18,572 |
| exact-gender surfaces | 17,126 |
| V0 article-candidate surfaces | 17,101 |
| surfaces with multiple gender tags | 135 |
| surfaces with missing gender | 1,311 |

The first simple sorted-table estimate for V0 article candidates is about 305
KB: 171 KB of surface strings plus 134 KB of fixed-width records. A lower-bound
feature-only estimate is about 188 KB. This is intentionally more conservative
than the old suffix/Bloom gender-only experiment, which estimated about 25 KB
for 42,295 unambiguous nouns, because this report keeps explicit dictionary
surface strings and diagnostics.

The reporter can also emit a concrete generated metadata sample pack. The pack
is still a pretty JSON debug artifact, not the runtime file format, but it uses
the same string-pool plus fixed-row lower-bound model as the estimate:

| Pack | Rows | String pool | Binary lower bound | Pretty JSON |
| --- | ---: | ---: | ---: | ---: |
| 200-row sample | 200 | 2.1 KB | 3.7 KB | 37 KB |
| all V0 article candidates | 17,101 | 171 KB | 305 KB | 3.1 MB |
| all V0 candidates + ambiguities | 17,101 exact + 135 ambiguous | 173 KB | 308 KB | 3.1 MB |

The ambiguity rows preserve multi-gender dictionary surfaces such as `livre`
and `tour` as explicit ambiguous analyses instead of letting them fall through
to unknown or heuristic lookup.

Generated packs include a `provenance` block with the Unicode source license,
generator path, stable upstream source labels, byte sizes, and SHA-256 hashes.
The shared Java provenance value now rejects source-backed packs unless
`sourceLabels` and `sources` are a 1:1 pair and both `license` and `generator`
are present, and runtime-pack sources must use 64-character lowercase SHA-256
digests; empty hand-written fixture provenance is still allowed.
For the pinned full French dictionary, the first source SHA starts with
`21e1a3d385db`.

Tiny generated fixtures from the same command live under
`common/src/test/resources/com/box/l10n/mojito/mf2/inflection/` so Java loader
tests exercise the real generator JSON shape:

- `fr_noun_metadata_pack_fixture.json`: 12 exact rows and 4 ambiguous rows.
- `fr_gender_suffix_rule_pack_fixture.json`: 8 exported suffix rules.

Refresh those checked-in Java fixtures with:

```bash
python3 dev-docs/experiments/mf2-inflection/fr_noun_pack_report.py \
  --dictionary /private/tmp/inflection-dictionary-fr.lst \
  --dictionary /Users/ja/code/inflection/inflection/resources/org/unicode/inflection/dictionary/supplemental_fr.lst \
  --out /private/tmp/fr-noun-pack-report-fixture.json \
  --sample-pack-out common/src/test/resources/com/box/l10n/mojito/mf2/inflection/fr_noun_metadata_pack_fixture.json \
  --sample-pack-limit 12 \
  --sample-pack-ambiguity-limit 4 \
  --suffix-rule-pack-out common/src/test/resources/com/box/l10n/mojito/mf2/inflection/fr_gender_suffix_rule_pack_fixture.json \
  --suffix-rule-limit 8
```

Then verify the generator-to-loader contract:

```bash
mvn -pl common -Dtest=FrenchNounMetadataPackJsonLoaderTest,FrenchGenderSuffixRulePackJsonLoaderTest test
```

The same report also trains Unicode-derived suffix/Bloom gender classifiers from
single-token exact-gender noun surfaces:

| Profile | Surfaces | Rules | Suffix-only | Validated | Exceptions | Corrections | Compact est. |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `unicode-web-medium-like` | 16,590 | 610 | 79.10% | 100.00% | 3,467 | 15 | 11.4 KB |
| `unicode-backend-like` | 16,590 | 370 | 79.07% | 100.00% | 3,473 | 10 | 9.8 KB |

This is not an article pack and does not carry the full surface dictionary. It
is exact only for the declared supported set after build-time validation; unknown
runtime words still need source/confidence diagnostics.

For readable fallback experiments, the reporter can also emit just the generated
suffix-rule part of the backend-like classifier:

| Artifact | Training surfaces | Total rules | Exported rules | Suffix-only | Compact rule bytes | Pretty JSON |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 50-rule fixture | 16,590 | 370 | 50 | 79.07% | 3.1 KB full / 569 B exported | 6.7 KB |

This is deliberately weaker than the full old-style suffix/Bloom/correction
classifier. It is useful as a small, inspectable heuristic fixture, while exact
formatting should still prefer explicit terms or dictionary metadata.

## Serbian Case Pack Report

`sr_case_pack_report.py` parses the materialized Unicode Serbian dictionary plus
`inflectional_sr.xml` and estimates a first case-form lookup pack. Serbian is the
first non-French experiment because it exercises outer inflection through
`case`, while the local Unicode checkout already has the needed data
materialized.

```bash
python3 dev-docs/experiments/mf2-inflection/sr_case_pack_report.py \
  --dictionary /Users/ja/code/inflection/inflection/resources/org/unicode/inflection/dictionary/dictionary_sr.lst \
  --inflectional /Users/ja/code/inflection/inflection/resources/org/unicode/inflection/dictionary/inflectional_sr.xml \
  --out /private/tmp/sr-case-pack-report.json \
  --case-form-pack-out /private/tmp/sr-case-form-pack.json \
  --compiled-case-form-pack-out /private/tmp/sr-compiled-case-form-pack.json \
  --case-form-pack-limit 12
```

The current report found:

| Metric | Count |
| --- | ---: |
| dictionary entries | 1,140 |
| supported noun/proper-noun entries | 1,030 |
| unique supported surfaces | 1,027 |
| nominative singular candidates | 220 |
| dictionary inflection patterns | 71 |
| missing inflection patterns | 0 |
| ambiguous supported surfaces | 558 |

The report now pins the generator-side review policy for the runtime export:
159 dictionary rows are automatic closed-world case-form terms, 981 dictionary
rows are blocked with explicit reason counts, and 558 supported surfaces require
review because their surface metadata is ambiguous. The largest blocked reason
is `not-nominative` with 755 rows; the review-required reason map mirrors the
report's ambiguity reasons so Java can validate the policy without re-running
the generator.

The first simple case-pack estimate is about 29 KB: 14 KB of surface strings,
1.7 KB of pattern-template strings, 10 KB of exact surface rows, and 3.4 KB of
pattern slot rows. That is intentionally a conservative row model, not a final
mmap/trie format.

The same generator can emit a readable case-form sample pack derived from
nominative singular dictionary entries and their Unicode pattern templates. The
first exported fixture has 159 candidate terms, 12 exported terms, 105 form
rows, a 1.4 KB string pool, and a 2.9 KB rough binary lower-bound estimate.
Example generated term `sr.case.mačka` includes forms such as
`accusative.singular=mačku`, `accusative.plural=mačke`, and
`dative.singular=mački`.

The compacted compiled export drops debug-only fields such as `stem` and emits
the same row-oriented `compiled-term-pack/v0` shape used by the renderer:
`strings`, `terms`, `formSets`, runtime provenance, size estimates, and empty
`diagnostics`. For the 12-term fixture it is 15 KB as pretty JSON, with the same
2.9 KB rough binary lower bound as the debug case-form pack.

The current Serbian data is a small Unicode sample compared with the full
French dictionary. It is still useful for the checked Java/common V0 path
because it proves the next pack family needs case/gender/number slots, pattern
provenance, ambiguity diagnostics, and deterministic build validation before we
materialize larger German or Russian dictionaries. This experiment is release
fixture evidence for selected V0 slices, not a claim that all inflection types
or all languages are covered.

Tiny generated fixtures from the same command live under
`common/src/test/resources/com/box/l10n/mojito/mf2/inflection/` so Java loader
tests exercise the real generator JSON shape:

- `sr_case_pack_report_fixture.json`: full Serbian counts and feature maps with
  4 sampled nominative candidates, 4 sampled ambiguous surfaces, and closed-world
  case-form review policy counts.
- `sr_case_form_pack_fixture.json`: 12 generated terms and 105 generated
  case-form rows, including `sr.case.mačka`.
- `sr_compiled_case_form_pack_fixture.json`: the same generated Serbian forms in
  renderer-ready `compiled-term-pack/v0` row shape.

Refresh the checked-in Java fixture with:

```bash
python3 dev-docs/experiments/mf2-inflection/sr_case_pack_report.py \
  --dictionary /Users/ja/code/inflection/inflection/resources/org/unicode/inflection/dictionary/dictionary_sr.lst \
  --inflectional /Users/ja/code/inflection/inflection/resources/org/unicode/inflection/dictionary/inflectional_sr.xml \
  --out common/src/test/resources/com/box/l10n/mojito/mf2/inflection/sr_case_pack_report_fixture.json \
  --case-form-pack-out common/src/test/resources/com/box/l10n/mojito/mf2/inflection/sr_case_form_pack_fixture.json \
  --compiled-case-form-pack-out common/src/test/resources/com/box/l10n/mojito/mf2/inflection/sr_compiled_case_form_pack_fixture.json \
  --case-form-pack-limit 12 \
  --max-samples 4
```

Then verify the generator-to-loader contract:

```bash
mvn -pl common -Dtest=SerbianCasePackReportJsonLoaderTest,SerbianCaseFormPackJsonLoaderTest,CompiledTermPackJsonLoaderTest,CompiledTermPackBinaryCodecTest test
```

## German Article/Case Report

`de_article_case_report.py` parses the materialized Unicode German dictionary
plus `inflectional_de.xml` and estimates an eager article/case phrase-form pack.
German is the first article-and-case target because forms such as
`definite.accusative.singular` depend on case, gender, number, and article
choice.

The local environment does not have `git-lfs`, so the German inputs are
materialized in cache instead of replacing the pointer files in the Unicode
checkout:

```bash
python3 dev-docs/experiments/mf2-inflection/de_article_case_report.py \
  --dictionary /Users/ja/.cache/mf2-inflection-data/dictionary_de.lst \
  --inflectional /Users/ja/.cache/mf2-inflection-data/inflectional_de.xml \
  --out /private/tmp/de-article-case-report.json \
  --max-samples 8
```

The current report found:

| Metric | Count |
| --- | ---: |
| dictionary entries | 206,419 |
| supported noun/proper-noun entries | 161,665 |
| unique supported surfaces | 161,651 |
| article/case candidate terms | 41,363 |
| dictionary inflection patterns | 1,207 |
| missing inflection patterns | 0 |
| ambiguous supported surfaces | 104,487 |
| eager phrase-form rows | 661,808 |
| eager phrase-form lower bound | 17.4 MB |

Examples from the generated samples and spot checks:

```text
Katze -> definite.accusative.singular = die Katze
Katze -> definite.dative.plural = den Katzen
Mädchen -> definite.nominative.singular = das Mädchen
Mädchen -> indefinite.genitive.plural = Mädchen
```

The eager phrase pack is useful for backend/build validation, but it is too
large for a default client pack. German should probably compile only the
closed-world product terms for runtime and keep the full dictionary-derived
article/case generator as an authoring, prefill, and validation input. The first
skip counts also show why diagnostics matter: some common surfaces have multiple
inflection patterns (`Hund`) or conflicting valid noun forms (`Buch`).
The generator also rejects non-invariant singular+plural dictionary rows before
phrase generation, which preserves true invariant terms such as `Mädchen` while
skipping rows that would otherwise turn `1-Euro-Jobs` into a doubled-s plural.
The generated report now carries a `reviewPolicy` for production export:
41,363 terms are automatic closed-world article/case export candidates,
104,487 ambiguous supported surfaces require review, and 165,056 dictionary
entries are blocked from runtime export with reason counts such as
`not-nominative`, `not-singular`, `unsupported-part-of-speech`, and
`singular-plural-surface-not-invariant`. Java and smoke validation pin this
policy against the existing count and reason maps.

`de_term_usage_example.json` and `de_term_pack_example.json` show the intended
closed-world runtime shape for German product terms. The generated compact
fixture contains three terms (`1-Euro-Job`, `Katze`, `Mädchen`), 24 required
forms, and a 935 byte mmap lower-bound estimate before the binary codec appends
the locale string. Java validates the JSON fixture and the binary round trip,
including:

```text
Gelöscht: {$item :term article=definite case=accusative count=$count}
  de.article_case.katze, count=2 -> Gelöscht: die Katzen.

Mit {$item :term article=definite case=dative count=$count}
  de.article_case.maedchen, count=2 -> Mit den Mädchen.

Erstellt: {$item :term article=indefinite case=nominative count=$count}
  de.article_case.maedchen, count=2 -> Erstellt: Mädchen.
```

## Spanish Next Target

Spanish is cached outside the Unicode checkout for the same reason as German:
the local checkout has LFS pointers but not the real data files. The cached
files match the pointer hashes:

| File | Size | SHA-256 |
| --- | ---: | --- |
| `dictionary_es.lst` | 37,232,751 bytes | `c027bc481306bbdae35aae1bce50c683c6e1c41e91643e68c9efda4e041d6389` |
| `inflectional_es.xml` | 4,112,171 bytes | `1ed54f3e48d32ac020d22fcb02a52ab05e72e8b73914891429798ebd09a98541` |

`es_noun_pack_report.py` now produces the first Spanish noun/proper-noun
gender+number report:

| Metric | Count |
| --- | ---: |
| dictionary entries | 556,656 |
| supported noun/proper-noun entries | 69,315 |
| unique supported surfaces | 69,243 |
| gender/number candidate surfaces | 63,698 |
| exact gender/number surfaces | 52,190 |
| ambiguous supported surfaces | 17,053 |
| dictionary inflection patterns | 809 |
| missing inflection patterns | 0 |
| inflectional patterns | 1,129 |
| noun inflectional patterns | 521 |
| metadata-pack lower bound | 1.21 MB |
| stressed feminine singular article overrides | 38 |
| eager generated article phrase rows | 127,396 |
| eager generated article phrase lower bound | 3.43 MB |

The report confirms Spanish is much smaller than full German phrase generation
for the first useful runtime slice: a simple gender/number metadata table is
about 1.21 MB before trie/minimal-perfect-hash work. Spanish article generation
is also tractable if the metadata carries the dictionary's `stressed` grammeme:
only 38 feminine singular surfaces in the candidate set need the `el`/`un`
override (`el agua`, `un agua`) instead of the default `la`/`una`. Eagerly
materializing generated definite/indefinite article phrases would be about 3.43
MB, so the better runtime direction is central article generation from compact
gender/number/stress metadata while keeping closed-world explicit forms for
product terms that need guaranteed wording or non-standard terminology.
The report now carries the same production `reviewPolicy` shape as Portuguese:
52,190 surfaces are automatic compact-export candidates, 11,508 compact
candidates need glossary review because they retain non-gender/number ambiguity
markers, and 5,545 surfaces are blocked from compact export until gender/number
metadata is resolved. Smoke validation regenerates the report, compares it to
`es_noun_pack_report_fixture.json`, and pins the policy counts and reason maps.

## Italian Noun/Article Report

Italian data is now cached outside the Unicode checkout because the checkout
files are Git LFS pointers:

| File | Size | SHA-256 |
| --- | ---: | --- |
| `dictionary_it.lst` | 27,327,567 bytes | `c5836997d872f7e5833fe0c338109591235b91f0306fa3e317f1e7aabbfe42a9` |
| `inflectional_it.xml` | 1,394,074 bytes | `70ad7ba37f88b96607ba367549cc2260453ff774eebb3533d728da0b7c072653` |

`it_noun_pack_report.py` now produces the first Italian noun/proper-noun
gender+number and article-class report:

| Metric | Count |
| --- | ---: |
| dictionary entries | 412,265 |
| supported noun/proper-noun entries | 63,567 |
| unique supported surfaces | 63,529 |
| gender/number candidate surfaces | 52,542 |
| exact gender/number surfaces | 45,823 |
| ambiguous supported surfaces | 17,706 |
| used dictionary inflection patterns | 539 |
| missing inflection patterns | 0 |
| metadata-pack lower bound | 990 KB |
| `standard` article-class candidates | 38,922 |
| `lo/gli` article-class candidates | 1,911 |
| elision article-class candidates | 11,709 |
| eager generated article phrase lower bound | 2.77 MB |

Italian reuses the compact article-generation family, but it needs an extra
article class beyond Spanish: masculine nouns split between `il/i` and
`lo/gli`, and vowel starts need `l'`/`un'` behavior. The generated fixture
validates representative samples: `lo gnomo`, `gli gnomi`, `il libro`,
`i cani`, `l'acqua`, and `un'ape`. The smoke pipeline regenerates the report and
compares it to `it_noun_pack_report_fixture.json`.
The generated `reviewPolicy` pins 45,823 automatic compact-export candidates,
6,719 review-required compact candidates, and 10,987 blocked surfaces. Java and
smoke validation both enforce the count arithmetic and the ambiguity reason
maps so the article-shell runtime cannot accidentally accept unresolved
gender/number rows.

The runtime prototype now uses that article class directly. For Italian
`article=definite|indefinite` usages, the scanner requires gender, number,
`articleClass`, and `forms.bare.singular`/`forms.bare.plural`; the compiler
emits only those bare rows plus compact feature bits. The checked-in
`it_compiled_article_pack_fixture.json` renders `lo gnomo`, `gli gnomi`,
`il libro`, `un'acqua`, and `un'ape` in both the Python smoke path and Java
JSON/mmap tests.

## Portuguese Noun/Agreement Report

Portuguese data is now cached outside the Unicode checkout because the checkout
files are Git LFS pointers:

| File | Size | SHA-256 |
| --- | ---: | --- |
| `dictionary_pt.lst` | 2,345,650 bytes | `00a2c13c4b5c437cc8677e7115a7e8ad3ec62dcba2115698fb283541cdabee70` |
| `inflectional_pt.xml` | 637,727 bytes | `b223e087bd3ea5470fabebb6277e500561d24a7c7fbd5f867a1d115d2f3d3aef` |

`pt_noun_pack_report.py` now produces the first Portuguese noun/proper-noun
gender+number and agreement-form report:

| Metric | Count |
| --- | ---: |
| dictionary entries | 35,188 |
| supported noun/proper-noun entries | 6,628 |
| unique supported surfaces | 6,614 |
| gender/number candidate surfaces | 6,424 |
| exact gender/number surfaces | 6,116 |
| ambiguous supported surfaces | 498 |
| used dictionary inflection patterns | 245 |
| missing inflection patterns | 0 |
| metadata-pack lower bound | 112 KB |
| agreement form categories | 16 |
| eager generated agreement phrase lower bound | 2.78 MB |

The report now carries an explicit `reviewPolicy` for production export:
6,116 surfaces are automatic compact-export candidates, 308 compact candidates
must still go through glossary review because they have non-gender/number
ambiguity markers, and 190 surfaces are blocked from compact export until
gender/number metadata is resolved. Java and smoke validation pin those counts
and the reason maps so the generator cannot silently widen runtime export.

Portuguese can use compact runtime composition for a broader agreement shell
than Spanish or Italian: definite/indefinite articles, `de`/`em`/`por`
article contractions, possessive articles, and proximal/medial/distal
demonstratives with `de` and `em` contractions are all small fixed tables keyed
by gender and number. The generated fixture validates targeted samples such as
`o campo`, `a casa`, `dos campos`, `nas casas`, `pelo campo`, and `sua casa`.
The smoke pipeline regenerates the report and compares it to
`pt_noun_pack_report_fixture.json`; Java validates the same fixture for schema,
provenance, count coherence, size estimates, agreement-form table coverage,
phrase-form keys, review-policy coherence, and targeted samples.

The first Portuguese runtime checkpoint composes the most common agreement
shell from compact metadata instead of emitting eager phrase rows. With
`locale=pt`, `article=definite|indefinite` plus optional
`preposition=de|em|por` requires only gender, number, and
`forms.bare.singular`/`forms.bare.plural`. The current contraction subset is
`de+definite`, `em+definite`, `em+indefinite`, and `por+definite`; unsupported
shapes fail before compilation. The checked-in
`pt_compiled_agreement_pack_fixture.json` has 2 terms, 4 bare-form rows, Unicode
source provenance, and renders examples such as `o campo`, `os campos`,
`uma casa`, `das casas`, `num campo`, and `pelos campos` in Python smoke and
Java JSON/mmap tests.

## Russian Case Pack Audit

Russian data is now cached outside the Unicode checkout because the checkout
files are Git LFS pointers:

| File | Size | SHA-256 |
| --- | ---: | --- |
| `dictionary_ru.lst` | 76,320,564 bytes | `0cec5900694f7a7e1361ab95b3e47bca9114079b6ab8b48531c355eb8862bfeb` |
| `inflectional_ru.xml` | 1,500,300 bytes | `8fade258e582840840daa29bd6971a61a090feb33268b303582c10bd4b08b00a` |

`ru_case_pack_audit.py` parses Russian noun/proper-noun dictionary rows,
inflection patterns, animacy, case, gender, number, duplicate pattern slots, and
rough lower-bound pack sizes:

```bash
python3 dev-docs/experiments/mf2-inflection/ru_case_pack_audit.py \
  --dictionary /Users/ja/.cache/mf2-inflection-data/dictionary_ru.lst \
  --inflectional /Users/ja/.cache/mf2-inflection-data/inflectional_ru.xml \
  --out /private/tmp/ru-case-pack-audit.json \
  --max-samples 8
```

The first audit found:

| Metric | Count |
| --- | ---: |
| dictionary entries | 914,690 |
| supported noun/proper-noun entries | 911,004 |
| unique supported surfaces | 910,896 |
| ambiguous supported surfaces | 212,141 |
| nominative singular candidates | 150,023 |
| complete simple 12-slot case candidates | 68,171 |
| used dictionary inflection patterns | 749 |
| missing inflection patterns | 0 |
| patterns with duplicate slots | 300 |
| duplicate slot rows | 1,046 |
| metadata lower bound | 4.96 MB |
| eager case-form row lower bound, excluding strings | 9.82 MB |

The audit report is now checked in as
`common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ru_case_pack_audit_fixture.json`.
`RussianCasePackAuditJsonLoader` validates its schema, locale, Unicode source
provenance, allowed Russian grammar values, skip-count arithmetic, conflict-key
distribution, closed-world case-form review policy, sample shape, immutability,
and size estimates. The policy pins 68,169 unique automatic export terms,
846,521 blocked dictionary rows, and 212,141 review-required ambiguous
surfaces; the 2 duplicate term IDs explain the difference between the 68,171
complete case-form candidates and the unique runtime export terms. The Python
smoke pipeline also regenerates the audit and compares it byte-for-byte as JSON
data against the checked-in fixture.

The blocker is not missing data; it is policy. Russian has many alternate forms
for the same case/number slot, and the current audit skips 26,995 otherwise
candidate terms for conflicting generated form keys. The enhanced audit records
which keys conflict and includes concrete samples. The dominant case is
`instrumental.singular`: 26,981 conflicting candidates include normal alternates
such as `Аляской`/`Аляскою` and `Россией`/`Россиею`. Most conflicting terms have
only one conflicting key, but a smaller set of proper-name patterns conflict
across multiple singular cases, as in the `Анатолич`/`Анатолий` pattern.

Russian V0 should therefore avoid automatic canonicalization. The runtime
fixture path should start with unambiguous generated rows only, preserve
variant-bearing rows as diagnostics or authoring-side prefill choices, and
require explicit Mojito term forms when product text needs one of the variants.
A later variant-aware pack can store ordered form variants with provenance, but
the current renderer should not silently choose one Russian variant from the
dictionary.

The same audit script now exports a V0 unambiguous case-form fixture. It
intentionally skips variant-bearing rows and prioritizes three stable sample
surfaces from the Unicode tests: `кошка`, `ресторан`, and `аббатство`.

```bash
python3 dev-docs/experiments/mf2-inflection/ru_case_pack_audit.py \
  --dictionary /Users/ja/.cache/mf2-inflection-data/dictionary_ru.lst \
  --inflectional /Users/ja/.cache/mf2-inflection-data/inflectional_ru.xml \
  --out common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ru_case_pack_audit_fixture.json \
  --case-form-pack-out common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ru_case_form_pack_fixture.json \
  --compiled-case-form-pack-out common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ru_compiled_case_form_pack_fixture.json \
  --case-form-pack-limit 3 \
  --case-form-surface кошка \
  --case-form-surface ресторан \
  --case-form-surface аббатство \
  --max-samples 8
```

The checked-in fixture has 68,169 unambiguous candidate terms after duplicate
term-id filtering, exports 3 terms, contains 36 explicit case-form rows, and has
a 1.28 KB binary lower-bound estimate. `RussianCaseFormPackJsonLoader` validates
the debug pack contract for Unicode provenance, the exact 12 case/number forms
per term, literal-only V0 rows, string-pool and binary lower-bound arithmetic,
skipped-term reason keys, JSON byte count, and conversion to the compiled
renderer shape. Python smoke and Java JSON/mmap tests render forms such as
`кошку`, `кошек`, `ресторанов`, `аббатстве`, and `аббатствах`.

## Turkish Suffix Pack Survey

Turkish is now cached outside the Unicode checkout as the next MF2-supported
locale survey:

| File | Size | SHA-256 |
| --- | ---: | --- |
| `dictionary_tr.lst` | 57,207 bytes | `32097e8a123c591d4470a51e883b8afb6cebf9b5b60c0857cffd07a7c513e391` |
| `inflectional_tr.xml` | 21,129 bytes | `810637ded03292c0d48511dcfa1e293d63e044ae312293245a590585640c1445` |
| `supplemental_tr.lst` | 43,876 bytes | `17fc7f47aea713943bdedf5c25a1cca909120b96b266653f46b817c4ab0c10d5` |

`tr_suffix_pack_survey.py` joins the real dictionary, inflection XML, and
supplemental Turkish metadata:

```bash
python3 dev-docs/experiments/mf2-inflection/tr_suffix_pack_survey.py \
  --dictionary /Users/ja/.cache/mf2-inflection-data/dictionary_tr.lst \
  --inflectional /Users/ja/.cache/mf2-inflection-data/inflectional_tr.xml \
  --supplemental /Users/ja/code/inflection/inflection/resources/org/unicode/inflection/dictionary/supplemental_tr.lst \
  --out common/src/test/resources/com/box/l10n/mojito/mf2/inflection/tr_suffix_pack_survey_fixture.json \
  --explicit-template-pack-out common/src/test/resources/com/box/l10n/mojito/mf2/inflection/tr_compiled_explicit_template_pack_fixture.json \
  --explicit-template-surface çakmak \
  --explicit-template-surface gök \
  --explicit-template-surface amel \
  --explicit-template-surface anahtar \
  --max-samples 8
```

The first survey found:

| Metric | Count |
| --- | ---: |
| dictionary entries | 2,117 |
| supported noun/proper-noun entries | 1,384 |
| unique supported surfaces | 1,383 |
| default `inflection=1` entries | 1,091 |
| explicit inflection entries | 256 |
| missing inflection patterns | 0 |
| supplemental metadata entries | 970 |
| supported surfaces covered by supplemental metadata | 12 |
| supplemental-only surfaces | 958 |
| explicit XML template rows | 162 |
| supplemental metadata lower bound | 14.4 KB |

This is a different runtime family from the previous locales. The dominant
Turkish noun pattern has no explicit XML forms, so a production pack should not
eagerly store case forms for every noun. It should use a rule-plus-exception
suffix model: infer ordinary vowel harmony and consonant-ending metadata from
the term surface, override with supplemental rows for foreign/exception terms,
and keep the 162 explicit XML template rows for irregular borrowed or historic
forms. The likely compact metadata bits are `vowelEnd`, `frontVowel`,
`roundedVowel`, `foreign`, `exception`, `hardConsonant`, `softConsonant`, and
`compound`. `TurkishSuffixPackSurveyJsonLoader` validates the generated report
contract for schema, locale, source hashes, supported Turkish grammar keys,
metadata bits, supplemental coverage arithmetic, pack-shape byte estimates,
sample shape, and immutability.

`tr_term_usage_example.json` and `tr_term_pack_example.json` exercise the first
runtime suffix-composition spike. For `locale=tr`, the scanner treats
`count=$count` plus `case=nominative|accusative|dative|locative|ablative` as a
compact suffix-rendering path. It requires `number`,
`turkishSuffix.vowelEnd`, `turkishSuffix.frontVowel`,
`turkishSuffix.roundedVowel`, `turkishSuffix.hardConsonant`, and
`forms.bare.singular`, but it does not require grammatical gender or eager
case/count rows. The compiler stores those suffix properties in term-row feature
bits, and the Java/Python renderers compose plural, accusative, dative,
locative, and ablative forms from the bare singular stem. The checked-in
`tr_compiled_suffix_pack_fixture.json` covers this compact shape and is loaded
by Java JSON/mmap tests.

The survey now also emits a `compositionPolicy` block for the production
boundary. It pins `inflection=1` as the only rule-safe dictionary path for this
spike, and chooses `explicit-template-forms` for mutation handling instead of
adding compact mutation bits. The explicit path is small: 60 case-template rows
at a 720-byte row estimate, including 9 consonant-mutation rows at 108 bytes
across 5 XML patterns. Those mutation samples include final `k` turning into
`ğ` for accusative/dative forms such as `çakmak` -> `çakmağı` and `çakmağa`.
The policy marks supplemental `exception`, `foreign`, and `soft-consonant` rows
as requiring explicit review/forms before runtime composition; that is 968 of
the 970 supplemental rows. This is the guardrail against silently producing
plausible but wrong Turkish case forms. Java loader tests now reject drift in
both `ruleSafeInflection` and the explicit-review flag set, so the compact
renderer cannot accidentally expand beyond ordinary `inflection=1` nouns or
start composing rows that still require explicit review.

The same generator now emits a renderer-ready explicit-template fixture for the
small XML-template path. `tr_compiled_explicit_template_pack_fixture.json`
exports 4 requested terms, 18 explicit form rows, a 351 byte string pool, and a
647 byte binary lower-bound estimate. Java JSON/mmap tests and Python smoke
render mutation and irregular examples such as `çakmak` -> `çakmağı`,
`gök` -> `göğe`, `amel` -> `aʼmal`, and regular explicit plural
`anahtar` -> `anahtarlar`. This keeps the production split concrete: ordinary
`inflection=1` nouns can use compact suffix composition, while XML mutation or
irregular rows become explicit compiled forms.

The automatic explicit-template export is now checked too. With
`--explicit-template-pack-limit 8` and no requested surfaces,
`tr_compiled_explicit_template_auto_pack_fixture.json` finds 71 base candidate
terms, exports 8, writes 31 form rows, and estimates 983 binary bytes. The
generator rejects dictionary rows already marked plural, dual, or non-base case
before they can become term bases; that prevents bad outputs such as doubled
plural forms from plural dictionary surfaces. Python smoke locks the
`non-singular-dictionary-surface` and `non-base-case-dictionary-surface`
diagnostic counts, and Java JSON/mmap tests render examples including
`baklava` -> `baklavayı`, `bahar` -> `bahâran`, and `cetvel` -> `cedâvil`.
The survey also records the full all-candidate explicit-template estimate after
the same base-surface filtering: 71 terms, 279 literal form rows, a 3.4 KB
string pool, and an 8.2 KB binary lower bound. The pretty JSON debug shape is
45 KB. That means the explicit XML-template exception path is small enough to
ship as rows; the larger Turkish decision remains the supplemental
rule-plus-exception metadata and authoring/review policy.

## Hindi Pack Survey

Hindi is the next default Unicode Inflection language after the earlier scoped
French/German/Spanish/Italian/Portuguese/Turkish pass. The materialized inputs
are cached outside the checkout:

| File | Size | SHA-256 |
| --- | ---: | --- |
| `dictionary_hi.lst` | 592,562 bytes | `c5b051852706d4c73188d532a072b4f6642b43ddd2efd506394f234428d93398` |
| `inflectional_hi.xml` | 635,966 bytes | `20b67cea017333ce11a041c9150268bb14643fb5185a7a4228f4e1079f1d4f0b` |
| `pronoun_hi.csv` | 2,433 bytes | `586446abbbdc6d8466912941ac2609d7bdac072af5c3422bf575a8715b1bf810` |

`hi_pack_survey.py` joins the dictionary, inflection XML, and Hindi pronoun
table:

```bash
python3 dev-docs/experiments/mf2-inflection/hi_pack_survey.py \
  --dictionary /Users/ja/.cache/mf2-inflection-data/dictionary_hi.lst \
  --inflectional /Users/ja/.cache/mf2-inflection-data/inflectional_hi.xml \
  --pronouns /Users/ja/code/inflection/inflection/resources/org/unicode/inflection/inflection/pronoun_hi.csv \
  --out common/src/test/resources/com/box/l10n/mojito/mf2/inflection/hi_pack_survey_fixture.json \
  --max-samples 8
```

The first survey found 7,533 dictionary entries, 2,936 noun/proper-noun term
entries, 3,485 noun/adjective agreement entries, 1,165 ambiguous agreement
surfaces, 258 inflection patterns, and zero missing patterns for the 225 used
agreement patterns. A direct-singular exact case-form pack would have 1,187
candidate terms, 3,898 form rows, and a 146 KB binary lower-bound estimate.
Pronouns add a small but important second table: 38 rows, including genitive
forms that depend on the referenced noun's gender and number (`मेरा`, `मेरे`,
`मेरी`, etc.).

The V0 implication is a case-form row family plus a pronoun agreement table:
Hindi product terms need `direct`, `oblique`, and `vocative` noun forms with
gender/number/animacy metadata; possessive pronouns need codependent
gender/number. This is smaller than German/Russian eager dictionary packs, but
more grammar-sensitive than the compact Romance article shells.
`HindiPackSurveyJsonLoader` now validates the generated fixture from Java,
including schema/locale pins, source provenance, feature domains, pattern slot
arithmetic, skipped-term closure, pronoun row counts, and case-form byte
estimates.

The same script can now emit a renderer-ready noun case-form fixture:

```bash
python3 dev-docs/experiments/mf2-inflection/hi_pack_survey.py \
  --dictionary /Users/ja/.cache/mf2-inflection-data/dictionary_hi.lst \
  --inflectional /Users/ja/.cache/mf2-inflection-data/inflectional_hi.xml \
  --pronouns /Users/ja/code/inflection/inflection/resources/org/unicode/inflection/inflection/pronoun_hi.csv \
  --out /private/tmp/hi-pack-survey.json \
  --compiled-case-form-pack-out common/src/test/resources/com/box/l10n/mojito/mf2/inflection/hi_compiled_case_form_pack_fixture.json \
  --case-form-pack-limit 3 \
  --case-form-surface अंगारा \
  --case-form-surface आँख \
  --case-form-surface आदमी \
  --max-samples 8
```

`hi_compiled_case_form_pack_fixture.json` contains 3 terms, 18 literal
`direct`/`oblique`/`vocative` forms, a 3.8 KB pretty JSON payload, and a 626 byte
binary lower-bound estimate before the mmap codec appends the locale string.
Java JSON/mmap tests and Python smoke render `अंगारे`, `आँखों`, and `आदमियो`
through the shared `:term case=... count=$count` path.

The script also emits the renderer-ready Hindi pronoun agreement table:

```bash
python3 dev-docs/experiments/mf2-inflection/hi_pack_survey.py \
  --dictionary /Users/ja/.cache/mf2-inflection-data/dictionary_hi.lst \
  --inflectional /Users/ja/.cache/mf2-inflection-data/inflectional_hi.xml \
  --pronouns /Users/ja/code/inflection/inflection/resources/org/unicode/inflection/inflection/pronoun_hi.csv \
  --out /private/tmp/hi-pack-survey.json \
  --pronoun-agreement-pack-out common/src/test/resources/com/box/l10n/mojito/mf2/inflection/hi_pronoun_agreement_pack_fixture.json \
  --max-samples 8
```

`hi_pronoun_agreement_pack_fixture.json` contains 38 rows, 30 unique output
forms, 20 genitive dependency rows, 14 number-invariant rows, the
`dependency-pronoun-agreement-rows-v0` pack shape, and a 730 byte binary
lower-bound estimate. `HindiPronounAgreementPackJsonLoader` validates
schema/locale pins, pack shape, source provenance, dependency rules, selector
uniqueness after `number=any` expansion, summary arithmetic, and immutable
fixture shape. The standalone renderer covers `मैं`, `हमारे`, `तुम`, and
`आपके`. This remains a separate pack from `CompiledTermPack`; the MF2-facing
integration composes the two packs when a pronoun form depends on another term's
gender and number.
For V0 production packaging, this JSON sidecar is the canonical shipping
artifact. It must stay physically separate from `compiled-term-pack/v0` JSON and
from M2IF term-pack rows; the `binaryLowerBoundBytes` summary is only a sizing
and provenance guard. If a native runtime needs Hindi dependency-aware rendering,
add a separate Hindi pronoun binary sidecar with its own magic/version and
decoder tests rather than adding locale-specific pronoun sections to the main
compiled-term-pack binary.
`hi_pronoun_agreement_parity.py` is the standalone non-Java parity check for
this auxiliary table shape:

```bash
python3 dev-docs/experiments/mf2-inflection/hi_pronoun_agreement_parity.py
```

It pins the JSON fixture length and SHA-256, pack shape, source provenance,
38-row summary, 730-byte lower-bound estimate, expanded selector uniqueness,
dependency rules, and sample selections for `मैं`, `हमारे`, `मेरी`, `तू`,
`तुम`, and `आपके`.

`HindiPronounTermRenderer` is the first MF2-facing integration for that
dependency:

```mf2
{$owner :term person=first case=genitive count=$ownerCount agreeWith=$item agreeWithCount=$itemCount}
{$item :term case=direct count=$itemCount}
```

`person` and `register` select the pronoun row, `count` selects the pronoun
number, `agreeWith` names the referenced term argument, and `agreeWithCount`
selects the referenced term's runtime number. The renderer resolves the
referenced term ID through the same closed-world term-argument map as ordinary
`:term` rendering, reads its compact gender metadata, and then calls the Hindi
pronoun agreement table. Java coverage now proves `मेरा अंगारा`, `मेरे अंगारे`,
`हमारे अंगारे`, `मेरी आँखें`, and `आपके अंगारे` from one message shape.
`TermRequirementValidator` now validates the same shape before rendering:
pronoun usages do not require their own term binding, but `agreeWith` must bind
to a known argument in closed-world mode, referenced terms must carry masculine
or feminine gender, and a referenced lexical number is required unless
`agreeWithCount` supplies the runtime number. The report also catches missing
second-person `register`, unexpected non-second-person `register`, unsupported
pronoun options, and malformed `agreeWith`/`agreeWithCount` variable references.
Agreement diagnostics now include `relatedArgument` so authoring UI can show
which referenced argument, such as `$item`, drives the missing agreement
metadata or binding.
The option names, allowed values, Hindi-locale marker detection, and
variable-reference parsing now live in `HindiPronounTermOptions`, so
`HindiPronounTermRenderer` and `TermRequirementValidator` share one option
contract instead of duplicating parser rules.

The shared runtime plumbing is now split out as well: `TermRenderRuntime`
handles MF2-style `{$variable}` pattern substitution, count-reference lookup,
numeric count validation, and one/other-to-singular/plural selection for both
`CompiledTermPackRenderer` and `HindiPronounTermRenderer`. Compiled renderer
failures now keep the low-level diagnostic and add the source `:term` argument,
bound term ID, and source span so preview callers can point back to the exact
message usage that failed.

`Mf2TermRenderer` is the current Java integration facade: ordinary compiled
term packs use the compiled renderer path, while Hindi case/pronoun packs add
the dependency-aware pronoun extension behind the same `renderMessage` entry
point with early locale mismatch diagnostics. The facade now also exposes
`renderBoundMessage` for the schema-gated
`mojito-mf2-inflection/message-term-binding-manifest/v0` manifest shape. That
runtime bridge accepts only row-level singleton bindings: missing bindings fail,
multi-term validation catalogs fail as ambiguous, and Hindi `agreeWith`
dependencies can bind the referenced term argument without inventing a term ID
for the pronoun usage itself. `TermBindingManifestValidator` exposes the same
renderability boundary as a report with `OK`, `MISSING`, `AMBIGUOUS`, `UNKNOWN`,
and `UNUSED` argument statuses, and direct Java catalogs now reject blank
message IDs, blank argument names, bindings for message IDs that are not present
in the source message set, and blank or duplicate term IDs. Product/editor code
can explain blocked rows before invoking the renderer, and direct Java callers
get the same structural manifest boundary as the schema-gated JSON loader.
`TermBindingManifestReportJsonWriter` writes that report as
`mojito-mf2-inflection/term-binding-report/v0` with lowercase status strings and
the same top-level diagnostics/messages/summary convention as the
term-requirement report. It sorts message keys, argument keys, and diagnostics
when serializing, so generated reports and REST responses stay stable across
equivalent manifest construction paths. The webapp now exposes that report at
`POST /api/glossaries/{glossaryId}/inflection-profiles/bindings/report`; the
endpoint uses the requested locale when the manifest omits one, rejects locale
mismatches, and keeps rendering separate from diagnostics. The frontend API
normalizes Spring error envelopes for the binding report/render endpoints and
admin profile preview/review/save paths, so manifest-structure failures such as
blank message IDs, blank argument names, blank term IDs, duplicate term IDs, and
profile provenance validation failures show the actual validation reason instead
of raw `Bad Request` JSON. Controller and frontend API tests pin that `400`
reason contract. Workbench now calls that endpoint for row-level source/target
`:term` usage when a glossary context is available, starting with empty term ID
arrays so the backend report exposes the missing binding state before
compiled-pack rendering is attempted.
Workbench labels missing/ambiguous report rows as unresolved MF2
argument-to-term-ID bindings, keeping them distinct from missing profile data.
The REST binding report also validates singleton term IDs against the locale
profile pack and returns `unknown` when a bound ID is not present, so a report
can fail before rendering even when cardinality is otherwise renderable.
`POST /api/glossaries/{glossaryId}/inflection-profiles/bindings/render` is the
matching render-preview boundary: it takes the same binding manifest plus
runtime variables, compiles the approved profile pack, reruns pack-aware
binding validation, and returns rendered messages keyed by manifest message ID.
Runtime variable names must be MF2-style identifiers and values must be
non-null before placeholder or count-reference rendering runs.
The shared frontend `buildMf2TermBindingManifest` utility now owns that row
manifest shape and accepts explicit per-argument or per-message term IDs.
Glossary match responses also expose the
canonical `termKey` / inflection term ID, which is enough for a picker or
explicit binding resolver to populate the manifest without fetching each term
detail.
Workbench now has that first explicit resolver: expanding an MF2 term details
panel fetches matched glossary terms and lets the user select a canonical term
ID per MF2 argument. No term ID is inferred automatically from the source text.
The same panel now collects runtime variables referenced by `:term` options,
for example `count=$count`, and calls the render-preview endpoint only after
all arguments are explicitly bound, the backend binding report is
diagnostic-free, and all runtime variables have nonblank values. Explicit term
and runtime selections are scoped to the Workbench row, glossary, repository,
and locale; expression edits prune removed arguments or variables without
clearing still-visible overrides.
The frontend requirement extractor now mirrors the Java Hindi pronoun boundary:
`agreeWith=$item` is treated as the related term argument to bind, not as a
runtime scalar input, while `count=$ownerCount` and
`agreeWithCount=$itemCount` remain explicit runtime variables. Workbench
therefore prompts for the referenced `$item` term ID plus count samples, without
asking translators to invent a term binding for the pronoun-owning `$owner`
argument.

## Arabic Survey And Fixture Target

Arabic is now the first materialized Unicode Inflection Semitic-family locale
after the French/German/Spanish/Italian/Portuguese/Russian/Turkish/Hindi pass:

```text
/Users/ja/.cache/mf2-inflection-data/dictionary_ar.lst
/Users/ja/.cache/mf2-inflection-data/inflectional_ar.xml
/Users/ja/code/inflection/inflection/resources/org/unicode/inflection/inflection/pronoun_ar.csv
```

The cached files match the checkout Git LFS pointers:

| File | Size | SHA-256 |
| --- | ---: | --- |
| `dictionary_ar.lst` | 893,500 B | `7d3ce5339d95517edbdc9c824f002e076351e6ced9bb67c7109ad3d486e3a77e` |
| `inflectional_ar.xml` | 1,599,608 B | `80329a41580409b839a8c768923a80c9e16d20a58dce505beddfa814fe18a3df` |

A quick token survey shows the first required grammar dimensions: singular,
plural, dual, nominative, accusative, genitive, masculine, feminine,
indefinite, construct, noun/proper-noun/adjective, and pronoun
nominative/accusative/genitive/reflexive rows.

`ar_pack_audit.py` is the first generator-side audit:

```bash
python3 dev-docs/experiments/mf2-inflection/ar_pack_audit.py \
  --dictionary /Users/ja/.cache/mf2-inflection-data/dictionary_ar.lst \
  --inflectional /Users/ja/.cache/mf2-inflection-data/inflectional_ar.xml \
  --pronouns /Users/ja/code/inflection/inflection/resources/org/unicode/inflection/inflection/pronoun_ar.csv \
  --out dev-docs/experiments/mf2-inflection/ar_pack_audit_fixture.json
```

The first audit parsed 11,023 dictionary entries, 1,629 noun/proper-noun term
entries, 6,080 noun/proper-noun/adjective agreement entries, 130 nominative
singular base candidates, 1,601 dual agreement entries, 412 construct agreement
entries, 3,841 fully specified case/number/gender/definiteness agreement rows,
604 XML patterns, no missing agreement patterns, 1,222 ambiguous agreement
surfaces, and 52 pronoun rows. Java now validates the generated audit fixture
contract before runtime work. The audit fixture also records the V0 pack policy:
Arabic should use a closed-world explicit-form pack for noun/proper-noun terms,
with `case`, `number`, and `definiteness` as runtime form keys. Dual is an
explicit `number=dual` option, not an implicit count rewrite. Construct is an
explicit form key. Gender, animacy, and part of speech stay metadata. The
policy now includes `reviewRequiredEvidence` counts for ambiguous surfaces
(1,222), construct rows (412), dual rows (1,601), verb-bearing term-pattern
slots (18), missing-or-ambiguous gender rows (891), and pronoun rows (52);
Java and smoke validation pin those numbers against the generated fixture.
Adjective agreement is out of V0, and pronoun attachment needs a later
Arabic-specific profile instead of reusing the Hindi dependency-aware renderer
directly. `ar_explicit_form_pack.py` now generates two closed-world compiled
Arabic fixtures. The default review-required fixture comes from
dictionary-observed `c2` "mother" rows. It
has one term and 14 literal form rows for singular/dual/plural,
nominative/accusative/genitive, and indefinite/construct cells where source rows
exist. Its `generationSummary` records the 18-row full-grid policy and review
diagnostics for the four missing cells: `indefinite.genitive.dual`,
`indefinite.genitive.plural`, `construct.genitive.dual`, and
`construct.genitive.plural`. Java and Python both validate it through the common
compiled-pack JSON, renderer, and binary/mmap paths using generic
`definiteness` and explicit `number` term options.
Its compiled `exportPolicy` marks the exported term as review-required rather
than automatic because the term is useful for present cells but incomplete:
`runtimeExport=closed-world-explicit-forms`, `compositionMode=explicit-form-rows-v0`,
`reviewRequiredTerms=1`, and `reviewRequiredReasons.missing-form-cell=1`.
The approved fixture uses dictionary-observed `17a` "message" rows for
`رسالة`, has all 18 required cells, reuses source rows where one row is tagged
for multiple cases or definiteness values, and renders checked samples such as
construct dual genitive `رسالتي` and indefinite plural genitive `رسائل`.
Its compiled `exportPolicy` has `automaticExportTerms=1` with no
review-required or blocked terms, and its pinned M2IF fixture is 1,794 bytes
with SHA-256
`cabd16085dc4b121afd4a0ab7a596990bb8d4f98d3a34b095fa247f802955e36`.

## Hebrew Survey Target

Hebrew is now materialized in the local cache:

```text
/Users/ja/.cache/mf2-inflection-data/dictionary_he.lst
/Users/ja/.cache/mf2-inflection-data/inflectional_he.xml
/Users/ja/code/inflection/inflection/resources/org/unicode/inflection/inflection/pronoun_he.csv
```

The cached files match the checkout Git LFS pointers:

| File | Size | SHA-256 |
| --- | ---: | --- |
| `dictionary_he.lst` | 9,728,222 B | `abece63d2c0f688d156e79860c9a3c97c51a30e07268616be67e4c7dc2d557cd` |
| `inflectional_he.xml` | 19,306,220 B | `991f04b14eda1986ad7521dc76d9ade201fd01989d8c66c50944608d5c19c037` |

`he_pack_audit.py` is the first generator-side audit:

```bash
python3 dev-docs/experiments/mf2-inflection/he_pack_audit.py \
  --dictionary /Users/ja/.cache/mf2-inflection-data/dictionary_he.lst \
  --inflectional /Users/ja/.cache/mf2-inflection-data/inflectional_he.xml \
  --pronouns /Users/ja/code/inflection/inflection/resources/org/unicode/inflection/inflection/pronoun_he.csv \
  --out dev-docs/experiments/mf2-inflection/he_pack_audit_fixture.json
```

The first audit parsed 147,765 dictionary entries, 31,039 noun/proper-noun term
entries, 49,836 noun/proper-noun/adjective agreement entries, 35,005 construct
agreement entries, 19 dual agreement entries, only 3 case-tagged agreement
entries, 5,299 XML patterns, no missing agreement patterns, 6,902 ambiguous
term surfaces, and 32 pronoun rows. This points to a different V0 than Arabic:
closed-world construct-state explicit forms with `number` and `definiteness`
as runtime options, gender/part-of-speech metadata, no noun case mode, no
dictionary-derived article generation, and pronoun attachment left for a later
Hebrew-specific profile. Java now validates the generated audit fixture
contract, including provenance labels, pattern slot coherence, pronoun
inventory counts, sample bounds, and the closed-world/no-case pack policy. The
Python smoke pipeline also regenerates the audit from the cached Unicode files
and compares it byte-for-byte against the checked-in fixture.

The audit now includes an `approvedFixtureCandidateSearch` block for the
complete Hebrew product-term sample requirement. With the current Unicode data,
there are zero clean noun/proper-noun inflection groups that contain all five
required rows (`bare.singular`, `bare.plural`, `construct.singular`,
`construct.plural`, and `construct.dual`). There is one clean group with only a
`construct.dual` row (`inflection=11d3`, `שיפוליי`) and 181 near-complete clean
groups with four observed keys, all missing `construct.dual`. That means the V0
runtime shape is valid, but automatic approved export for Hebrew needs reviewed
product rows rather than a dictionary-only approved fixture. The common Java
profile-pack path now covers that route with a Mojito-authored reviewed Hebrew
profile: an approved `he.reviewed.hand` row compiles to a runtime pack and
renders `bare.singular`, `bare.plural`, `construct.singular`,
`construct.plural`, and `construct.dual` forms through the same
`definiteness=construct`/`number=dual` selectors product messages use. The
webapp review path now supports that reviewed-row workflow directly: REST and
MCP review calls can replace `morphologyJson` and `formsJson` while approving a
row, clear structured missing-cell diagnostics, and keep reviewer provenance in
the same validated service transaction. MCP review summaries return parsed
morphology and form objects as well as diagnostics so missing cells can be
filled without re-uploading a full authoring pack. They now also include compact
diagnostic summaries that preserve `reason`/`code`, `formKey`, `messageId`,
`argument`, `relatedArgument`, missing keys, and source spans, so dependency
diagnostics such as Hindi pronoun agreement can be consumed without hand-parsing
raw diagnostic JSON. The admin inflection review
modal now exposes the same product-reviewed path with a structured form grid:
`Save profile` remains the full upsert route, while `Save and approve` sends the
edited grid through the narrow review route and clears missing-cell diagnostics
only after backend validation accepts the final row.

`he_construct_form_pack.py` now emits the first closed-world compiled Hebrew
fixture from dictionary-observed `inflection=95` "house" rows. The fixture has
one term, four literal form rows (`bare.singular`, `bare.plural`,
`construct.singular`, `construct.plural`), a 2,766 byte compact JSON size, and a
186 byte binary lower-bound estimate. Its `generationSummary` records the
missing `construct.dual` cell as a review diagnostic, and its compiled
`exportPolicy` marks the term as review-required with
`runtimeExport=closed-world-construct-state-explicit-forms`. The common Java
compiled JSON loader and binary/mmap codec render the fixture and preserve that
missing cell as a deterministic render failure.

## Malayalam Survey Target

Malayalam is now materialized in the local cache:

```text
/Users/ja/.cache/mf2-inflection-data/dictionary_ml.lst
/Users/ja/.cache/mf2-inflection-data/inflectional_ml.xml
/Users/ja/code/inflection/inflection/resources/org/unicode/inflection/inflection/pronoun_ml.csv
```

The cached files match the checkout Git LFS pointers:

| File | Size | SHA-256 |
| --- | ---: | --- |
| `dictionary_ml.lst` | 53,958,746 B | `6bda9371a2aa17c08328381e678b77e769269f4ee74749dd4f9e0bd5890cf59c` |
| `inflectional_ml.xml` | 613,479 B | `1868dab352ff2648c2ba495bc08a3877409eadf177f573817fd03ae07174b12f` |

`ml_pack_audit.py` is the first generator-side audit:

```bash
python3 dev-docs/experiments/mf2-inflection/ml_pack_audit.py \
  --dictionary /Users/ja/.cache/mf2-inflection-data/dictionary_ml.lst \
  --inflectional /Users/ja/.cache/mf2-inflection-data/inflectional_ml.xml \
  --pronouns /Users/ja/code/inflection/inflection/resources/org/unicode/inflection/inflection/pronoun_ml.csv \
  --out dev-docs/experiments/mf2-inflection/ml_pack_audit_fixture.json
```

The first audit parsed 748,662 dictionary entries, 730,439 noun/proper-noun term
entries, 733,006 noun/proper-noun/adjective agreement entries, 721,993
case-tagged agreement entries, 2,262 gender-tagged agreement entries, 363 XML
patterns, no missing agreement patterns, 3,707 ambiguous term surfaces, and 75
pronoun rows. The data is not a simple Hindi-style direct/oblique case profile:
it has broad noun coverage for nominative, accusative, dative, genitive,
locative, instrumental, sociative, and vocative. V0 should start with a
closed-world multi-case explicit-form pack using `case` and `number` runtime
options, keep gender/animacy as sparse metadata, and defer suffix composition
and a Malayalam-specific pronoun profile until after the first product-term
fixture. Java now validates the generated audit fixture contract, including
provenance labels, broad case coverage, sparse gender coverage, pattern slot
coherence, pronoun inventory counts, sample bounds, and the closed-world
explicit-case pack policy. The Python smoke pipeline also regenerates the audit
from the cached Unicode files and compares it byte-for-byte against the
checked-in fixture.

`ml_case_form_pack.py` now emits two closed-world compiled Malayalam fixtures.
The default review-required fixture is generated from dictionary-observed
`inflection=110` "disciple" rows: one term, 14 literal form rows for
singular/plural nominative, accusative, dative, genitive, instrumental,
locative, and sociative cells, a 5,377 byte compact JSON size, and a 903 byte
binary lower-bound estimate. Its `generationSummary` records missing
singular/plural vocative cells as review diagnostics, and its compiled
`exportPolicy` marks the term as review-required with
`runtimeExport=closed-world-multi-case-explicit-forms`.

The `--fixture approved` path emits `ml.case.father` from `inflection=cb` rows
with all 16 required singular/plural case cells, including vocative. The
approved fixture has a 5,483 byte compact JSON size, a 1,032 byte binary
lower-bound estimate, `automaticExportTerms=1`, `reviewRequiredTerms=0`, and a
2,015 byte pinned M2IF fixture with SHA-256
`56f3c79cf45c7a0aff0d5ecef82d035d94d11cce552b0f30c66a525b2124b0fe`. The
common Java compiled JSON loader and binary/mmap codec render both fixtures,
including `case=sociative` and approved `case=vocative`, while the
review-required fixture still preserves missing vocative as a deterministic
render failure.

## Nordic/Germanic Survey Target

Danish, Norwegian Bokmål, Dutch, and Swedish are now materialized in the local
cache:

```text
/Users/ja/.cache/mf2-inflection-data/dictionary_da.lst
/Users/ja/.cache/mf2-inflection-data/inflectional_da.xml
/Users/ja/.cache/mf2-inflection-data/dictionary_nb.lst
/Users/ja/.cache/mf2-inflection-data/inflectional_nb.xml
/Users/ja/.cache/mf2-inflection-data/dictionary_nl.lst
/Users/ja/.cache/mf2-inflection-data/inflectional_nl.xml
/Users/ja/.cache/mf2-inflection-data/dictionary_sv.lst
/Users/ja/.cache/mf2-inflection-data/inflectional_sv.xml
```

The cached files match the checkout Git LFS pointers:

| File | Size | SHA-256 |
| --- | ---: | --- |
| `dictionary_da.lst` | 38,557,469 B | `6226384ce104886c32e6a90432f8b41d96be0f9925eb10896095250d048b6c8b` |
| `inflectional_da.xml` | 2,149,623 B | `795f73dcbc1eea9db20fe77063952a023ed499c746d8ae3e003be001a8633851` |
| `dictionary_nb.lst` | 9,470,180 B | `a65316f4458ace28fe0959047590bae0e130bf8eaf973ed492bb3d955aff4d5b` |
| `inflectional_nb.xml` | 249,252 B | `e0b4331f6a9b1069058594173f37a226df4825fe487878f00fc73e38db98b401` |
| `dictionary_nl.lst` | 780,817 B | `9ccee2f7e50685c4239432b8d59ea5ba362f5bd5e3f529993a2ea7db5e2fa8c7` |
| `inflectional_nl.xml` | 2,381,180 B | `18a274c965f2d182248fc51171cf266458f91789ff29d65de6a277fb9a876d96` |
| `dictionary_sv.lst` | 19,379,822 B | `d986dc58f0ccf03c38abfb2d0af55b962bcf6131591831ab26373bb02986eeb8` |
| `inflectional_sv.xml` | 855,601 B | `46aea692f4c14586f44e7de7a796a1331685393fcb716c3cb7d8df50bf2048da` |

`germanic_nordic_pack_audit.py` is the first generator-side group audit:

```bash
python3 dev-docs/experiments/mf2-inflection/germanic_nordic_pack_audit.py \
  --out dev-docs/experiments/mf2-inflection/germanic_nordic_pack_audit_fixture.json
```

The audit confirms several runtime directions. Danish and Swedish both
have broad nominative/genitive, singular/plural, definite/indefinite, and
common/neuter coverage, so their V0 should start with closed-world explicit
genitive/definiteness form rows before attempting article or suffix
composition. Norwegian Bokmål has useful gender, number, and definiteness data
but almost no noun case coverage, so its first shape is a
definiteness/gender/number metadata pack. Dutch is smaller and has sparse noun
case/definiteness but a clear diminutive signal, so its next step is
metadata/diminutive validation rather than a broad case runtime.

The fixture parses 586,265 Danish rows, 162,215 Norwegian Bokmål rows, 13,093
Dutch rows, and 296,437 Swedish rows. Case-tagged agreement rows are high for
Danish (478,561) and Swedish (240,031), nearly absent for Norwegian Bokmål (5),
and sparse for Dutch (624). This made `sv` and `da` the first runtime slices for
genitive/definiteness, while `nb` is the cleaner metadata-first candidate.
`GermanicNordicPackAuditJsonLoader` now validates the checked-in common fixture
in Java: exact schema and provenance, locale order, Danish/Swedish explicit
genitive/definiteness policy evidence, Bokmal sparse-case metadata policy,
Dutch diminutive metadata policy, slot-attribute inventories, pronoun inventory
shape, immutable records, and stale-fixture failure cases. The smoke pipeline
also regenerates the group audit and compares it with the common fixture.

`sv_genitive_definiteness_pack.py` now emits the first Swedish compiled fixture
from dictionary-observed `bostad` and `chassi` rows:

```bash
python3 dev-docs/experiments/mf2-inflection/sv_genitive_definiteness_pack.py \
  --dictionary /Users/ja/.cache/mf2-inflection-data/dictionary_sv.lst \
  --out dev-docs/experiments/mf2-inflection/sv_compiled_genitive_definiteness_pack_fixture.json
```

The fixture has 2 terms, 20 literal form rows, a 6,438 byte compact JSON size,
and a 760 byte binary lower-bound estimate. It includes bare singular/plural
aliases plus explicit `indefinite|definite` x `nominative|genitive` x
`singular|plural` cells, keeps common/neuter gender metadata, and records the
exact Unicode dictionary source rows for every exported form. Java validates the
same fixture through the common compiled JSON loader, binary/mmap codec,
metadata index, and renderer. Its `generationSummary.exportPolicy` pins V0 as
closed-world explicit form rows and explicitly defers article selection,
definiteness-suffix composition, and genitive-suffix composition.

`da_genitive_definiteness_pack.py` now emits the matching Danish compiled
fixture from dictionary-observed `franskmand` and `barnebarn` rows:

```bash
python3 dev-docs/experiments/mf2-inflection/da_genitive_definiteness_pack.py \
  --dictionary /Users/ja/.cache/mf2-inflection-data/dictionary_da.lst \
  --out dev-docs/experiments/mf2-inflection/da_compiled_genitive_definiteness_pack_fixture.json
```

The fixture has 2 terms, 20 literal form rows, a 6,526 byte compact JSON size,
and an 821 byte binary lower-bound estimate. It exports the same bare
singular/plural aliases plus explicit `indefinite|definite` x
`nominative|genitive` x `singular|plural` cells, keeps common/neuter gender
metadata, and records the exact Unicode dictionary source rows for every
exported form. Java validates JSON loading, binary/mmap round trip, metadata
indexing, and rendering for `franskmænd`, `franskmændenes`, `barnebarnet`, and
`børnebørns`. Its export policy matches Swedish: automatic explicit-row export
for the sampled terms, no review-required or blocked terms, and compact
article/definiteness/genitive composition deferred beyond V0. The Java
`CompiledTermPack` now exposes the validated export policy, and
`encodeWithEmbeddedMetadata` preserves that policy alongside provenance while
row-only binary output keeps cold metadata decoupled. The pinned Danish/Swedish
M2IF hex fixtures, Arabic/Malayalam approved M2IF fixtures, Arabic/Hebrew/Malayalam review-required M2IF fixtures, and
non-Java decoder now validate the same optional export-policy metadata shape so
native readers do not silently drop packaging policy.

`nb_noun_metadata_pack.py` emits the first Norwegian Bokmål metadata-first
fixture:

```bash
python3 dev-docs/experiments/mf2-inflection/nb_noun_metadata_pack.py \
  --dictionary /Users/ja/.cache/mf2-inflection-data/dictionary_nb.lst \
  --out dev-docs/experiments/mf2-inflection/nb_noun_metadata_pack_fixture.json
```

The fixture records 114,535 metadata-candidate rows across 114,089 surfaces,
with only 1 case-tagged noun row. Its 12 exported sample rows cover masculine
`hund`, feminine `jente`, neuter `barn`, and the multi-gender plural `bøkene`.
The compact metadata estimate is 2.7 MB before trie/compression
(`1,321,686` string-pool bytes + `1,374,420` row bytes). Java validates schema,
provenance, feature-bit coherence, diagnostic counts, and byte-estimate
arithmetic. `barn` intentionally carries `multiple-numbers`, and `bøkene`
carries `multiple-genders` plus `multiple-inflections`, so product-term
authoring can require review instead of guessing.

`nl_noun_metadata_pack.py` emits the first Dutch metadata/diminutive fixture:

```bash
python3 dev-docs/experiments/mf2-inflection/nl_noun_metadata_pack.py \
  --dictionary /Users/ja/.cache/mf2-inflection-data/dictionary_nl.lst \
  --out dev-docs/experiments/mf2-inflection/nl_noun_metadata_pack_fixture.json
```

The fixture records 5,312 metadata-candidate rows across 5,308 surfaces, with
2,412 metadata-backed diminutive rows. Its 20 exported sample rows cover base
and diminutive pairs such as `boek`/`boekje`, `huis`/`huisje`, `man`/
`mannetje`, `kind`/`kindje`, and `vrouw`/`vrouwtje`. The compact metadata
estimate is only 114 KB before trie/compression (`50,752` string-pool bytes +
`63,744` row bytes). Java validates schema, provenance, feature-bit coherence,
diminutive counts, and byte-estimate arithmetic. The fixture also records why
Dutch stays metadata-only for now: only 12 term rows are tagged for
definiteness and 80 are tagged for case.

## Closed-World Term Pack Example

`fr_term_pack_example.json`, `de_term_pack_example.json`,
`es_term_pack_example.json`, `it_term_pack_example.json`, and
`pt_term_pack_example.json`, plus the Turkish suffix, Hindi compiled case,
Arabic explicit-form, Hebrew construct-state, Malayalam multi-case, and Danish
and Swedish genitive/definiteness fixtures, plus the Norwegian Bokmål and Dutch
metadata fixtures,
are readable examples of the deterministic runtime path. They show:

- a build-time requirement manifest for an MF2 `:term` usage;
- explicit French forms for an item term;
- two separate `livre` term IDs, one masculine for "book" and one feminine for
  "pound";
- explicit German article/case phrase forms for closed-world product terms.
- compact Spanish article composition from bare noun forms plus
  gender/number/stress metadata.
- explicit Arabic case/number/construct forms with `definiteness` and
  `number=dual` term options.
- explicit Hebrew construct-state forms with `definiteness=construct` and
  explicit singular/plural `number` options.
- explicit Malayalam multi-case forms with `case`, `number`, and the
  language-specific `sociative` case option.
- explicit Swedish genitive/definiteness forms with `case`, `definiteness`, and
  `number` term options.
- explicit Danish genitive/definiteness forms with `case`, `definiteness`, and
  `number` term options.
- Norwegian Bokmål gender/number/definiteness metadata validation without noun
  case rendering.
- Dutch gender/number/diminutive metadata validation without case or
  definiteness rendering.
- compact Italian article composition from bare noun forms plus
  gender/number/article-class metadata.
- compact Portuguese article/contraction composition from bare noun forms plus
  gender/number metadata.
- generated Russian compiled case-form fixtures for unambiguous dictionary
  terms.
- compact Turkish plural/case suffix composition from bare noun forms plus
  suffix metadata, with generated suffix-pack survey data for the larger
  rule-plus-exception pack shape.
- generated Turkish explicit XML-template fixtures for mutation/irregular forms
  that should not be guessed by compact suffix composition.
- automatic Turkish explicit-template export validation that rejects already
  plural or case-inflected dictionary surfaces as term bases.

That example is intentionally not compact. The design doc describes the runtime
export as a string pool plus term/form rows once the authoring shape is
validated.
Compiled runtime packs also reject blank string-pool values and empty form sets
at the record boundary, so imported or generated payloads cannot ship blank
term rows or rows that are structurally unrenderable.

## Mojito Authoring Direction

The current product-facing design uses the existing glossary workspace as the
term catalog. Source terms and translations remain ordinary glossary text units;
inflection adds a locale-specific sidecar keyed by glossary term metadata, with
structured morphology, selector-form rows, diagnostics, and provenance. The
sidecar is the input to build-time validation and compiled-pack export, while
explicit selector forms continue to override generated article/suffix
composition.

`TermInflectionProfilePackJsonLoader` is the first Java prototype of that
sidecar boundary. It loads profile JSON, converts profiles into
`TermRequirementValidator` terms for build checks, rejects approved profiles
with diagnostics, rejects compilation of profiles still needing review, and
emits deterministic `CompiledTermPack` rows for approved explicit forms. Profile
rows may also carry their own provenance object so generated prefill data,
manual review, and source dictionaries can be audited at row granularity.
The shared requirement JSON boundary now also rejects blank locales, term IDs,
term text, form keys, and form values before semantic validation runs.

The webapp persistence checkpoint now stores that same sidecar shape in
`glossary_term_inflection_profile` via `GlossaryTermInflectionProfileService`.
The service canonicalizes profile JSON, validates with the common loader, and
can compile approved glossary rows into renderer-ready term packs. Compiled
export now accepts only `APPROVED` rows, skips `DISABLED` rows, and fails with a
clear diagnostic for generated or review-needed rows. The service also returns a
reviewed compiled-export result with approved/skipped counts and skipped profile
details, and the compiled REST download exposes approved/skipped counts as
headers while keeping the body renderer-ready. It also derives a glossary
compiled `exportPolicy` and exposes runtime export/composition mode through REST
headers and the frontend preview API. Authoring export preserves per-profile
provenance and rolls valid row source metadata into the pack-level provenance
block; malformed row source metadata remains row-local instead of poisoning pack
provenance. `GlossaryWS` now exposes profile list/upsert routes, authoring
profile-pack JSON import/export, and compiled JSON export for a glossary/locale.
Import stores row provenance when present and falls back to the pack provenance,
including source-backed provenance, for older packs.
Import/export validation failures are pinned as REST `400` responses that keep
the service or loader validation reason. Import validates every profile against
glossary metadata before saving any row, so a later unknown term or stale source
text cannot leave a partially imported pack.
It also exposes a narrow profile review route that updates status, diagnostics,
and provenance without reposting morphology/forms, so UI authoring and MCP
review workflows can approve or disable generated rows through a stable action.
`glossary.inflection.review_profiles` is the first MCP surface on top of that
route: it lists actionable profiles with parsed diagnostics and can approve,
disable, or mark one term profile as review-needed through the same validating
service boundary. The admin glossary detail page now also includes the first UI
review card for this lifecycle: choose a locale, list non-approved or
diagnostic-bearing profiles, inspect parsed diagnostics such as missing Arabic
form cells, and approve or disable rows through the same REST review route. The
card can also preview the strict compiled export path and reports
approved/skipped counts, compiled runtime profile/form counts, payload size, or
the validation diagnostic that blocks export. The same UI now has a sidecar
editor for status, morphology, explicit form rows, diagnostics, and provenance;
the form rows generate the backend JSON payload while the other sidecar fields
remain raw JSON. Save uses the full REST upsert route and therefore the same
common loader validation as import/compiled export. Once compiled export preview
succeeds, the card can also run a small JavaScript render probe against the
returned compiled pack, binding `{$item}` to a selected term ID and parsing
scalar variables such as `count` from JSON.

The first JavaScript parity checkpoint is
`webapp/frontend/src/utils/mf2TermRenderer.ts`. It supports `:term` option
parsing, count/number-driven form-key selection, literal/pattern form
rendering, fail-closed diagnostics for missing terms, variables, or form cells,
and the same compact composition rules as the Java renderer for
Spanish/Italian/Portuguese articles plus Turkish suffix forms.
`mf2TermRenderer.test.ts` imports the Java compiled fixture JSON for Serbian,
Hindi, Arabic, German, Spanish, Italian, Portuguese, and Turkish smoke cases so
editor-preview behavior stays pinned to the backend fixture shape. The first
product consumer is the admin compiled-pack render probe. The first Workbench
checkpoint is `webapp/frontend/src/utils/mf2TermRequirements.ts`: it extracts
actual source/current-target `:term` usages, expands locale-aware requirements
for the supported renderer families, and shows a compact row summary in
Workbench. It also mirrors Java's unsupported-locale runtime-form requirement
for option-bearing `:term` usages in metadata/profile-only locales, and the
backend binding report mirrors that policy as a first-class binding status. The
common Java usage-catalog loader now expects those bindings in
the explicit `mojito-mf2-inflection/message-term-binding-manifest/v0` schema
and rejects bindings for messages or `:term` arguments that are not present in
the source. The matching frontend builder keeps Workbench's unresolved manifest
and explicit term-ID manifest on one tested path. Full Workbench rendering now
has a backend render-preview endpoint, explicit term-ID selection, and row-level
runtime variable inputs before Workbench calls render preview. Lexical glossary
matches can provide canonical term IDs, but not the argument-to-term relationship
on their own. The UI reports that condition as an unresolved binding, not as a
term-profile quality failure, and reports bound-but-unknown term IDs as invalid
glossary term IDs.

## MF2 Term Requirement Scanner

`mf2_term_requirements.py` scans a deliberately small MF2-like `:term` subset
from a `mojito-mf2-inflection/message-term-binding-manifest/v0` catalog and
expands each usage into locale requirements. It can also validate those
requirements against the readable term-pack example:

```bash
python3 dev-docs/experiments/mf2-inflection/mf2_term_requirements.py \
  --catalog dev-docs/experiments/mf2-inflection/fr_term_usage_example.json \
  --term-pack dev-docs/experiments/mf2-inflection/fr_term_pack_example.json \
  --out /private/tmp/fr-term-requirements-report.json
```

The current scanner recognizes placeholders like:

```mf2
{$item :term article=definite count=$count}
```

It also accepts closed-set case options for explicit case-form terms:

```mf2
{$item :term case=accusative count=$count}
```

For the current subset it accepts the option vocabulary pinned in
`term_usage_option_contract_fixture.json`: `article=definite|indefinite`,
`case=ablative|accusative|dative|direct|genitive|instrumental|locative|nominative|oblique|prepositional|sociative|vocative`,
`number=dual|plural|singular`, `definiteness=construct|definite|indefinite`,
`preposition=de|em|por`, and `count=$variable`. Unsupported options,
duplicate option keys, blank option values, unsupported
case/article/preposition values, literal count values, `preposition` without
`article`, `preposition` with `case`, and unsupported preposition/article
combinations fail before requirement generation. Java and Python smoke tests
both compare against the shared option-contract fixture, so adding a case such
as Malayalam `sociative` cannot silently drift between runtimes.

and expands them to requirements such as:

```text
partOfSpeech=noun
gender
number
elision
forms.definite.singular
forms.definite.plural
forms.count.one
forms.count.other
```

For Spanish article usage, the locale profile now takes the compact path
instead of requiring eager article phrase rows. For:

```mf2
{$item :term article=definite count=$count}
```

with `locale=es`, the scanner requires:

```text
partOfSpeech=noun
gender
number
stress
forms.bare.singular
forms.bare.plural
```

The compiler carries `stressed` in the term-row feature bits, and the Java/Python
renderers compose `el agua`, `las aguas`, `la abeja`, and `unas abejas` from
bare forms plus gender/number/stress metadata. If an explicit
`forms.definite.singular` or `forms.indefinite.singular` row exists, it still
wins over composition. The smoke pipeline regenerates
`es_compiled_article_pack_fixture.json` from the readable Spanish examples and
compares it to the checked-in test fixture, so fixture drift fails loudly.

Italian article usage follows the same compact path with one extra metadata
field. With `locale=it`, the scanner requires:

```text
partOfSpeech=noun
gender
number
articleClass
forms.bare.singular
forms.bare.plural
```

The compiler carries `articleClass` in the term-row feature bits, and the
Java/Python renderers compose `lo gnomo`, `gli gnomi`, `il libro`, `un'acqua`,
and `un'ape` from bare forms plus gender/number/article-class metadata. The
smoke pipeline regenerates `it_compiled_article_pack_fixture.json` from the
readable Italian examples and compares it to the checked-in fixture.

Portuguese article and contraction usage follows the compact path without extra
metadata beyond gender and number. With `locale=pt`, both article-only usage:

```mf2
{$item :term article=definite count=$count}
```

and contraction usage:

```mf2
{$item :term preposition=de article=definite count=$count}
```

require:

```text
partOfSpeech=noun
gender
number
forms.bare.singular
forms.bare.plural
```

The compiler stores only bare rows, and the Java/Python renderers compose
`o campo`, `a casa`, `um campo`, `umas casas`, `do campo`, `das casas`,
`no campo`, `num campo`, and `pelos campos` from gender/number metadata. The
smoke pipeline regenerates `pt_compiled_agreement_pack_fixture.json` from the
readable Portuguese examples and compares it to the checked-in fixture.

Turkish suffix usage is the first compact case-inflection path. With
`locale=tr`, both count-only usage:

```mf2
{$item :term count=$count}
```

and supported case usage:

```mf2
{$item :term case=accusative count=$count}
```

require:

```text
partOfSpeech=noun
number
turkishSuffix.vowelEnd
turkishSuffix.frontVowel
turkishSuffix.roundedVowel
turkishSuffix.hardConsonant
forms.bare.singular
```

The compiler stores only bare singular rows for the checked-in Turkish fixture,
and the Java/Python renderers compose examples such as `evi`, `okulu`,
`arabayı`, `gülü`, `parkta`, `parktan`, `evleri`, `okullarda`, and `güller`.
This spike intentionally covers ordinary vowel harmony and hard-consonant
locative/ablative behavior; consonant mutation and exception rows remain in the
larger rule-plus-exception pack design.

For Turkish terms generated from explicit XML templates, the renderer path is
the generic compiled form lookup. The generated explicit-template fixture stores
literal rows like `accusative.singular=çakmağı`, `dative.singular=göğe`,
`count.other=aʼmal`, and `count.other=anahtarlar`; the Java and Python renderers
select those rows directly instead of invoking suffix composition.

For locales without a compact case profile, `case=accusative count=$count`
requires explicit compiled forms:

```text
partOfSpeech=noun
gender
number
forms.accusative.singular
forms.accusative.plural
forms.count.one
forms.count.other
```

This is not a production MF2 parser. It is a generator-side experiment for the
build-time contract: messages declare `:term` usage, locale profiles expand that
usage into concrete term metadata/forms, and closed-world catalogs fail before
runtime when the term pack is incomplete. The scanner defaults to
`--mode closed-world`, where a `:term` usage without bound term IDs is a
diagnostic; use `--mode open-world` only when validating a dynamic runtime
argument contract instead of a fixed product-term catalog.
Top-level diagnostics include the same `span` offsets as the corresponding
`termUsages` entry so editor integrations can highlight the failing expression.

## Term Pack Compiler

`mf2_term_pack_compile.py` compiles the readable term pack plus the requirement
report into a row-oriented JSON shape that mirrors the intended compact runtime
pack:

```bash
python3 dev-docs/experiments/mf2-inflection/mf2_term_pack_compile.py \
  --term-pack dev-docs/experiments/mf2-inflection/fr_term_pack_example.json \
  --requirements-report /private/tmp/fr-term-requirements-report.json \
  --out /private/tmp/fr-compiled-term-pack.json
```

The current example compiler output has:

| Metric | Count |
| --- | ---: |
| strings | 24 |
| terms | 3 |
| form sets | 3 |
| form rows | 14 |
| diagnostics | 0 |
| compact JSON bytes | 1,463 |
| estimated binary lower bound | 573 |

The binary estimate is intentionally simple: UTF-8 string pool bytes plus fixed
term rows, form rows, and message binding references. It is a direction check,
not a final file format, but the row widths now match the Java compiled-pack
record contract.

Compiled packs with non-empty `diagnostics` are tooling artifacts, not renderable
runtime packs. The Java loader and Python renderer reject them before formatting.
The Java loader also preserves the compiled pack schema, locale, Unicode source
provenance, and binary lower-bound estimates so fixture tests cover the same
metadata the production runtime pack needs.

## Compiled Binary Layout Sketch

The next runtime shape should be memory-mappable and little-endian so Java,
Rust, Swift, and JavaScript/WASM readers can share the same generator output.
The first proposed layout is:

```text
header:
  magic[4] = "M2IF"
  version u16 = 0
  flags u16
  localeString u32
  stringCount u32
  termCount u32
  formSetCount u32
  formRowCount u32
  bindingRowCount u32
  section directory offsets/lengths

strings:
  utf8 bytes with NUL terminators
  stringOffset[u32] table, length stringCount + 1

terms:
  idString u32
  textString u32
  featureBits u32
  senseString u32, or 0xffffffff
  formSetIndex u32

formSets:
  termString u32
  firstFormRow u32
  formRowCount u32

formRows:
  keyString u32
  valueString u32
  flags u32, bit 0 = pattern

bindings, reserved for a later runtime section:
  messageString u32
  argumentString u32
  termString u32
```

The mmap reader should validate magic/version, section bounds, sorted or
deduplicated term IDs, string indexes, form-set ownership, duplicate form keys
per term, and that non-empty diagnostics were excluded before packing. JSON
provenance can stay as a sidecar or be embedded as a string-table metadata
section; the runtime hot path only needs the row tables above. The generic JSON
compiler may keep message bindings as metadata, but the current mmap lower-bound
estimate and Java codec treat the bindings section as empty.

`CompiledTermPackBinaryCodec` is the first Java validation of this layout. It
encodes the current compiled-pack model to bytes, decodes from `ByteBuffer`
without requiring a heap-backed array, round-trips the generated Serbian
fixture, and rejects corrupted magic, versions, section lengths, section bounds,
form-row flags, malformed UTF-8, out-of-bounds string indexes, and non-empty
reserved binding rows.

## Term Pack Renderer

`mf2_term_pack_render.py` is a tiny proof renderer over the compiled row shape.
It implements only the experiment subset:

- `article=definite|indefinite` selects singular/plural article forms;
- `preposition=de|em|por` composes the supported Portuguese article
  contractions when `locale=pt`;
- `case=accusative` and the other supported cases select singular/plural case
  forms such as `accusative.singular`;
- `article=definite case=accusative` is reserved for article/case/number form
  keys such as `definite.accusative.singular`;
- `count=$count` selects plural when the provided count is not `1`;
- count-only terms select `count.one` or `count.other`;
- pattern values substitute simple `{$name}` placeholders.

Example commands:

```bash
python3 dev-docs/experiments/mf2-inflection/mf2_term_pack_render.py \
  --catalog dev-docs/experiments/mf2-inflection/fr_term_usage_example.json \
  --compiled-pack /private/tmp/fr-compiled-term-pack.json \
  --message inventory.deleted \
  --term item=item.iron_sword \
  --arg count=1
```

```text
Vous avez supprimé l'épée de fer.
```

The ambiguous `livre` cases render differently because the term IDs are
different:

```bash
python3 dev-docs/experiments/mf2-inflection/mf2_term_pack_render.py \
  --catalog dev-docs/experiments/mf2-inflection/fr_term_usage_example.json \
  --compiled-pack /private/tmp/fr-compiled-term-pack.json \
  --message inventory.deleted \
  --term item=concept.book \
  --arg count=1
```

```text
Vous avez supprimé le livre.
```

```bash
python3 dev-docs/experiments/mf2-inflection/mf2_term_pack_render.py \
  --catalog dev-docs/experiments/mf2-inflection/fr_term_usage_example.json \
  --compiled-pack /private/tmp/fr-compiled-term-pack.json \
  --message inventory.deleted \
  --term item=unit.pound \
  --arg count=1
```

```text
Vous avez supprimé la livre.
```
