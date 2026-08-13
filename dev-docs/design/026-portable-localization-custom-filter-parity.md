# Portable localization custom-filter parity

The portable converters must preserve intentional Mojito workflow behavior, not
merely match platform parsers or upstream Okapi. The original differential suite
passes no filter options and only checks source extraction. A separate configured
workflow suite now executes the actual customized Android, FormatJS, Chrome,
Evolve, and Apple filters with their real options and checks message IDs, source
text, comments, usages, and exact translation-memory MD5 inputs. Shared Java/Rust
fixtures also verify output cleanup, original encodings, import-only target
notes, and locale-owned plural completion. Nine manifest-declared comparisons
run the actual customized Android, production-routed `.stringsdict`, and PO
filters with `CopyFormsOnImport` and verify their emitted plural-category sets,
including category-owned hidden Foundation printf conversions. They also prove
that the production-routed Apple filter silently drops the first of two
independent selectors, while the portable implementation preserves both, and
that Russian and Polish PO import incorrectly copy `few` to `other`, while the
portable implementation correctly derives `other` from `many`. Slovenian's
actual customized filter also mislabels the native `two` and `few` branches,
inventing `many`; the portable implementation preserves the platform's real
`one`, `two`, `few`, and `other` ownership. Additional standalone fixtures
verify uppercase language, uppercase/lowercase region, and underscore-versus-
hyphen locale normalization without claiming an actual configured-filter
differential.

## Existing filter options

| Format or workflow | Option | Current behavior | Portable status |
| --- | --- | --- | --- |
| Android XML | `oldEscaping` | Select legacy Android source and output escaping. | Deliberately rejected when true; inaccurate legacy escaping cannot silently replace compiler-correct AAPT2 values. |
| Android XML | `removeDescription` | Remove `description` attributes from localized output. | Implemented for every native resource and item independently in Java and Rust; the existing postprocessor incorrectly handles only top-level strings and plural groups. |
| Android XML | `postProcessIndent` | Reformat processed XML using the requested indentation width. | Implemented, bounded to 0–32 spaces. |
| Android XML | `postRemoveTranslatableFalse` | Remove protected `translatable="false"` elements from localized output. | Implemented, including protected arrays, plurals, and generic resource elements. |
| Android XML | `postEmptyResourcesToEmptyFile` | Return an empty file when no localizable resource remains. | Implemented. |
| JSON | `useFullKeyPath` | Select full slash-separated paths versus leaf-key identities. | Implemented for generic configured JSON. |
| JSON | `extractAllPairs` | Select all scalar pairs or only paths matching `exceptions`. | Implemented. |
| JSON | `exceptions` | Match the JSON key/path patterns selected when extraction is restricted. | Implemented with compiled, validated regular expressions. |
| JSON | `codeFinderData` | Configure protected inline-code matching. | Implemented for validated version-one numbered regular-expression rules, preserving each original protected spelling beside its stable code ID. |
| JSON | `noteKeyPattern` | Read translator notes from matching sibling keys. | Implemented for actual FormatJS, Chrome, and Evolve configurations. |
| JSON | `usagesKeyPattern` | Read usage locations from matching sibling keys. | Implemented. |
| JSON | `filePositionPathKeyPattern` | Read a usage's source-file path from a configured key. | Implemented. |
| JSON | `filePositionLineKeyPattern` | Read the source line and append it to the usage. | Implemented. |
| JSON | `filePositionColKeyPattern` | Read the source column and append it to the usage. | Implemented. |
| JSON | `noteKeepOrReplace` | Carry or replace contextual notes across nested objects. | Implemented, including inherited Evolve preview URLs. |
| JSON | `usagesKeepOrReplace` | Carry or replace contextual usages across nested objects. | Implemented, including inherited Evolve preview URLs. |
| JSON | `removeKeySuffix` | Remove a suffix such as `/defaultMessage` from extracted identities. | Implemented. |
| JSON | `convertToHtmlCodes` | Convert configured protected inline codes into reversible markup. | Implemented as exact legacy `<br id='pN'/>` extraction plus lossless reverse replacement; unknown, repeated, and missing codes fail closed. |
| Apple `.strings` | `removeComment` | Remove comments from localized output. | Implemented for block and single-line comments without treating comment-like quoted translation content as a real comment. |
| YAML | `useFullKeyPath` | Select full YAML key-path identities. | Out of scope until YAML has a portable format contract. |
| YAML | `extractAllPairs` | Select every scalar or only configured exceptions. | Out of scope until YAML has a portable format contract. |
| YAML | `exceptions` | Select configured YAML key/path patterns. | Out of scope until YAML has a portable format contract. |
| HTML | `processImageUrls` | Expose image URLs as protected adaptation units. | Out of scope until HTML has a portable format contract. |
| HTML | `emptyAndNbspNotTranslatable` | Suppress empty and nonbreaking-space-only HTML units. | Out of scope until HTML has a portable format contract. |
| Translation import | `targetComment` | Attach the configured note while importing translations. | Implemented only by explicit Java/Rust import APIs as `metadata.mojitoTargetComment`; source descriptions and TM source identity remain unchanged. |

Options are supplied as `key=value`; duplicate keys use the final value. Existing
filters silently coerce every Boolean spelling except case-insensitive `true`
to `false`, ignore malformed options without `=`, and ignore unrecognized keys;
JSON's `Pattern.compile` accepts Java-only constructs such as lookahead and
backreferences. Portable Java/Rust intentionally share a stricter safe contract:
Boolean values must explicitly be `true` or `false`, malformed/unknown options
fail closed, regexes must be accepted by both implementations, Android
indentation is bounded to 0–32 spaces, and unsupported escaping is rejected.
This means previously ignored typos now produce explicit errors, and Java-only
regex constructs are not portable. Actual configured Mojito production defaults
remain covered by direct custom-filter differentials. The complete three-rule
inline-code configuration already exercised by Mojito's existing extraction test
is compared directly against the customized filter; duplicate numbered rules
and duplicate rule counts retain their final value rather than incorrectly
counting repeated definitions or silently dropping protected placeholders.

Configured inline-code rules are compiled and ordered once for each parsed option
set, then reused for every extracted message. A warmed 20,000-message,
three-rule local Java workload fell from 77–81 ms to 59–66 ms after eliminating
repeated rule-count parsing and per-message rule-list allocation; Rust applies
the same parse-once policy independently. These are local workload timings, not
production throughput claims.

## Required non-option behavior

- The shared extraction pipeline suppresses any text unit whose effective
  translator comment contains the case-sensitive literal `DO NOT TRANSLATE`.
- Android's customized filter concatenates preceding XML comments, unless a
  `description` attribute already supplies the note. AAPT2 itself owns only
  the latest comment; platform-native parsing and legacy-note compatibility
  must therefore be separate explicit policies.
- Apple `.strings` comments containing multiline `<locations>...</locations>`
  expose unique ordered usage locations and remove that block while preserving
  all remaining comment whitespace; that whitespace affects legacy TM MD5.
- GNU gettext `#:` references become usages on every synthesized plural form.
  The existing native parser already preserves these references in metadata.
- Java properties declarations may repeat the same key when their value and
  translator description are identical. Portable extraction exposes one stable
  translation-memory identity, updates every original declaration on output,
  and still rejects conflicting duplicate values or descriptions.
- Android, JSON, Apple `.strings`, and GNU PO remove untranslated entries when
  inheritance mode is `REMOVE_UNTRANSLATED`; Android also removes plural groups
  lacking a translated `other` branch. Workflow-aware Java/Rust localization now
  implements those policies; ordinary source-template rendering remains lossless
  and leaves missing translations untouched.
- AAPT2 itself accepts and retains Android plural bags with no `other`, but a
  canonical ICU/FormatJS plural cannot safely represent them. Independent Java
  and Rust readers/writers reject these bags instead of inventing a fallback;
  the actual compiled AAPT2 category snapshot documents that intentional
  incompatibility.
- GNU PO translated-output parity is also verified against the actual customized
  filter, its skeleton writer, and its real inheritance postprocessor: the
  portable output exactly matches the retained header, translated entry,
  removed entry/comments, spacing, and trailing newline, while preserving native
  LF or CRLF source-template bytes. This is a successful
  workflow equivalence check, not an inferred parser-only comparison. A separate
  actual-filter differential proves legacy cleanup also discards real translated
  entries when its placeholder occurs in a translation, translator comment, or
  source ID; portable cleanup recognizes only exact `msgstr` placeholder values,
  including split continuation lines, complete multiline-context ownership, and
  individually indexed plural branches.
- Android's existing `postEmptyResourcesToEmptyFile=true` postprocessor treats
  valid generic `<item type="string">` and heterogeneous `<array>` resources as
  nonexistent, returning an empty file even though AAPT2 owns translatable
  values. The actual customized filter, skeleton writer, and postprocessor
  reproduce that loss; portable Java/Rust retain both native resource types and
  apply the same deterministic DOM-compatible normalized attribute ordering.
- Android's existing `postRemoveTranslatableFalse=true` postprocessor recognizes
  only the exact lowercase, unpadded spelling `false`, while AAPT2 also accepts
  `False`, `FALSE`, and ASCII-padded equivalents. Direct customized-filter and
  skeleton-writer output proves protected title-case, uppercase, and padded
  values leak into the generated translation; portable Java/Rust remove every
  platform-accepted false spelling.
- Android's existing `removeDescription=true` postprocessor removes translator
  notes from top-level `<string>` and `<plurals>` elements but leaves descriptions
  on generic strings, array groups, array items, and individual plural items.
  The original fixture compiles with AAPT2, and direct customized-filter,
  skeleton-writer, and postprocessor output proves each leaked note; portable
  Java/Rust remove `description` consistently from every native resource node.
  AAPT2 verifies both original and localized Android workflow resources on
  every complete conformance run.
- Android's existing cleanup also removes deliberately translated scalar,
  and plural-item values exactly equal to its private untranslated marker.
  The customized filter, skeleton writer, and production-routed postprocessor
  reproduce both losses. Portable Java/Rust also preserve a separately
  quoted marker translation and choose collision-free private
  cleanup markers, preserve the actual translated values, and still remove
  genuinely missing entries; AAPT2 compiles the exact retained translations.
  Portable untranslated cleanup never removes an intrinsically protected or
  `DO NOT TRANSLATE`-suppressed resource: its literal value, including the
  reserved marker, remains in the output unless `postRemoveTranslatableFalse`
  explicitly requests protected-resource removal. These protected cases are
  native AAPT2 contracts, not actual customized-postprocessor differentials:
  Okapi retains the untouched values with different source escaping.
  Temporary Android cleanup markers avoid both supplied translation values and
  every literal already present in the source template, so protected or
  suppressed collision variants cannot be deleted accidentally.
- AAPT2 trims ASCII whitespace around plural quantities, including XML numeric
  character references for tabs and newlines. The existing customized Android
  filter rejects these valid resources outright; portable Java/Rust preserve
  their exact source attribute spelling, map source-template slots to canonical
  categories, and retain the translated padded `other` branch during inherited
  untranslated-output cleanup.
- AAPT2 also resolves numeric character references inside valid BMP Unicode
  resource names and generic resource-type attributes before assigning native
  identities. Portable source templates independently decode those attributes
  for slot matching while retaining every exact original escaped UTF-8 byte
  during translation.
- Apple `.strings` has an existing real translated-output bug when both
  `removeComment=true` and `REMOVE_UNTRANSLATED` are active: its customized
  postprocessor removes untranslated entries, then applies comment removal to
  the original unfiltered content, restoring the untranslated placeholder.
  Direct execution of the actual customized filter, skeleton writer, and
  production-routed postprocessor verifies the bug; portable Java/Rust
  correctly apply both policies to the same output.
- Apple `.strings` also permits literal `/*...*/` and `//` text inside quoted
  translations. The customized `removeComment=true` postprocessor applies its
  block-comment expression to complete file content and silently corrupts those
  values; actual Foundation accepts them unchanged. Portable Java/Rust remove
  only structural comments, including comments after the final declaration,
  and preserve every double-quoted or native single-quoted translation byte;
  bare-key declarations retain their own source spelling, and native Unicode
  line/paragraph separators still terminate single-line comments. Inherited
  untranslated cleanup also removes single-quoted and bare-key entries while
  safely materializing translated key-only shortcut declarations. The customized
  cleanup also drops real translations containing its placeholder as a
  substring or exactly equal to it. Portable Java/Rust track each missing
  original translation by stable key; they retain both deliberately translated
  exact placeholders and legitimate translated marker text, even when that
  text imitates a complete
  single-quoted untranslated declaration, including an apparent preceding block
  comment, inside a double-quoted translation. Cleanup scans OpenStep
  quote/comment state once across all candidate entries, so embedded fake
  declarations cannot remove real translated content. XML property-list
  `.strings` additionally remove genuine XML comments and complete untranslated
  key/value pairs while preserving their original DTD, processing instructions,
  entities, translated XML escaping, and every untouched source byte. Each
  original and localized Apple workflow dictionary, including CDATA-owned keys
  and values, literal
  comment-like CDATA text, spoofed untranslated XML markup embedded inside a
  translated CDATA value, and intervening processing instructions, is parsed
  by real Foundation, which verifies exact translated keys and values. Cleanup
  validates the dictionary structurally and recognizes only genuine direct
  `<dict>` key/value children; fake XML text inside CDATA cannot delete a real
  translation. XML property-list cleanup applies the same stable-key ownership
  rule, preserving explicitly translated exact placeholders while removing
  only genuinely untranslated dictionary entries.
- JSON's existing translated-output postprocessor removes untranslated array
  objects by ascending original index; adjacent removals shift later entries,
  leaving an untranslated descriptor in the exported catalog. Direct execution
  of the actual customized filter, skeleton writer, and JSON postprocessor
  reproduces the leak and proves it also removes a deliberately translated
  literal placeholder value from a top-level descriptor. Portable Java and
  Rust distinguish missing translations by their original stable key/path
  identity, remove every untranslated array object without index-shift loss,
  and preserve explicitly translated literal placeholders in top-level objects,
  arrays, nested arrays, and arrays nested inside surviving array entries.
  Cleanup processes arrays from their original final index backward so deeper
  descendant paths never drift after earlier siblings disappear.
- GNU gettext cleanup also cannot infer translation ownership from an output
  string: an explicit translation may intentionally equal the legacy
  untranslated placeholder. Portable Java/Rust independently select a private
  collision-free temporary marker for genuinely missing translations, preserve
  explicit marker-valued translations, and remove every incomplete untranslated
  scalar or plural group. Real `msgfmt` verifies the retained native MO values;
  the actual customized postprocessor instead deletes the explicit translation.
- Explicit Java/Rust import APIs support `CopyFormsOnImport` for Android, PO,
  and `.stringsdict`, independently synthesizing locale-required plural forms.
  Java reuses Mojito's existing `PluralRuleService`, including its intentional
  French, Portuguese, Hebrew, and Maltese compatibility overrides; Rust applies
  the same overrides above Mojito's existing MF2 cardinal plural rules. Android
  and Apple clone `other`; PO preserves actual gettext index categories and
  applies the customized Irish, Czech/Slovak, Lithuanian,
  Russian/Ukrainian/Belarusian,
  Polish, and Slovenian extra-copy rules. Category-owned Android metadata and
  Apple native `applePluralRules` and category-owned hidden Foundation printf
  conversions are copied with each branch, including every independently owned
  selector in multi-variable Apple dictionaries and separately owned iPhone/Mac
  device plural trees. Target locale language, script, and region casing plus
  hyphen/underscore separators are normalized consistently across Java and
  Rust. Real customized Android, production-routed `.stringsdict`, and PO
  filter executions verify the resulting category sets. Normal source parsing
  remains unchanged.
- The actual production-routed `.stringsdict` filter emits only the last plural
  variable from a multi-variable message; both portable implementations preserve
  every selector, including selector-owned hidden `%n` argument metadata. This
  is an intentional native-correct improvement, not missing legacy parity.
- Apple localization patterns can reference the same plural selector multiple
  times. Import completes the locale-required categories in every occurrence,
  not only the first, while retaining the one source-owned native plural rule.
- Apple device-specific dictionaries retain each device's own native `other`
  translation when synthesizing additional plural categories; copying only the
  selected device's canonical branches would silently overwrite Mac-specific
  translations during normalized output generation.
- Locale-aware `.stringsdict` translated output retains only target-language
  plural categories, using existing `PluralRuleService` policy in Java and the
  equivalent policy in Rust. Removal follows verified source-owned plural slots,
  preserves every unrelated XML byte, and handles independent plural selectors;
  native canonical parsing remains locale-neutral.
- Manifest-owned import round trips independently write and reparse completed
  single-selector, repeated-selector, multi-selector, hidden-conversion, and
  device-owned `.stringsdict` catalogs in Java and Rust; every synthesized
  category, protected conversion, and device translation must survive output.
- Existing Russian and Polish PO import materialize `other` from the `few`
  translation, despite each format's native `many` translation being the
  correct CLDR fallback. Manifest-owned differential evidence records both
  actual incorrect legacy values and their portable `many`-derived replacements.
- Existing Slovenian PO import mislabels the native second form `two` as `few`,
  shifts native `few` to an invented `many`, and emits `one`/`few`/`many`/
  `other` instead of the platform's `one`/`two`/`few`/`other`. Direct execution
  records both the incorrect category set and the wrong `few` translation;
  portable extraction preserves all four native categories and values.
- `targetComment` belongs to translation import, not extraction. The explicit
  import APIs remove it from format-specific parser options and preserve it
  separately as `metadata.mojitoTargetComment` on each imported descriptor;
  source comments, notes, and translation-memory identity are never overwritten.
- Translation-memory identity is `MD5(name + source + comment)`, using Java
  string concatenation, including the literal `null` for a missing comment.
  Compatibility requires preserving or deliberately migrating those exact
  three inputs; native canonical placeholders and richer product identities
  cannot be passed to existing TM lookups without an explicit projection.
- FormatJS production defaults exercise `noteKeyPattern=description`,
  `extractAllPairs=false`, `exceptions=defaultMessage`,
  `removeKeySuffix=/defaultMessage`, and file/line/column usage extraction.
  Line and column positions use the existing customized filter's signed 32-bit
  boundary exactly: minimum and maximum values are retained, overflowing or
  underflowing numbers are ignored, and a column without a valid line is never
  appended. Numeric strings reject leading `+` and surrounding whitespace but
  normalize `-0`; actual configured-filter snapshots verify every boundary.
- The customized JSON filter incorrectly drops a complete file/line/column
  usage when the message has neither a translator note nor a separate usage
  annotation: its object-end handler only enters annotation processing when
  one of those unrelated annotations exists. A direct configured custom-filter
  comparison proves the loss; standalone Java/Rust preserve the source
  location even when `description` is absent.
- Chrome extension JSON uses `noteKeyPattern=description`,
  `extractAllPairs=false`, and `exceptions=message`.
- Evolve JSON uses preview URLs as both notes and usages, carries them across
  nested field objects, restricts selection to `_fields/.*/value`, and relies
  on stable slash-separated full-path IDs.
- Configured JSON inline-code extraction uses the real `#v1` rule format,
  assigns Okapi-compatible `p1`, `p2`, ... identities in source order, stores
  each original native spelling in canonical metadata, and restores protected
  placeholders from translated HTML markers without trusting missing, duplicated,
  or unknown code identities.
- Changed JSON translations retain every untouched source byte, including key
  ordering, original whitespace, escaped strings, array structure, and final
  newlines. `REMOVE_UNTRANSLATED` intentionally keeps the established normalized
  JSON postprocessor layout because deleting complete descriptor objects is a
  structural output policy, not an unchanged-template patch. The normalized
  writer nevertheless preserves whether the original source ended in a newline.
  Cleanup owns only
  originally missing, translatable JSON identities; explicitly translated
  placeholder-valued messages and unrelated literal metadata remain intact.
- Existing generic JSON and i18next resources can contain `//` and `/* */`
  comments. Workflow-aware Java/Rust extraction accepts those comments while
  canonical FormatJS parsing remains strict, and localized output preserves the
  original comment bytes and URLs containing literal `//`.

## Compatibility boundary

Existing `push` and `pull` support an explicit, default-Okapi
`--converter portable` integration through the current extraction and
translated-output services. The CLI parses `--converter okapi|portable`
through the same case-insensitive enum path as existing command modes, and the
help text documents that casing boundary, so lowercase documented examples work
while invalid modes fail before any
synthetic portable filter option is sent. Focused extraction and
translated-output tests also prove that marker is stripped before
format-specific Android parsing or localized-output generation, and that the
same service boundaries honor the default-off
`l10n.converter.portable=true` backend property without mutating filter
options. Shared selector tests pin immutable snapshots for both marker
insertion and platform-option stripping so routing metadata cannot leak through
later list mutation. Stored extracted-content payloads bypass both portable and
Okapi reparsing even when portable routing is enabled. Unsupported or missing
asset paths, including deferred XLIFF, remain on Okapi when only the backend
default is enabled, with extraction explicitly covered by an Okapi-extractor
delegation test. Explicit portable requests remain explicit even when the path
is missing, and fail through the unsupported-format diagnostic before the
extraction or
localized-output services enter Okapi-specific internals. The complete existing
CLI integration class can therefore be replayed against the portable backend
with:

```sh
mvn -Pno-local-config -pl cli -am \
  -Dl10n.converter.portable=true \
  -Dtest=PullCommandTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

One historical Android CLI dataset contains resource names beginning with
digits (`100_character_description_`, `15_min_duration`, and similar names).
The official Google AAPT2 compiler rejects every one of those entries, even
with its `--legacy` option. Native portable parsing intentionally rejects that
non-buildable dataset rather than silently weakening Android resource-name
validation or falling back to Okapi; the remaining existing cases provide the
meaningful portable regression gate.

To run every existing buildable CLI scenario as a green portable-backend gate,
exclude only the compiler-invalid historical case:

```sh
mvn -Pno-local-config -pl cli -am \
  -Dl10n.converter.portable=true \
  '-Dtest=PullCommandTest,!PullCommandTest#pullAndroidStrings' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Rollout controls, durable skeleton transport,
production-side import persistence, and enrollment of additional source file
types remain separate future work. Standalone Java and Rust must continue to
provide executable, shared contracts for every supported extraction and output
policy.
YAML, HTML, and bilingual XLIFF require separate format contracts; do not claim
they are portable-supported merely because their existing filter options were
inventoried here.
