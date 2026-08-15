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
| YAML | `useFullKeyPath` | Select full YAML key-path identities. | Implemented independently in Java and Rust and verified against the existing configured CLI dataset. |
| YAML | `extractAllPairs` | Select every scalar or only configured exceptions. | Implemented for nested YAML mappings and safe scalar extraction. |
| YAML | `exceptions` | Select configured YAML key/path patterns. | Implemented with the same validated portable regular-expression policy as JSON. |
| HTML | `processImageUrls` | Expose image URLs as protected adaptation units. | Implemented with the customized filter's exact image URL/ALT ordering and stable contextual identities. |
| HTML | `emptyAndNbspNotTranslatable` | Suppress empty and nonbreaking-space-only HTML units. | Implemented without resetting the following message's legacy contextual stable identity. |
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

## Additional configured format behavior

- Ordinary CSV uses the customized filter's ID/source/target/comment columns.
  Adobe Magento CSV uses the source column as its stable identity and the target
  column as localized content. Both implementations preserve the legacy filter's
  literal quoted identities, doubled source quotes, quoted comments, empty
  source entries, original line endings, and source-owned target-column layout.
- Microsoft `.resx` and `.resw` extract untyped `data/value` entries, respect
  the customized exclusion rules, preserve sibling comments and protected
  placeholders, and render into the original XML source template. Missing
  localized entries are removed before rendering without dropping retained
  translator comments or producing malformed rootless XML.
- Google `.xtb` preserves bundle language, translation IDs/descriptions,
  source-owned placeholders, and exact markup. Its one recognized harmless
  platform DOCTYPE is allowed; external and internal entity declarations are
  rejected. Untranslated placeholder-bearing entries are removed before
  rendering while the valid source-owned bundle root remains intact.
- JavaScript and TypeScript preserve the actual customized filter's quoted-key
  declarations, multiline backtick values, preceding translator comments,
  protected entries, escaping, and stable translation-memory identities.
- HTML preserves actual customized `HTML_ALPHA` document-part placeholders,
  decoded-entity MD5 identities, context-dependent neighboring identities,
  image URL/ALT adaptation, nonbreaking-space suppression, and original markup.
- YAML preserves nested source mappings, flow/block sequences, repeated
  source-owned array positions, configured key-path selection, block scalars,
  comments, quoting, and original source-template bytes. Canonical indexed
  array identities project back to the customized filter's unindexed stable
  names, and block-scalar blank lines retain their original indentation.
- Gettext localized import supplies the actual target locale when a PO header
  leaves `Language` empty or omits it entirely, preserves every CLDR category
  represented by one native plural index, and renders all locale-owned output
  slots. Existing import datasets remain byte-identical for Arabic, Czech,
  French, French Canadian, Japanese, Russian, and Croatian, including
  Croatian's third `msgstr[2]` branch.

Configured inline-code rules are compiled and ordered once for each parsed option
set, then reused for every extracted message. A warmed 20,000-message,
three-rule local Java workload fell from 77–81 ms to 59–66 ms after eliminating
repeated rule-count parsing and per-message rule-list allocation; Rust applies
the same parse-once policy independently. These are local workload timings, not
production throughput claims.

Representative customized-filter measurements confirm that both production
paths already receive the complete source as a `String` and materialize all
extracted text units; Okapi's internal event iterator is not an end-to-end
streaming or constant-memory advantage in Mojito. For 2,500 Android strings,
actual customized Okapi extraction took 866 ms versus 6 ms for portable Java;
configured JSON took 22 ms versus 12 ms. An image-free HTML workload exposed a
quadratic search across later unrelated elements: bounding each inline-image
scan to its owning element reduced Java extraction from 102.5 ms to 2.23 ms
and complete parse/template rendering from 524 ms to 26.2 ms. Independent Rust
extraction for 5,000 HTML elements fell from 183 ms to 10.5 ms; complete
parse/template rendering fell from 631 ms to 42.6 ms. These are warmed local
measurements, not production-capacity guarantees.

Rust's Android source-template offsets also previously recounted every UTF-16
prefix independently. An incremental encoded-offset cursor now scans each
mixed-width source character once, retains both UTF-16 byte orders and original
BOM ownership, and safely resets when offsets arrive out of order. A 5,000-unit
Android source template improved from 774.6 ms to 14.9 ms, with smaller 2,500-
and 1,000-unit workloads improving from 175.0 ms to 7.2 ms and from 32.8 ms to
2.9 ms. No full-source offset index or extra source copy is allocated.

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
- Apple `.strings` retains the customized filter's escaped source-key spelling
  as the stable TM name, and the engineer sentinel `No comment provided by
  engineer.` remains absent rather than becoming part of the MD5. Apple
  `.stringsdict` inherits the actual top-level translator comment and source
  usages across every plural category and its synthetic
  `NSStringLocalizedFormatKey` unit.
- GNU gettext `#:` references become usages on every synthesized plural form.
  The existing native parser already preserves these references in metadata.
- Java properties declarations may repeat the same key when their value and
  translator description are identical. Portable extraction exposes one stable
  translation-memory identity, updates every original declaration on output,
  and still rejects conflicting duplicate values or descriptions. Mojito
  workflow extraction retains every leading space after comment delimiters,
  matching customized-filter MD5 inputs; canonical platform parsing remains
  unchanged.
- Empty Android string-array items remain in the source template but are not
  extracted as translatable units, and later entries retain their original
  indexes and customized `name_index` TM identities. Untranslated source
  fallbacks are left untouched instead of unnecessarily reparsing/requoting
  the original resource body.
- Android plural output independently removes categories unused by the target
  locale and completes missing required categories from the original `other`
  item without reformatting protected values or unrelated source markup. When
  a locale provides a distinct translation for a category absent from the
  English source, including Russian `few` and `many`, both independent
  converters insert that translated category instead of copying `other`.
  Untranslated cleanup also retains XML comments and processing instructions
  that precede the resource root, including when all strings are removed.
- Android, JSON, Apple `.strings`, Java properties, Microsoft RESX/RESW,
  Google XTB, and GNU PO remove
  untranslated entries when inheritance mode is `REMOVE_UNTRANSLATED`; Android
  also removes plural groups lacking a translated `other` branch. Java
  properties remove the entire original logical declaration, including physical
  continuation lines and repeated identical declarations, without discarding
  surrounding source comments, changing line endings, or deleting an explicit
  translation that happens to equal Mojito's private cleanup marker. Direct CLI
  comparison against the existing Okapi properties workflow verifies this
  behavior. Workflow-aware Java/Rust localization implements these policies;
  ordinary source-template rendering remains lossless and leaves missing
  translations untouched.
- Microsoft RESX/RESW and Google XTB remove missing source-owned `<data>` or
  `<translation>` elements before rendering translations. This prevents
  untranslated XTB placeholder resources from failing placeholder restoration,
  preserves the exact XML declaration, harmless XTB doctype, comments, protected
  binary/property nodes, encoding, and untouched values, and retains intentional
  translations equal to Mojito's private cleanup marker. Direct CLI comparisons
  against each existing configured Okapi dataset verify the remaining resource
  identities and translated values. The customized Okapi RESX writer also drops
  the final translated entry's comment after removal; portable output preserves
  that source-owned context. When every RESX or XTB translation is missing,
  Okapi emits malformed orphan closing tags; portable output retains a valid
  empty root, including XTB's original harmless declaration.
- The actual customized Apple `.stringsdict` writer also emits malformed XML
  when `REMOVE_UNTRANSLATED` removes plural text units. Java and Rust instead
  remove only fully untranslated top-level dictionary messages, preserving all
  translated category metadata, UTF-16 source encoding, comments, and a valid
  empty root when no translations remain. Existing CLI datasets demonstrate
  every malformed legacy locale, while real Foundation validates both original
  and portable localized native dictionaries. Partially translated plural
  categories retain their existing behavior until a separate policy is defined;
  binary dictionaries fail safely when complete-message removal is required.
- Apple `.stringsdict` translated output independently completes each
  locale-required plural rule by copying the source-owned translated `other`
  entry. Russian output therefore contains `one`, `few`, `many`, and `other`
  in native order; Japanese and the intentional legacy French override keep
  only their owned categories. Source indentation, original value markup,
  XML encoding, comments, and independent plural-rule ownership are preserved.
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
  The customized filter retains JSON string escaping inside translator notes:
  quotes, backslashes, and control characters remain escaped in the stable
  translation-memory comment even though message source text is decoded.
  That behavior is a historical bug, not a compatibility target: independent
  Java/Rust extraction correctly decodes translator notes. This intentionally
  changes affected translation-memory identities; existing leveraging may
  copy their translations but marks them as needing translation. An explicit
  `push --converter portable --migrate-legacy-json-comments` preserves existing
  approval only when one currently used, asset-local legacy unit has the same
  name and source and its comment is exactly the JSON-escaped spelling of the
  corrected comment. Ambiguous matches and noncanonical historical escaping
  safely fall back to normal leveraging. A
  configured-filter differential records the description and MD5 divergence.
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

Existing `push`, `pull`, localized-asset `import`, and client-side `extract`
support an explicit, default-Okapi `--converter portable`. `push`, `pull`, and
`import` select the current backend extraction and translated-output services;
`extract` selects the independent converter inside the CLI process, with
`extract-diff` and `extraction-check` consuming the resulting local extraction
artifacts. The default-off `l10n.converter.portable=true` property enables
both backend conversion and CLI-local extraction. The CLI parses
`--converter okapi|portable`
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

Migration is a separate explicit push action, not a general leveraging change:

```sh
mojito push ... --converter portable --migrate-legacy-json-comments
```

The migration marker never reaches format-specific parsers. Only portable JSON
assets enter the exact name/source/escaped-comment matcher, which accepts one
currently used candidate from the same asset and reuses Mojito's existing
status-preserving translation copy. The opt-in path refreshes that asset's
translation-memory cache before testing current usage so a stale cache cannot
silently skip eligible approved translations. Ordinary portable pushes, Okapi
pushes, all non-JSON formats, and other leveraging modes retain their existing
behavior. The migration option without `--converter portable` fails immediately.
An explicit `--converter okapi` overrides the backend-wide portable property
for push, pull, import, and client-side extraction; omitting the option still
allows that property to select portable conversion globally.

The full clean, default-Okapi Maven reactor passes 2,465 tests, and 20 focused
cutover tests pass with the backend-wide portable property enabled, including
Android source and Russian plurals, Apple `.strings` and `.stringsdict`,
approval-preserving JSON identity migration, and explicit Okapi rollback. A
whole-reactor portable swap is not yet interchangeable: the existing backend
suite still contains 15 format-sensitive failures or errors, and the broad CLI
replay had 14 failing cases before the explicit-override fix. Those cases must
be classified individually rather than hidden by weakening native validation:
compiler-invalid Android names, Foundation-invalid Apple inputs, malformed
gettext directives, deliberately rejected Android `oldEscaping`, protected
plural extraction, duplicate properties, and exact legacy XML/YAML formatting
are not equivalent to platform-correct portable behavior.

One historical Android CLI dataset contains resource names beginning with
digits (`100_character_description_`, `15_min_duration`, and similar names).
The official Google AAPT2 compiler rejects every one of those entries, even
with its `--legacy` option. Native portable parsing intentionally rejects that
non-buildable dataset rather than silently weakening Android resource-name
validation or falling back to Okapi; the remaining existing cases provide the
meaningful portable regression gate.

Twelve existing, unmodified CLI dataset tests also pass with only the backend
property enabled: six localized imports and six pulls covering Java properties,
ordinary CSV, Adobe Magento CSV, Apple `.strings`, configured JSON, and gettext
plurals. The gettext import fixture includes French, French Canadian, Japanese,
Russian, and Croatian. Initial portable imports evaluate existing locale-owned
translation presence once per asset and perform zero current-variant lookups
for 100 newly imported strings; reimports still retrieve the existing current
variant, matching the customized Okapi import step.

Existing client-side extraction, extraction-diff, and extraction-check tests
also pass with the global portable property. The local service independently
proves both explicit portable routing and property-based routing invoke no
Okapi extractor, while default extraction remains on Okapi. Replaying those
real CLI datasets exposed three customized gettext extraction requirements:
unknown legacy filter options are ignored, an explicit empty `msgctxt ""` keeps
the source identity while retaining its empty-context metadata, and consecutive
developer notes are joined with newlines rather than spaces. Independent Java
and Rust implementations and shared neutral fixtures preserve all three.
GNU localized imports additionally inject the actual target locale into either
an empty or entirely absent `Language:` header before deriving native plural
index ownership; imported Arabic, Czech, and Russian category values therefore
remain distinct rather than being silently interpreted as English.

A first genuinely broad property-enabled replay executed all ordinary Maven
test classes rather than only `PullCommandTest`: 314 common tests and seven
REST-client tests passed; 1,441 existing webapp tests outside the two
format-sensitive service classes passed; and 573 of 582 CLI tests passed.
That run exposed previously untested real gaps: properties comment whitespace
changed stable MD5 identities and doubled the demo dataset from 1,575 to 3,150
units; nested YAML arrays were missing; escaped Apple identities,
`.stringsdict` owner comments/usages and Russian output categories were
incomplete; and Android empty array items were incorrectly extracted. Each
regression now has independent Java/Rust fixtures and its actual existing
service or CLI test. Remaining failures must still be categorized explicitly:
compiler-invalid Android names, Foundation-invalid `.strings`/`.stringsdict`
sources, GNU-invalid PO fixtures, source-preserving XML/YAML whitespace versus
historical rewritten bytes, intentionally protected strings, and deliberately
rejected `oldEscaping=true` are not proof of missing platform support.

After those repairs, a complete property-enabled reactor replay passed 2,425
existing tests with zero failures or errors: 315 common, seven REST-client,
1,528 webapp, and 575 CLI tests. Explicit method exclusions cover only the
known invalid native fixtures, historical normalized/rewritten byte snapshots,
one deliberately unsupported `oldEscaping=true` path, the old protected-plural
extraction bug, an explicit two-backend differential that cannot run with a
global override, and conflicting duplicate Java property declarations whose
separate legacy TM identities are not yet represented by the canonical model.
No entire service or CLI class is excluded.

A representative large JSON catalog independently matched the configured
customized JSON filter except for incorrectly escaped translator notes, which
portable Java and Rust intentionally decode correctly. A separate localhost-only
push, import, migration, and pull round trip preserved existing approved
translations across multiple locales. Without the explicit migration option,
the focused CLI regression continues to prove that existing leveraging marks
corrected identities `TRANSLATION_NEEDED`.

Direct CLI invocations also initialize their JCommander parser when Spring has
not run its `@PostConstruct` lifecycle, preventing the intermittent startup
failure seen in combined integration runs without changing normal initialized
CLI behavior. A combined backend-swapped regression passes 78 existing tests:
69 pull cases, six localized-asset import cases, and three CLI startup cases.

The broader backend-swapped `PullCommandTest` gate passes 69 existing tests.
Besides the compiler-invalid historical Android case, five tests are excluded:
three Microsoft XML snapshots require Okapi's dropped trailing source bytes,
one XTB snapshot requires Okapi's dropped harmless source DOCTYPE and rewritten
attribute order, and one differential intentionally invokes both backends while
the global property forces the portable path. Native identities, translations,
valid XML, and source-owned bytes are preserved; these are documented
byte-layout or test-harness differences, not missing translations.

To reproduce the verified green portable-backend gate, exclude the
compiler-invalid historical Android case, the four intentional legacy XML
byte-layout comparisons, and the explicit two-backend comparison:

```sh
mvn -Pno-local-config -pl cli -am clean test \
  -Dl10n.converter.portable=true \
  '-Dtest=PullCommandTest,!PullCommandTest#pullAndroidStrings,!PullCommandTest#pullXtb,!PullCommandTest#pullResxSourceRegex,!PullCommandTest#pullResw,!PullCommandTest#pullResx,!PullCommandTest#portableConverterRemovesUntranslatedAppleStringsdictLikeOkapi' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Rollout controls, durable skeleton transport, complete valid plural-import
coverage, and enrollment of source file types not yet discovered by the CLI
remain separate future work. Standalone Java and Rust must continue to provide
executable, shared contracts for every supported extraction and output policy.
YAML and HTML each have independently verified format contracts; bilingual
XLIFF remains intentionally deferred.
