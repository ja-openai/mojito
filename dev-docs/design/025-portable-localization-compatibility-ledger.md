# Portable localization compatibility and improvement ledger

> Generated from `file-formats/conformance/manifest.json`, actual Okapi extraction
> snapshots, and shared Java/Rust migration-shadow reports. Regenerate with
> `python3 file-formats/conformance/compatibility_ledger.py --write`.

## Decision: correct beats compatible

The replacement must reproduce real platform semantics and preserve source
bytes; matching an incorrect Okapi result is not a goal. Choose behavior in
this order:

1. Real AAPT2/linker, Foundation runtime, Xcode, GNU gettext, JDK, FormatJS,
   and ICU results, checked with original neutral fixtures.
2. Lossless translatable ownership, exact placeholders, products, plural
   selectors, comments, encoding, inline markup, and source-template bytes.
3. Explicit security boundaries and fail-closed rejection of unsafe input.
4. Legacy compatibility only where it preserves the first three properties.

An incompatible result is acceptable, and often required, when the platform
proves Okapi loses a translation, translates a protected value, corrupts
markup/context, collapses identities, or rejects valid input. Every deliberate
difference needs a manifest case and a real native or legacy observation.
Intentional Mojito workflow policy is not an Okapi defect: configured filter
options, comment-based suppression, usages, translation-memory identity,
plural import, inheritance, and localized-output cleanup require their own
compatibility contracts. The complete option and behavior inventory lives in
`dev-docs/design/026-portable-localization-custom-filter-parity.md`.

## What the measurements do and do not mean

The corpus currently contains **227 real Okapi comparisons**:
3 matching, 59 deliberately different,
5 valid platform inputs rejected by Okapi,
159 unsupported production routes, and
1 platform-invalid input nevertheless extracted by Okapi.
Its **60 actual migration-shadow snapshots** currently record:

- **152 `missing_legacy`**: real platform-localizable units that Okapi omitted.
- **208 `unexpected_legacy`**: units Okapi extracted that the platform does not translate.
- **229 `source_mismatch`**: incorrect protected markup, aliases, macro expansion, or source text.
- **17 `comment_mismatch`**: lost or changed translator context.
- **29 `duplicate_legacy`**: colliding legacy text-unit identities.
- **16 `legacy_projection_collision`**: distinct native-qualified resources collapsed into one legacy identity.

These are fixture-scoped difference records, not distinct production
incidents, affected customers, or extrapolated prevalence. One fixture may
expand a plural into several units; several fixtures may exercise the same
underlying defect. `legacy_projection_collision` means richer native
product/condition identities collapse into Okapi's smaller ID space; each
record preserves sorted qualified `canonicalIds`, and the canonical
catalog itself contains no duplicate message IDs.

Original snapshots call the real customized Mojito filters with null options
and inspect extraction only. Separate configured-workflow comparisons now
run the actual custom Android, FormatJS, Chrome, Evolve, and Apple filters
with their real options and verify source, comments, usages, and TM MD5.
Dedicated customized HTML_ALPHA comparisons additionally exercise default
extraction, configured image URLs, and retained nonbreaking-space units.
They verify decoded-entity MD5s, hidden document-part inline identities,
translator-visible HTML placeholders, image descriptions, and subsequent
stable context against the real override-selected legacy filter. Existing
HTML CLI datasets also verify exact localized markup, image URL/alt text,
and inherited locale output. These override-only comparisons are separate
from the manifest-declared path-routed comparison total above.
Configured FormatJS positions preserve actual signed 32-bit line/column
boundaries, normalize negative zero, and ignore overflows, leading plus
signs, and padded numbers rather than emitting invented locations.
Actual configured JSON extraction drops valid source locations whenever
a message has no translator note; both portable implementations retain
that independent file/line/column ownership.
Shared standalone fixtures verify inheritance-based removal, localized
output, import target comments, and pinned-CLDR plural completion. Real
9 customized Android, production-routed Apple stringsdict, and PO
import-filter comparisons verify copied plural categories, identify
legacy-dropped independent Apple selectors and hidden Foundation
conversions, cover repeated Apple selectors, prove Russian and Polish
PO's incorrect legacy `other` values, and expose Slovenian's wrong
`two`/`few` category ownership plus invented `many`. Independent
import fixtures also
complete device-owned iPhone/Mac plural trees without merging their
distinct native values;
5 imported Apple variants, hidden arguments, repeated-selector,
multi-selector, and device-tree catalogs survive write/reparse round trips;
protected JSON inline-code conversion
also has an actual custom-filter differential and lossless contracts.
A further 9 actual customized Android/Apple/JSON/PO filter, skeleton-writer,
and translated-output postprocessor comparisons prove generic Android
resources disappear from supposedly empty output, valid uppercase/title-
case/padded protected Android booleans leak into translated files, and
generic, array, and plural-item translator descriptions leak despite
explicit Android description removal;
explicit Android scalar and plural translations equal to the cleanup
placeholder disappear while portable output retains both;
separately quoted marker translations also retain exact native values;
portable Android cleanup also retains protected and DO NOT TRANSLATE-
suppressed literal marker values without claiming a legacy differential;
native ASCII-padded plural quantities retain exact source spelling and
their translated `other` output despite legacy extraction rejecting them;
escaped BMP Unicode resource names and generic types retain AAPT2 identities
and every original XML entity byte during source-template translation;
combined Apple comment removal plus inheritance cleanup restore
untranslated placeholders, Apple comment-like translated text is
silently corrupted, Apple translations containing or exactly equal to the
placeholder are incorrectly discarded, and fake untranslated declarations or block comments
embedded inside quoted Apple translations remain literal; gettext removes
translations whose source, comment, or translated value contains or equals
that marker while portable output preserves explicitly translated exact
placeholders using collision-free private cleanup markers;
consecutive untranslated JSON array objects survive ascending-index cleanup;
and explicit placeholder-valued top-level JSON translations are incorrectly
discarded while portable top-level, array, nested-array, and array-owned
nested-array translations retain original path identities;
both implementations preserve
intended output.
Another 1 actual customized gettext filter, skeleton-writer,
and postprocessor comparison confirms exact successful translated-output
parity, including header preservation and inheritance cleanup.

| Format | Real comparisons | Observed legacy policies | Shadow reports | Projected canonical units | Actual Okapi units |
| --- | ---: | --- | ---: | ---: | ---: |
| Android resources | 38 | compiler_rejected: 1, different: 34, match: 1, rejected: 2 | 35 | 463 | 581 |
| Apple .strings | 10 | different: 10 | 10 | 60 | 31 |
| Apple .stringsdict | 10 | different: 7, rejected: 3 | 7 | 150 | 130 |
| Xcode .xcstrings | 158 | unsupported: 158 | 0 | 0 | 0 |
| GNU gettext PO | 3 | different: 2, unsupported: 1 | 2 | 7 | 7 |
| Java properties | 4 | different: 4 | 4 | 11 | 11 |
| FormatJS JSON | 2 | different: 1, match: 1 | 2 | 4 | 5 |
| JavaScript source | 1 | different: 1 | 0 | 0 | 0 |
| TypeScript source | 1 | match: 1 | 0 | 0 | 0 |

## Confirmed Okapi failures by format

### Android XML and AAPT2 resource sets

- **Missing translations:** generic `<item type="string">`, heterogeneous
  `<array>` string slots, generic `<bag>` arrays/plurals, runtime-gated
  generic resources, and simultaneous positive/negative conditions are
  omitted even though AAPT2 exposes them as localizable values.
- **Forbidden translations:** package/private/theme/resource aliases,
  nontranslatable arrays/plurals, exact `donottranslate*`
  files, disabled flag conditions, disabled directories, and unresolved
  feature-flag resources leak into extraction even when they are opaque or
  absent from the real linked APK.
- **Wrong source:** build macros remain unexpanded, protected `<xliff:g>`
  XML leaks into source, whitespace-bearing/doubled references change
  meaning, translator descriptions disappear, and native style/annotation
  semantics are not represented.
- **Colliding ownership:** product-specific and mutable-condition resources
  share Okapi IDs even when the actual platform has distinct selected or
  simultaneously active translations.
- **False rejection:** whitespace-padded plural quantities compile in AAPT2
  but Okapi throws `Invalid plural form:  one `.
- **Platform-documentation discrepancy:** Android's guide says Unicode spaces
  collapse after XML parsing, but actual AAPT2 preserves all 16 tested
  non-ASCII separators, no-break characters, and the BOM. Forty-nine
  compiler-verified original values plus UTF-8/UTF-16 translated source
  templates enforce the real ASCII-only collapse boundary independently in
  Java and Rust; this is verified platform behavior, not an Okapi defect.
- **Protected-resource correctness:** AAPT2 still validates apostrophes,
  Unicode escapes, quote-resetting style boundaries, and nested protected
  sections inside every nontranslatable scalar, generic string, array,
  plural bag, and `donottranslate*` file. Both portable implementations
  now fail closed without mistakenly applying translator-only placeholder
  restrictions; UTF-8/UTF-16 source templates preserve every protected
  value and style span. The actual Okapi shadow records its protected
  array/plural translation leaks separately.
- **Namespace-transparent quote state:** AAPT2 ignores foreign inline
  namespaces and unknown XLIFF tags without resetting an enclosing quote;
  only genuine unnamespaced style spans reset it. This remains true inside
  ordinary/generic protected strings, arrays, plural bags, private
  `donottranslate*` files, and nested protected sections. Original native
  warning/span snapshots, UTF-8/UTF-16 source templates, stable rejection
  cases, and real Okapi extraction all pin the exact boundary.
- **Native instruction preservation, not an Okapi comparison:** AAPT2
  treats XML processing instructions as invisible inside scalar, styled,
  protected `xliff:g`, generic, array, and plural text. Independent
  UTF-8/UTF-16 source templates now retain their exact bytes and original
  quote/Unicode/placeholder boundaries; native source/translated snapshots
  verify style spans while invalid XML declarations fail closed.
- **Native XML lexical boundaries:** AAPT2 rejects every colon-bearing
  processing-instruction target, invalid BMP XML Name starts, join
  controls, supplementary emoji, and malformed element/attribute QNames.
  Independent Java/Rust parsers preserve valid accented/CJK names and
  trailing combining marks; exact UTF-8/UTF-16 source templates preserve
  every native-accepted instruction byte.
- **Legacy XML Unicode compatibility:** Android Expat and JDK Xerces
  use XML 1.0 Fourth Edition BaseChar/Ideographic/Combining/Extender
  classes instead of permissive Fifth Edition intervals. Rust independently
  embeds the exact legacy NCName ranges; 426 native-backed fixtures
  preserve real Greek/Hebrew/Arabic/Indic/Hangul/CJK names, reject
  modern-only scalars, and accept empty default namespace resets.
- **Intrinsic reserved XML namespace:** `xml:` is bound without an
  explicit declaration. AAPT2 rejects prefixed roots/bag/attribute
  children, ignores namespaced top-level resources/macros/controls,
  and treats prefixed inline tags as transparent foreign wrappers.
  Rust now independently seeds the reserved binding; 33 native-backed
  fixtures and four UTF-8/UTF-16 source templates prevent false private
  resource extraction, swallowed comments, and invented style spans.
- **Encoding-safe selected build products:** AAPT2 selects tablet,
  default, watch, and combined default/tablet resource variants across
  independently mixed BOM-less/BOM-bearing UTF-16, UTF-8 BOM, and
  declared ISO-8859-1 source sets. Java/Rust product selection now
  reapplies each original XML encoding instead of incorrectly reparsing
  files as UTF-8; real linked overlays and selected-product multi-file
  templates preserve exact original/localized bytes and winning slots.
- **Native Unicode product identities:** AAPT2 applies ASCII-only product
  normalization; NEL, nonbreaking/figure/narrow spaces, em spaces, and
  Unicode-only names remain distinct valid selections. Rust's Unicode
  trim falsely rejected 61 genuine builds, while Java's extra isBlank
  check rejected 15 compiler-valid Unicode-only products. Independent
  implementations now match the real linker across 95 original product
  overlays and four mixed-encoding source-owned translation templates;
  empty, ASCII-padded, comma-bearing, and duplicate names still fail.
- **Native inline-attribute namespaces:** AAPT2 erases XML, Android, and
  foreign prefixes before native span/font/annotation handling; root and
  resource `xml:space` do not preserve whitespace. Original, normalized,
  and localized compiler snapshots verify actual size/color effects, while
  UTF-8/UTF-16 source templates preserve qualified attributes exactly.
- **Additional false rejection:** AAPT2 accepts arbitrary `xml:space`
  values, but the actual Okapi ITS pipeline throws
  `org.w3c.its.ITSException: Invalid value for 'xml:space'.`
- **Concrete regression:** literal hash/color/product resources produce
  86 projected canonical units versus
  78 real Okapi units:
  8 omitted generic/array translations,
  41 protected-source mismatches,
  1 missing comment, and
  6 duplicated legacy IDs, and
  6 qualified native identity
  collisions caused solely by the lossy Okapi projection.
- **Portable improvement:** repeated protected placeholder IDs retain
  independently omitted, explicitly empty, or category/occurrence-specific
  `example` attributes. Both normalized writers and exact source templates
  preserve each native value and occurrence order, while forged
  categories/examples fail closed.
- **Portable protection:** conventional `arg0` protected sections without
  examples are no longer mistaken for ordinary `%1$s` arguments. Typed
  scalar/array/plural occurrence ownership preserves mixed wrapped/plain
  ordering, nested styles, and empty examples in normalized XML and exact
  source templates; malformed ownership fails closed in Java and Rust.
- **Portable source ownership:** exact multi-file Gradle source templates
  translate only winning scalar/product/array/plural declarations. Lower
  overridden bodies and higher-priority nontranslatable tombstones remain
  byte-identical, with original/localized default/tablet AAPT2 link proof.
  Selected tablet/default/watch/default-plus-tablet runtime identities
  independently bind to exact qualified source string/array/plural slots;
  inactive alternatives remain untouched and forged ownership fails closed.
- **Portable macro provenance:** original cross-file build definitions stay
  byte-identical while higher-priority namespace-scoped/private/transitive
  macro uses, protected placeholders, styles, arrays, plurals, and selected
  products are translated in their actual owning source files and verified
  against independently linked original/localized AAPT2 packages.

### Apple Foundation `.strings` and `.stringsdict`

- XML property-list `.strings`, native default namespaces, structural
  processing instructions, bounded XML entities, and OpenStep dictionaries
  are valid Foundation resources but current Okapi extracts zero units or
  invents malformed IDs/comments.
- Configured Apple XML cleanup preserves DTDs, processing instructions,
  untouched bytes, and translated CDATA text even when that text imitates an
  entire untranslated `<key>`/`<string>` entry. Independent Java/Rust scanners
  remove only genuine, stable-key-owned untranslated dictionary entries,
  preserving explicit placeholder-valued translations; Foundation validates the
  original and localized dictionaries and their exact translated values.
- Foundation accepts 44 malformed processing-instruction targets and XML
  attribute QNames across original strings/stringsdict fixtures. Portable
  extraction intentionally rejects those unsafe forms while retaining
  Foundation-valid colon-bearing instruction targets and exact templates.
- A further 148 Foundation-accepted modern-only Unicode XML names are
  deliberately rejected to preserve native Android/JDK-safe Fourth
  Edition name semantics and independent cross-language parity.
- A single Foundation-correct fixture has 12 canonical units versus nine
  Okapi units, with seven missing IDs, four invented IDs, four comment
  mismatches, and one escaped-source mismatch.
- Independent positioned plural rules are flattened or lost. The two-rule
  fixture has 38 projected canonical units versus 48 Okapi units, with two
  missing and 12 invented legacy identities.
- Foundation-valid protected `futureLiteral` metadata crashes Okapi with
  `Invalid plural form: futureLiteral`; a CDATA plural key crashes it with
  `Invalid plural form: <![CDATA[one]]>`; explicitly empty protected data
  triggers a `NullPointerException`.
- Foundation deliberately disables native `%n`/`%2$n` conversions instead
  of emitting Java-style newlines. A real six-message Okapi differential
  records five incorrect source projections and one translator-comment
  mismatch; FormatJS and Swift Foundation agree on zero-width, repeated,
  escaped-literal, supplementary-Unicode, and physical-newline semantics.
- Zero-width does not mean argument-free: actual Swift Foundation consumes
  an implicit native argument for `%n`, while explicit `%2$n` does not
  advance the implicit argument cursor. Scalar OpenStep/XML `.strings`
  and Xcode catalogs preserve the hidden `argumentPosition`, visible
  object/integer ownership, repeated conversions, explicit overlap,
  escaped `%%n`, supplementary identifiers, normalized output, and exact
  UTF-8/UTF-16 translated source bytes. A separate nine-message Okapi
  differential measures nine source mismatches; Foundation and FormatJS
  independently execute all original and localized native selections.
- XML/binary `.stringsdict` categories and Xcode substitution-owned plural
  branches also reserve selector-relative hidden `%n` native arguments.
  Missing the dummy in `%d%n %@` crashes actual Foundation; safe original
  and translated UTF-8/UTF-16/binary/compiled fixtures preserve repeated
  dummies, explicit `%3$n`/`%2$@` ownership, overlaps, escaped `%%n`,
  and exact positioned `%2$#@count@` markers. Actual Okapi changes all
  42 projected category sources; `.xcstrings` has no legacy route.
- Device-specific Foundation/Xcode plural dictionaries reserve those same
  hidden arguments independently on iPhone and Mac. Omitting a Mac dummy
  causes an isolated real Foundation SIGSEGV; valid XML/binary resources,
  compiled String Catalogs, translated UTF-8/UTF-16 device/category
  skeletons, repeated/explicit/escaped conversions, and nearest-anchor
  insertion all execute safely. Actual Okapi extracts zero of 24 projected
  device-owned plural units; Xcode remains entirely unrouted.
- Standalone and iPhone/Mac-owned presentation-width rules reserve hidden
  native `%n` argument positions independently at every padded threshold.
  Exact UTF-8/UTF-16 branch templates preserve repeated/explicit/escaped
  conversions and nearest-placeholder ownership. Real Foundation renders
  `(null)` when a required width dummy is omitted rather than crashing;
  native original and translated threshold selections stay exact. Actual
  Okapi loses all ten canonical messages, invents ten standalone width
  identities, and drops every device-owned width dictionary entirely.
- The corresponding genuine XML/binary `.stringsdict` fixture preserves
  independent category-owned disabled `%n`, repeated conversions, literal
  `%%n`, and real newlines. Actual Okapi flattens all 24 plural-source
  projections and changes the physical category newline into a space;
  real Foundation/FormatJS and both source-template writers remain exact.
- Standalone device and presentation-width `.stringsdict` branches can
  now be independently translated through explicit `@device`/`@width`
  source slots while retaining each branch's native placeholders, padded
  width key, genuine/escaped `%n`, physical newlines, and untouched XML.
  Actual Mac selection and every translated native width threshold are
  runtime-verified; legacy selected-only sidecars remain compatible.
- Foundation device branches may also contain complete nested plural
  dictionaries, as Xcode itself emits. Independent Java/Rust readers
  preserve canonical iPhone selection plus every original device rule;
  normalized writers retain the complete native tree and opt-in
  `@device=iphone#one`/`@device=mac#other` slots independently translate
  every category. Real Mac singular/plural execution verifies both the
  untouched and byte-preserving translated UTF-8/UTF-16 resources.
- Foundation's native device rules also own complete presentation-width
  dictionaries. Both implementations independently translate padded
  `@device=iphone#040`/`@device=mac#5` thresholds, genuine/escaped `%n`,
  and physical newlines; real Mac width selection verifies translated
  below-minimum, exact, intermediate, and widest UTF-8/UTF-16 branches.
  The reverse width→device shape is valid to `plutil` but crashes real
  Foundation with `NSInvalidArgumentException`; both parsers reject it.
- A native device dictionary can mix scalar strings, full plural dictionaries,
  and presentation-width dictionaries across different devices. Both portable
  implementations previously rejected this Foundation-valid shape; they now
  preserve `deviceMixedVariants`, selected-device canonical semantics, and all
  25 independently editable UTF-8/UTF-16 source values. Actual Mac Foundation
  verifies scalar↔plural, scalar↔width, plural↔width, and simultaneous
  plural/width/scalar original/translated execution. This intentionally
  improves native correctness without weakening
  the separate rejection of reversed width-owned device dictionaries.
- Genuine binary `.strings` and `.stringsdict` source templates now preserve
  exact object ownership, typed protected metadata, independent selectors,
  key/reference identities, and Foundation runtime behavior. Copy-on-write
  safely separates shared values/plural branches and protected key aliases,
  including complete one-byte to two-byte container-reference promotion.

### Xcode String Catalogs, gettext, properties, and FormatJS

- `.xcstrings` and ordinary `.po` assets have no current production Okapi
  route, even though independent readers/writers and native compiler
  fixtures exist. A post-extraction observer cannot repair a missing route.
- **Compiler-owned development source aliases:** Xcode treats deprecated
  Hebrew `he`/`iw`, grandfathered Bokmål/Nynorsk `nb`/`no-bok` and
  `nn`/`no-nyn`, obsolete British `en-GB`/`en-UK`, Serbian default
  Cyrillic `sr`/`sr-Cyrl`, Mandarin `zh`/`cmn` and `zh-Hans`/
  `zh-cmn-Hans`, and case-only regional tags as the actual development
  localization. Earlier portable parsers silently fell back to the
  message ID and mislabeled that real source as a translated locale.
  Independent Java/Rust now bind source scalars/plurals, states, exact
  original localization spelling, normalized regeneration, and UTF-8/
  UTF-16 source-template slots to the compiler-owned value. Protected
  and German values remain untouched; real Xcode/Foundation snapshots
  execute original and translated source categories. Physically distinct
  `fr-CA`/`fr_CA` retains its existing separator fallback without falsely
  claiming that its resource directories are compiler-equivalent.
- **Competing development-source owners are intentionally rejected:**
  native Xcode accepts declared-plus-aliased active values, two
  undeclared compiler-equivalent aliases, and even protected collisions,
  then nondeterministically chooses which source reaches the bundle.
  A declared null plus an active alias can instead nondeterministically
  suppress the development resource entirely. Independent Java/Rust
  parsers and UTF-8/UTF-16 source skeletons deliberately fail closed
  with `DUPLICATE_LOCALE`; original native snapshots explicitly retain
  both winning or active-versus-empty compiler outcomes. This is a
  documented safety improvement over permissive Xcode, not an attempt
  to preserve lossy or unstable platform behavior.
- **Aliased development device/substitution ownership:** a second native
  Xcode regression remained after scalar source aliases were fixed:
  Russian target editing still looked up the source device tree by
  literal `sourceLanguage`, so valid Hebrew, Norwegian, Serbian,
  British, and Mandarin alias-owned iPhone/Mac substitutions failed.
  Java/Rust now independently bind the actual development key before
  validating target device roots and shared `lanes`/`lights` definitions.
  Original UTF-8/UTF-16 templates preserve source states, hidden
  Foundation dummy arguments, complete Russian plural categories,
  protected trees, and exact target branch ownership; real compiler
  snapshots and Swift Foundation execute both translated Mac selectors.
  Native-proven atomic fixtures also insert genuinely missing or
  explicitly null Russian scalar/device substitution trees under all
  five development aliases, preserving source-owned arguments, all
  four Russian categories, `%4$n`, protected null descriptors, exact
  UTF-8/UTF-16 source bytes, and normalized source-alias spelling.
  Untranslated explicit-null target locales intentionally disappear
  from normalized writers but remain exact in source skeletons.
- **Future Xcode device names are compiler-valid, not runtime proof:**
  `xcstringstool` accepts arbitrary unknown, private-use, supplementary,
  uppercase, Vision, and TV device identities without validating that
  the current Foundation runtime can render them. Real macOS Foundation
  chooses a `mac` branch when available, otherwise an explicit `other`
  fallback, and returns the caller fallback for unknown-only device
  resources. Independent Java/Rust preserve every opaque device key,
  default-source priority, protected branch, exact UTF-8/UTF-16
  template slot, normalized compiler output, and actual Mac/other
  runtime; future-only values remain documented as runtime-unavailable
  rather than being misrepresented as selectable on this platform.
  A native first-target probe additionally proves scalar `device.other`
  is an independently translated `.strings` fallback, while a varied
  plural `device.other` is rejected as `Fallback value cannot be
  further varied`. Real FormatJS accepts opaque private-use and
  supplementary select keywords. Independent Java/Rust therefore
  preserve future/private/supplementary scalar/plural devices, insert
  actual scalar `other` without collapsing it into the iPhone branch,
  omit synthetic plural fallbacks, and use shared Unicode-scalar
  ordering for byte-identical first-target UTF-8/UTF-16 templates.
- Explicit-null and genuinely absent Xcode source locales are valid native
  input but produce no source-language resource. A source unit marked
  `new` is omitted too; independent opt-in Java/Rust skeleton extractors
  materialize existing nulls or insert missing source keys as `translated`
  string units through independently owned exact/zero-width slots.
  Original UTF-8/UTF-16
  bytes, target-locale review states, protected records, escaped keys,
  placeholders, and hidden Foundation dummy arguments stay intact;
  real xcstringstool/Foundation snapshots verify the inserted values,
  while forged protected-null or missing-key ownership fails closed.
- **Lossless target-locale insertion:** opt-in scalar target skeletons
  retain original source values and review states while inserting missing
  or null target properties and updating existing target value bodies.
  Native Xcode compiles `new` target values but omits `new` source values,
  and treats `fr_CA`/`fr-CA` as distinct physical locale directories.
  Java/Rust preserve the existing catalog-wide spelling, reject mixed
  aliases/invalid/source-owned locales/protected forgery, and verify
  translated target bundles, hidden arguments, and UTF-8/UTF-16 bytes.
- **Native target-only plural categories:** the same target API owns
  existing Russian `one`/`few`/`many`/`other` branches independently
  of an English `one`/`other` source. Original branch review states,
  target-specific argument order, placeholder-anchored hidden `%3$n`,
  protected entries, and unrelated languages remain byte-exact.
  Real Xcode and Russian Swift Foundation select native categories for
  0, 1, 2, 5, 21, 22, and 25. Missing `other`, invented categories,
  and forged category ownership fail closed even
  where Xcode itself permissively accepts malformed target plurals.
- **Atomic missing/null target plurals:** one version-one `id` slot
  owns a missing locale boundary or explicit-null token; its complete
  ICU plural translation must match the categories demonstrated by an
  existing target-language sibling. Java and Rust independently insert
  deterministic translated `one`/`few`/`many`/`other` trees, preserve
  source-owned hidden `%3$n` argument positions and protected bytes,
  and verify real Xcode plus Russian Foundation across UTF-8/UTF-16.
  Incomplete/invented/duplicated categories, unknown placeholders,
  missing native category evidence, and protected slot forgery fail
  safely; no sidecar-schema version or fields were added.
- **Audited first-locale plural insertion:** a genuinely new target
  locale derives its exact category set from pinned Unicode CLDR 46/
  Unicode 16. Java checks ICU4J, Rust compiles in the same manifest,
  and actual Node ICU validates all 218 routable language rules while
  explicitly rejecting the undefined `und` root. Original missing/null
  Russian fixtures independently materialize `one`/`few`/`many`/
  `other`, source-anchored `%3$n`, translated states, protected entries,
  and exact UTF-8/UTF-16 source bytes; actual Xcode and Swift Foundation
  execute every category. Existing same-language evidence remains
  authoritative; unsupported locales and incomplete categories fail.
- **Region-preserving first-locale plurals:** original Brazilian `pt_BR`
  and European `pt-PT` catalogs both derive `one`/`many`/`other`, but
  actual Node ICU, Java ICU4J, and Swift Foundation select Brazilian
  `one` versus European `other` for zero and format one million with
  distinct period/nonbreaking-space separators. Independent Java/Rust
  preserve exact catalog spelling and bundle identity, hidden `%3$n`,
  protected German records, translated states, missing/null boundaries,
  and all original UTF-8/UTF-16 bytes. Safe region/script category
  fallback never collapses regional runtime identity or accepts an
  unsupported/undefined language root.
- **Script-preserving plurals and native collision rejection:** real Xcode
  keeps Serbian Latin `sr_Latn` in `sr-Latn.lproj` but minimizes
  Serbian Cyrillic `sr-Cyrl` to `sr.lproj`; `sr-Cyrl-RS` similarly
  becomes `sr-RS.lproj`, while Chinese scripts remain distinct.
  Independent Java/Rust preserve exact script spelling, complete
  `few`/`one`/`other`, source-owned `%3$n`, protected entries, actual
  Foundation selection, and UTF-8/UTF-16 bytes. Real Xcode silently
  and nondeterministically drops one Serbian, Azerbaijani, or Uzbek
  default-script aliases; the portable implementations intentionally
  reject original native-accepted collisions as `DUPLICATE_LOCALE`.
  Unsupported CLDR 48-only `kok-Latn` remains fail-closed.
- **Deprecated language ownership and bidirectional native output:**
  Xcode aliases `iw`/`he`, `in`/`id`, `ji`/`yi`, and `no`/`nb`;
  language/region/script case and additional Mongolian/Kazakh Cyrillic,
  Bosnian/Croatian/Hausa Latin, and Punjabi Gurmukhi defaults also
  silently collide. Independent Java/Rust reject every native-proven
  loser, preserve distinct Chinese and underscore-region bundles,
  and resolve modern Hebrew requests to catalog-owned `iw`/`iw-IL`
  null slots. Pinned CLDR canonicalizes the deprecated language before
  selecting complete `one`/`two`/`other`; original UTF-8/UTF-16
  templates preserve hidden `%3$n`, translated states and protected
  German records. Real Hebrew Foundation inserts U+2068/U+2069
  directional isolates around the English placeholder argument.
- **Grandfathered aliases versus unsafe Unicode variant conversion:**
  82 actual Xcode collision pairs now include grandfathered `i-*`
  languages, Bokmål/Nynorsk `no-bok`/`nb` and `no-nyn`/`nn`,
  Belgian/Flemish/Swiss sign languages, Chinese Mandarin/Hakka/
  Xiang/Cantonese extlangs, three-letter `cmn`/`zh`, `hbs`/
  `sr-Latn`, and `mol`/`mo`, plus case-only variants/private-use
  tags. Each compiles but silently drops one translation, so both
  portable implementations reject it. Twenty native-accepted control
  pairs prove that Unicode/ICU aliases cannot be applied globally:
  legacy `no-BOKMAL`, `no-NYNORSK`, `sv-AALAND`, `el-POLYTONI`,
  `aa-SAAHO`, Oxford/POSIX English, reordered variants, deprecated
  extension keywords, and historical territories remain distinct.
  Modern `nb`/`nn` source-preserving writers independently update
  original `no-bok`/`no-nyn` slots, complete `one`/`other`,
  source-owned `%3$n`, translated states, protected entries, exact
  UTF-8/UTF-16 bytes, and real Foundation nonbreaking-space grouping.
- **Compiler-distinct underscore/hyphen regional resources:** despite
  Unicode locale equivalence, Xcode and Swift Foundation independently
  preserve/address `pt_BR.lproj` and `pt-BR.lproj`; `en_US`/`en-US`
  and `sr_RS`/`sr-RS` behave likewise. Independent Java/Rust retain
  separate metadata identities and exact UTF-8/UTF-16 source slots,
  edit only the requested regional value, and preserve the sibling,
  German state, and protected branches. A previously rejected
  `fr_CA`/`fr-CA` fixture is corrected to native-proven acceptance.
  Actual underscore-script/region Chinese, Serbian, Azerbaijani, and
  deprecated-Hebrew physical collisions remain safely rejected.
- **Fail-closed CLDR release upgrades:** the Unicode CLDR 46 source
  manifest is SHA-256 pinned with immutable release-tag provenance;
  Java, Rust, and Node independently reject unreviewed version changes.
  Comparing authoritative CLDR 48.2 identifies five newly added locales
  (`cv`, `ie`, `kok`, `kok-Latn`, `sgs`); Java ICU recognizes them, but
  installed Node ICU recognizes none and the script-qualified record
  exceeds the current manifest contract. The reproducible upgrade audit
  reports added/removed locales, category drift, SHA-256 provenance,
  unsupported locale shapes, and explicit blockers before any cutover.
- **Opaque Xcode review-state preservation:** an actual 320-combination
  Xcode matrix accepts known, unknown, future, machine-generated, and
  undefined source/target/extraction states. A `new` source compiles
  only when its entry is manually managed, while every target state
  remains runtime-visible. Independent Java/Rust preserve source,
  target, unrelated German, and protected opaque states byte-for-byte
  while updating only owned UTF-8/UTF-16 target value slots. Existing
  review-state mutation remains unsupported until separately versioned
  state ownership and explicit authorization are defined.
- **First-locale device-owned scalar/plural trees:** missing/null
  Russian iPhone/Mac roots derive every device's exact plural categories
  from pinned CLDR rather than a same-language exemplar. Independent
  Java/Rust implementations preserve source-owned scalar `%2$n`, plural
  `%3$n`, default-device fallback, translated states, protected German
  records, and original UTF-8/UTF-16 bytes. Actual Xcode and Swift
  Foundation select Mac scalar/plural branches; invented devices, missing
  categories, divergent synthetic plural fallbacks, and unsupported
  locales fail closed. Opaque future/private-use/supplementary device
  identities and native scalar `other` fallback ownership are preserved
  independently, without accepting compiler-invalid varied fallbacks.
- **First-locale scalar/device substitutions:** absent/null Russian
  roots derive independently positioned `lanes`/`lights` plural
  definitions from pinned CLDR rather than an existing target exemplar.
  Java/Rust preserve source-owned `argNum`/`formatSpecifier`, exact
  positioned native markers, shared iPhone/Mac definitions/default
  fallback, deterministic translated states, protected German records,
  and byte-exact UTF-8/UTF-16 templates. No `%4$n` is fabricated
  where source categories have none; actual Xcode/Russian Foundation
  select scalar and Mac plural combinations. Invalid categories,
  unknown arguments/devices, and unsupported locales fail closed.
  Five compiler-equivalent development aliases independently own the
  same first-ever Russian substitution insertion with no target
  exemplar: pinned CLDR supplies all categories, unrelated German and
  protected missing/null records remain exact, normalized writers
  preserve the original source key, and real Xcode/Foundation verify
  scalar/Mac behavior across byte-preserving UTF-8/UTF-16 templates.
- **Independent target-device variations:** existing Russian iPhone/Mac
  scalar branches and their independently owned `one`/`few`/`many`/
  `other` nested plurals use the same version-one `@device` and
  `@device=<name>` identities. Java/Rust preserve each original review
  state, target argument order, anchored hidden `%2$n`/`%3$n`, protected
  entries, and untouched UTF-8/UTF-16 source bytes. Real xcstringstool
  and Russian Foundation confirm device selection and plural categories.
- **Atomic missing/null target-device trees:** one version-one `id`
  slot owns an absent locale boundary or its entire null token. One
  FormatJS-valid ICU `device` select supplies every source-owned iPhone/Mac
  scalar or nested Russian plural branch; its required `other` fallback
  must exactly duplicate the canonical default device because real Xcode
  silently discards a physical `other` device. Complete same-language
  category evidence, source-anchored `%2$n`/`%3$n`, deterministic
  translated-state JSON, protected entries, and UTF-8/UTF-16 bytes are
  verified by both implementations plus actual Xcode/Russian Foundation.
  Missing/unknown devices, divergent fallback, incomplete plurals,
  unknown placeholders, missing category evidence, and protected null
  forgery fail closed; no sidecar-schema change was introduced.
- **Independent target-language substitutions:** scalar and iPhone/Mac
  roots independently preserve their exact positioned/reordered native
  `%#@name@` spellings while target-owned `lanes`/`lights` definitions
  expose Russian-only `one`/`few`/`many`/`other` category slots. Java
  and Rust preserve each target `argNum`/`formatSpecifier`, root and
  category review states, hidden `%4$n`, protected source entries, and
  original UTF-8/UTF-16 bytes. Real Xcode and Russian Foundation verify
  shared scalar/device definitions, native category selection, and Mac
  root ordering; missing selectors/`other`, invented categories, malformed
  root markers, and forged target ownership safely reject.
- **Atomic missing/null target substitutions:** one version-one `id`
  slot owns an absent locale boundary or complete JSON null. A scalar ICU
  sentence or one complete iPhone/Mac ICU select carries every independently
  translated `lanes`/`lights` plural branch, using only category/argument
  evidence from an existing same-language substitution definition. Java/Rust
  materialize deterministic `translated` roots/categories, exact source
  native selector markers, preserved `%4$n`, complete shared device sets,
  protected records, and original UTF-8/UTF-16 bytes; native Xcode/Russian
  Foundation execute every absent/null scalar/device insertion. Missing
  evidence/selectors/categories/devices, invented categories/placeholders,
  unequal shared definitions/fallbacks, and protected-null forgery reject.
- Native Xcode source templates independently translate all source-device
  sentences using opt-in `@device` slots, including actual Mac branches,
  separately owned shared plural substitutions, and combined
  `@device=mac#one`/`@device=iphone#other` native plural categories.
  Original review states, UTF-16 offsets,
  protected translated locales, genuine/escaped `%n`, physical newlines,
  and byte-exact JSON remain intact; missing/repeated markers fail safely.
- **Native boundary, not missing functionality:** Xcode itself rejects
  `%#@selector@` references inside a top-level or device-nested plural
  category, including protected target locales. Three original fixtures
  assert its exact `Cannot reference substitution` diagnostic; Java and
  Rust both reject the same resources instead of generating invalid output.
- Existing gettext routes flatten plural indexes and change fuzzy/obsolete
  ownership; shared native-shape projections happen to match for the two
  executable samples, so do not overstate those cases as measured unit loss.
- **Native gettext grammar, not an Okapi defect:** actual `msgfmt` accepts
  only ASCII space/tab around plural-expression tokens, requires exact
  `nplurals=`/`plural=` assignments, and rejects Unicode/control separators.
  Four accepted and 61 rejected original formulas plus a byte-preserving
  translated PO skeleton keep Java and Rust aligned with the real compiler.
- **GNU-valid Unicode metadata ownership:** actual `msgfmt` accepts
  NEL/NBSP/figure/narrow spaces and U+001C..U+001F in extracted notes,
  translator notes, references, flags, Language, and custom headers.
  Rust now mirrors Java Character.isWhitespace and ASCII-only reference
  splitting; 24 native MO fixtures, 72 message contracts, and three
  UTF-8/Latin-1 source templates eliminate 176 verified disagreements.
- **GNU-native normalized metadata and safe domains:** NEL, NBSP, figure
  space, and narrow no-break space are valid literal references, flags,
  and domain names. Rust previously rejected 44 Java/native-correct
  normalized outputs and accepted 24 control-bearing references/domains
  that Java intentionally rejects. Both readers/writers now share Java's
  exact whitespace predicate; 48 real normalized MO round trips, 24
  native domain snapshots, 24 documented safe rejections, 24 additional
  unsafe metadata mutations, and four UTF-8/Latin-1/CRLF source
  templates preserve every original byte.
- **Safer native decimal ownership:** GNU accepts hundreds of insignificant
  leading zeroes in plural counts/literals, but also silently ignores malformed
  count suffixes and conflicting duplicate declarations. Both portable parsers
  preserve valid zero-padded numbers/source bytes and deliberately reject the
  ambiguous cases; eight native snapshots, 23 stable errors, and an additional
  translated source skeleton document this intentional improvement.
- Properties extraction changes translator comments and can lose escaped
  key identities or reinterpret source text.
- **JDK terminal-backslash identity:** `Properties.load` consumes an odd
  EOF backslash in keys and values, but the real Okapi properties filter
  keeps it in the key. Its actual two-unit comparison reports one missing
  native identity plus one invented escaped identity. Independent Java/Rust
  templates preserve EOF ownership, continued keys, ISO-8859-1 targets,
  and Java's exact nonbreaking-whitespace identity boundary.
- **Exact Java translator-comment whitespace:** `String.strip()` removes
  U+001C..U+001F but preserves NEL, NBSP, figure space, and narrow
  no-break space; Rust's ordinary Unicode trim does the opposite. The
  canonical Rust descriptor and properties reader now use Java's exact
  whitespace table. Ten JDK-backed fixtures pin 106 hash/bang notes,
  and four UTF-8/Latin-1/CRLF templates retain original comment bytes.
- **Continued-key source ownership:** the JDK removes all indentation
  from a continued final key line before deciding whether it has a value
  separator. Independent LF/CR/CRLF source templates inspect the logical
  declaration, retain every original byte, and insert `=` only when
  necessary; a second actual Okapi shadow again exposes one missing key
  and one invented backslash-bearing identity.
- The generic JSON filter extracts nested FormatJS descriptor children as
  separate messages instead of preserving `defaultMessage`, descriptions,
  variants, and placeholder metadata as one message contract.

## Required intentional incompatibilities

| Decision | Why the portable behavior is better | Evidence |
| --- | --- | --- |
| Platform-owned identity instead of Okapi naming | Preserve canonical array indexes, product/condition variants, escaped IDs, contexts, and independent plural selectors without collisions. | Android product/flag shadows; Apple independent-plural shadows; properties escaped-key shadows. |
| Platform-owned eligibility instead of regex extraction | Never translate aliases, color primitives, disabled resources, untranslated filenames, build declarations, protected metadata, or untranslated locales. | Real AAPT2 linked snapshots, Foundation bundles, GNU MO files, and `unexpected_legacy` reports. |
| One lossless FormatJS descriptor instead of detached native branches | Preserve complete plural messages, selector positions, placeholders, descriptions, and metadata while native writers reconstruct the exact format. | Independent Java/Rust catalogs; real FormatJS, ICU4J, Foundation, and GNU runtime selections. |
| Foundation-correct zero-width `%n` and hidden native argument ownership | Do not invent a visible newline; reserve scalar, plural-category, presentation-width, and device-owned implicit dummy slots, preserve explicit selector/dummy/visible positions and nearest-placeholder anchor placement, and retain exact ordered native spelling for lossless normalized/source-template regeneration without native crashes or silent `(null)` output. | Real Swift Foundation/FormatJS selections, OpenStep/XML/binary stringsdict/Xcode originals, iPhone/Mac device and padded-width branches, exact translated UTF-8/UTF-16 templates, isolated plural SIGSEGV versus width `(null)` evidence, 42 incorrect legacy category sources, and completely omitted device-owned Okapi units. |
| Exact source skeleton instead of normalized legacy output | Preserve original bytes, encoding, ordering, comments, entity spellings, protected markup, untouched metadata, product branches, and line endings. | Shared source-skeleton fixtures and original/localized AAPT2/Foundation/Xcode/GNU/JDK snapshots. |
| Honor real XML byte encodings instead of decoding every resource as UTF-8 | Scan the complete XML declaration through its real terminator, including 65,536-character whitespace; decode genuine ASCII and ISO-8859-1, autodetect BOM-less Android UTF-16LE/BE, preserve each source-set encoding during selected-product evaluation, reject unsupported aliases, opposite-endian labels, odd bytes and unpaired surrogates, retain Foundation's native BOM precedence, and keep exact original single-byte/UTF-16 skeleton slots. | Original AAPT2/Foundation encoding matrices, independent Java/Rust extraction, long SPACE/TAB/LF/CRLF declaration fixtures, mixed-endian native-linked default/tablet/watch product overlays, original/localized multi-file selected-product source templates, and compiled-value snapshots. |
| Fail closed on unsafe XML/binary/compiler inputs | Reject external/custom entities, malformed XML document/name/QName boundaries, modern-only Unicode names outside native Fourth Edition BaseChar/Ideographic/Combining/Extender tables, dangerous binary bounds, NUL/control/noncharacters, and resource forms that crash AAPT2; honor Android's stricter processing-instruction targets and valid empty default-namespace resets. | Explicit accepted-native/rejected-portable Foundation lexical fixtures, exhaustive AAPT2/JDK Unicode probes, original Greek/Hebrew/Arabic/Indic/Hangul/CJK source templates, and AAPT2 SIGABRT/SIGSEGV observations. |
| Runtime semantics over parser-only acceptance | Reject resources accepted by `plutil` or structural compilers when actual Foundation/ICU runtime cannot use them safely. | Foundation bundle rejection, unsafe Unicode selector names, positional type collisions, and real formatter execution. |
| Require a real Android plural fallback instead of inventing one | AAPT2 compiles plural bags without `other`, but they cannot be represented as valid canonical ICU/FormatJS messages; both independent readers and writers reject them with `MISSING_OTHER_VARIANT`. | Actual AAPT2 compiled category snapshot and the real FormatJS parser's required `other` clause. |
| Keep physical Xcode bundle identity separate from CLDR locale normalization | Preserve compiler-distinct underscore/hyphen regional resources and source slots instead of falsely rejecting them as duplicates; reject only native-proven case/deprecated/default-script/underscore-script collisions that actually overwrite one physical bundle. Keep Chinese hyphen-script variants distinct and unsupported CLDR 48 `kok-Latn` fail-closed. | Original accepted `fr_CA`/`fr-CA` and dual Portuguese compiler/Foundation snapshots, independently editable UTF-8/UTF-16 regional templates, explicit Foundation bundle lookup, compiler-losing Chinese/Serbian/Azerbaijani/Hebrew underscore cases, and recorded nondeterministic native winners. |
| Version-pinned CLDR categories instead of guessed locale fallbacks | First target locales use the exact Unicode CLDR 46 category set for complete plural, device-owned scalar/plural, and positioned scalar/iPhone/Mac substitution trees; Java cross-checks ICU4J, Rust includes the identical SHA-256-pinned manifest, and actual Node ICU verifies 218 routable locales. Brazilian `pt_BR` and European `pt-PT` retain exact regional spelling, distinct zero selection, and period/nonbreaking-space number formatting despite sharing the same category names. Unaudited CLDR 48.2 additions are rejected because Node ICU lacks all five locales and `kok-Latn` exceeds the current locale-shape contract. Source-owned hidden arguments are preserved but never invented, and the undefined root plus unsupported tags fail closed instead of silently becoming English. | Original missing/null Russian and Brazilian/European Portuguese UTF-8/UTF-16 Xcode skeletons, real scalar/Mac and region-specific Foundation selections, protected-record checks, byte-identical Java/Rust CLDR provenance, and a reproducible immutable-source upgrade audit. |
| Preserve opaque Xcode workflow metadata instead of silently approving translations | Existing source, target, machine-generated, stale, unknown future, and extraction states are platform-owned opaque strings. Existing version-one skeleton slots own only value/null/insertion bytes; only an actually new inserted unit receives its native initial `translated` state. | A 320-combination real Xcode state matrix, manual-versus-automatic source-new compilation, native localized target output for every state, independent Java/Rust preservation, protected records, and original UTF-8/UTF-16 skeletons. |
| Explicit unsupported capability instead of fabricated support | Keep bilingual XLIFF, unsupported/undefined locale insertion, mutable review-state ownership, unreachable binary-reference promotion, and ambiguous inline ownership unsupported until their lossless contracts exist. | Shared fail-closed source cases and the open-gap register below. |

## Safety differences even when a platform accepts the input

AAPT2 accepts and silently ignores malformed trailing XML text, entities,
CDATA, additional document roots, trailing declarations, and unsupported
XML versions. CoreFoundation additionally accepts misplaced/duplicate
declarations, invalid standalone fields, reserved or empty namespace
bindings, duplicate namespace-expanded attributes, some unbound attribute
prefixes, malformed processing-instruction targets and qualified names,
NUL/empty numeric references, raw NUL/C0 controls and
U+FFFE/U+FFFF noncharacters inside text, attributes, comments, CDATA,
and processing instructions, plus forbidden XML 1.1 control references.
Independent Java/Rust secure-document parsing intentionally
rejects all of them, with actual native snapshots documenting each
accepted-platform/rejected-portable boundary.
Foundation also accepts non-ASCII XML bytes under an ASCII declaration;
portable readers fail closed with INVALID_ENCODING because accepting those
octets would make source text depend on an unspecified decoder. Existing
UTF-8/UTF-16 byte-order marks still deliberately override contradictory
Foundation labels because that is actual native plist behavior.
BOM-less UTF-16 Android documents with odd trailing bytes or unpaired
surrogates can also compile because AAPT2 ignores malformed data after the
root; both portable decoders reject those original bytes before source
ownership or translation injection. AAPT2 accepts certain macro
cycles, product-qualified macros, and runtime-gated macro definitions at
compile time but its real linker terminates with SIGSEGV or SIGABRT; both
portable implementations reject them before the crash. AAPT2 also accepts
different inline namespace-qualified attributes that erase to the same
native local name; portable parsers reject them until duplicate native
attribute effects have a lossless canonical and writer contract. AAPT2
also accepts plural bags without an `other` fallback; their exact
compiled category map is recorded, but portable readers/writers reject
them because FormatJS requires an `other` clause and synthesizing one
would invent runtime behavior. The JDK
also accepts empty or Java-blank property keys, including bare terminal
backslashes; portable catalogs intentionally reject those identities.
Foundation also
accepts binary fractional dates that cannot round-trip through the current
XML writer, so the writer fails with an explicit precision error instead of
silently changing the resource.

These are documented, deliberate incompatibilities. Do not weaken them for
Okapi or parser-only parity. Any future relaxation requires a safe native
runtime contract, bounded parsing, a lossless writer, and fixtures in both
languages.

## Open gaps before the replacement can be called complete

| Priority | Gap | Current evidence | Required acceptance contract |
| --- | --- | --- | --- |
| P0 | Staged rollout, unsupported-format enrollment, and operational rollback | Existing `push`/`pull` and localized-asset `import` support explicit `--converter portable` for discovered native resource formats, including CSV/Magento, Microsoft resources, XTB, JavaScript/TypeScript, and YAML; default Okapi behavior remains unchanged. Ordinary `.po`/`.xcstrings` still lack CLI discovery, and no production sampling or alerting is installed. | Add deliberate per-format enrollment, bounded metrics/alerts, corpus-backed canaries, and reversible cutover without changing the default. |
| P0 | Durable production-owned source templates | Opt-in push/pull extraction and translated generation are integrated and preserve existing TM identity and request-scoped original source templates; templates are not persisted or independently versioned. | Persist/version templates only if a later workflow requires durable transport, and validate exact encoding and protected-source ownership across that boundary. |
| P0 | Remaining customized workflow integration boundaries | Existing CLI datasets verify configured source extraction, localized output, opt-in native localized-asset import, sync/async/parallel transport, and stable TM identities. Independent Java/Rust contracts cover target comments, pinned-CLDR plural copying, and intentionally corrected legacy output defects; 9 actual customized import-filter comparisons remain available. Malformed Foundation sources and remaining plural-import edge cases require native evidence. `oldEscaping=true` deliberately fails closed because it conflicts with AAPT2. | Expand import parity to every valid plural-bearing format, keep native-correct intentional divergences documented, and preserve the default Okapi path. |
| P1 | Foundation deeper nested variations and cross-axis native arguments | Scalar OpenStep/XML `.strings`, XML/binary `.stringsdict` plural and padded presentation-width categories, Xcode substitution-owned plural branches, and iPhone/Mac device-owned plural/width categories preserve hidden `%n` slots, explicit selector/dummy/object ownership, repeated/escaped conversions, exact positioned substitution markers, nearest-placeholder anchoring, and native-safe original/translated UTF-8/UTF-16 source templates. Standalone device/width branches, complete device-owned plural/width dictionaries, mixed scalar↔plural/scalar↔width/plural↔width device branches, and simultaneous plural/width/scalar device trees preserve independently editable version-one source slots (`@device#other`, `@device=iphone#one`, `@device=mac#040`), canonical selected-device rules, deterministic output, and real translated Mac execution. Reverse width-owned device dictionaries and missing plural dummy arguments crash Foundation; omitted width dummies silently render `(null)`. Remaining safe deeper three-axis and mutable-review ownership are not yet contracted. | Extend only native-safe deeper cross-axis argument ownership with source-template injection and real Swift Foundation/FormatJS samples without risking native crashes or silent data corruption. |
| P1 | Xcode mutable review states and blocked CLDR release admission | Existing source-/target-device scalars, nested Russian device plural categories, and target-owned substitution definitions have independent version-one `@device`, `@device=<name>`, and selector/category ownership. Missing/null scalar, plural, scalar/plural device, and scalar/device substitution trees insert atomically. First target locales use a SHA-256-pinned Unicode CLDR 46/Unicode 16 source independently guarded by Java/Rust/Node. An authoritative CLDR 48.2 audit identifies `cv`, `ie`, `kok`, `kok-Latn`, and `sgs`; Java ICU already recognizes them but Node ICU does not, and script-qualified rule records are not admitted by the current schema. A 320-combination Xcode matrix proves known/future review/extraction states compile, a `new` source is omitted unless manually managed, and every target state remains native-visible; Java/Rust preserve all opaque states and protected bytes. Existing version-one slots do not own state tokens. Explicit authorized state ownership/transitions and resolving CLDR runtime/schema/native-fixture upgrade blockers remain unimplemented. | Native `xcstringstool`/Foundation fixtures for authorized state transitions, source/target review ownership, protected branches, stable compiler-verified rejection, platform-aligned ICU versions, script-qualified locale contracts, and an approved SHA-256-pinned CLDR upgrade. |
| P1 | GNU multi-domain deployment and translator workflow | Native split-domain MO and templates exist, but production deployment/runtime loading, domain lifecycle editing, sticky/workflow flag separation, and Java ICU `::group-off` policy are not production contracts. | Safe multi-file publish/load, per-domain rollback, translator-state policy, native GNU/ICU runtime parity, and `.po` route enrollment. |
| P1 | Apple binary precision and noncanonical scalar policy | Fractional binary dates currently fail safely; noncanonical NaN payloads and uncommon Gregorian years need an explicit preservation or rejection decision. | Lossless typed native snapshots plus separately documented support boundaries for dates, NaNs, infinities, and calendar extremes. |
| P1 | Full source-level Android protected inline equivalence | Existing skeletons reject ambiguous/reparented/mutated inline markup; richer source-owned token equivalence and all cross-branch protected metadata combinations are not yet exhaustive. | Native original/localized span snapshots, unambiguous token identity, category-local ownership, and stable safe rejection of every ambiguous mutation. |
| P1 | Duplicate namespace-qualified Android inline attributes | AAPT2 accepts distinct XML namespaces that collapse to the same native span local name and can retain ordered duplicate annotation/font effects; both portable implementations safely reject them instead of generating invalid XML or silently dropping native entries. | Define versioned per-occurrence namespace/attribute provenance, lossless canonical representation, deterministic normalized namespace bindings, byte-preserving source templates, and original/normalized/localized AAPT2 runtime equivalence. |
| P2 | Bilingual XLIFF | No contract yet defines segment identity, source/target state, inline-code equivalence, notes, skeleton transport, or 1.2 versus 2.x semantics. | Define those contracts first; do not claim XLIFF support merely because the input is XML. |

## Complete executable legacy-comparison inventory

Every row comes directly from `manifest.json`. `Canonical/legacy` counts
exist only when an actual shared shadow snapshot is available; crashes and
missing routes have no fabricated unit counts. Reasons are manifest-owned
and must remain specific when a new incompatibility is added.

| Format | Manifest fixture | Policy | Canonical / legacy | Measured shadow differences | Legacy failure or incompatibility |
| --- | --- | --- | --- | --- | --- |
| JavaScript source | `javascript-customized-mojito-quoted-keys-and-template-values` | `different` | — | — | Mojito's business-policy step suppresses DO NOT TRANSLATE resources after raw JavaScript extraction. |
| TypeScript source | `typescript-customized-mojito-quoted-keys-and-template-values` | `match` | — | — | Native-shaped extraction matches. |
| Android resources | `android-basic` | `different` | 5 / 5 | comment_mismatch: 1, source_mismatch: 1 | Legacy extraction retains platform printf and inline-code spellings instead of canonical FormatJS placeholders. |
| Android resources | `android-escapes` | `different` | 6 / 6 | source_mismatch: 1 | Legacy Android escaping and comments must be recorded before a safe extraction cutover. |
| Android resources | `android-aapt2-literal-hashes-preserve-plural-text-and-generic-color-ownership` | `different` | 86 / 78 | comment_mismatch: 1, duplicate_legacy: 6, legacy_projection_collision: 6, missing_legacy: 8, source_mismatch: 41 | AAPT2 owns leading hash-color primitives while preserving literal, escaped, styled, protected, and product-qualified plural hashes; legacy Okapi exposes different native resource units. |
| Android resources | `android-aapt2-doubled-leading-at-resolves-native-aliases-and-build-macros` | `different` | 22 / 30 | missing_legacy: 1, source_mismatch: 6, unexpected_legacy: 9 | AAPT2 consumes doubled leading @ before reference classification and macro expansion, while the legacy filter leaks aliases and unresolved macro markers as translatable source. |
| Android resources | `android-aapt2-xml-attribute-controls-preserve-translator-descriptions-and-protected-examples` | `different` | 19 / 17 | comment_mismatch: 2, missing_legacy: 2, source_mismatch: 5 | XML attribute character references preserve translator descriptions and XLIFF examples, while literal whitespace normalizes and legacy extraction loses generic/array metadata and protected placeholder identities. |
| Android resources | `android-aapt2-resource-references-preserve-native-spaces-tabs-line-feeds-and-carriage-returns` | `different` | 11 / 27 | missing_legacy: 1, source_mismatch: 5, unexpected_legacy: 17 | AAPT2 retains whitespace-bearing standalone, array, and plural resource references while legacy extraction leaks aliases and loses opaque native reference ownership. |
| Android resources | `android-plurals` | `different` | 12 / 12 | none | Legacy extraction emits one text unit per native plural quantity instead of one canonical ICU descriptor. |
| Android resources | `android-inline-markup-and-literal-xliff` | `different` | 3 / 3 | source_mismatch: 2 | Legacy Okapi inline-code projection differs from lossless canonical Android markup. |
| Android resources | `android-xliff-anonymous-unicode-numeric-and-repeated-protected-placeholders` | `different` | 21 / 21 | source_mismatch: 21 | Anonymous, empty, numeric, Unicode, emoji, and repeated protected placeholders require explicit comparison with Okapi's native inline-code projection. |
| Android resources | `android-references-and-untranslatable-resources` | `different` | 4 / 13 | missing_legacy: 1, unexpected_legacy: 10 | Legacy reference and translatable=false filtering must be compared explicitly. |
| Android resources | `android-array-and-plural-resource-references` | `different` | 12 / 16 | source_mismatch: 2, unexpected_legacy: 4 | Native array positions, plural references, and ignored item-level translatable attributes differ from legacy extraction. |
| Android resources | `android-aapt2-package-private-theme-and-typed-reference-grammar` | `different` | 19 / 43 | source_mismatch: 5, unexpected_legacy: 24 | Native package-qualified/private/type/theme references must be excluded from translatable source while the existing Okapi extractor leaks reference-valued strings, arrays, and plural categories. |
| Android resources | `android-plural-quantity-ascii-whitespace` | `rejected` | — | — | AAPT2 trims plural quantity whitespace but the existing Mojito Okapi filter rejects the valid native resource. Actual exception: `java.lang.RuntimeException: Invalid plural form:  one `. |
| Android resources | `android-aapt2-generic-string-format-typed-primitives-and-strict-flags` | `different` | 9 / 1 | missing_legacy: 8 | AAPT2 generic string resources can compile into native primitives; explicit format=string activates real formatted/translatable behavior, while the legacy Okapi filter misses generic declarations entirely. |
| Android resources | `android-aapt2-generic-mixed-typed-arrays-and-lossless-native-slots` | `different` | 9 / 1 | missing_legacy: 8 | Native generic typed arrays contain translatable strings alongside primitive and reference slots, while the existing Okapi Android filter misses the entire typed-array declarations. |
| Android resources | `android-aapt2-generic-bag-array-and-plural-declarations` | `different` | 15 / 1 | missing_legacy: 14 | Undocumented but AAPT2-native <bag type=array\|string-array\|integer-array\|plurals> declarations carry real translatable strings, typed slots, references, plural groups, product identity, and source-set tombstones; the configured legacy Okapi filter does not model those native bags. |
| Android resources | `android-aapt2-donottranslate-filename-prefix-suppresses-whole-resource-file` | `different` | 0 / 18 | unexpected_legacy: 18 | AAPT2 treats exact lowercase donottranslate filename prefixes as nonlocalizable and skips every generated pseudolocale, including explicit true flags, generic strings, arrays, plurals, and generic bags; the legacy Android filter does not apply filename-based file suppression. |
| Android resources | `android-aapt2-structural-namespaces-controls-and-unicode-resource-names` | `different` | 13 / 15 | comment_mismatch: 3, missing_legacy: 10, unexpected_legacy: 12 | AAPT2 ignores namespaced top-level declarations and skip/eat-comment controls, normalizes ASCII-padded Unicode resource/product names, rejects invalid bag children, and preserves only the latest native comment; existing Okapi extraction uses different structural and identity semantics. |
| Android resources | `android-context-sensitive-format-validation-and-boolean-attributes` | `different` | 12 / 12 | missing_legacy: 1, unexpected_legacy: 1 | Styled strings, generic string declarations, ignored item attributes, implicit placeholders, and resource-level boolean semantics require an actual legacy extraction comparison. |
| Android resources | `android-aapt2-exact-boolean-case-and-ignored-plural-generic-attributes` | `different` | 20 / 28 | missing_legacy: 1, unexpected_legacy: 9 | Exact native boolean spellings, generic declarations, ignored plural flags, and nontranslatable source filtering require an observed legacy extraction comparison. |
| Android resources | `android-resource-product-variants` | `different` | 12 / 12 | duplicate_legacy: 1, legacy_projection_collision: 1 | Canonical product-specific resource identities must not silently collide under legacy Okapi extraction. |
| Android resources | `android-resource-path-default-values-directory` | `match` | 1 / 1 | none | Native-shaped extraction matches. |
| Android resources | `android-aapt2-configured-read-only-feature-flags-link-time-filtering-and-array-compaction` | `different` | 19 / 26 | duplicate_legacy: 1, missing_legacy: 5, source_mismatch: 2, unexpected_legacy: 11 | Read-only build feature flags filter complete resources and compact native arrays at link time, while the existing Android Okapi filter ignores build configuration and extracts disabled positions and plural branches. |
| Android resources | `android-aapt2-selected-tablet-product-precedes-feature-flag-stripping` | `different` | 13 / 26 | duplicate_legacy: 12, missing_legacy: 1, unexpected_legacy: 1 | AAPT2 selects product variants before stripping disabled feature flags, while legacy Okapi ignores the actual product, extracts disabled variants, duplicates platform resource identities, and loses compacted array slots. |
| Android resources | `android-aapt2-read-write-flags-preserve-runtime-conditional-translations` | `different` | 10 / 9 | missing_legacy: 1 | AAPT2 retains both read/write runtime-conditional branches and their generic string declarations, while legacy Okapi does not evaluate conditional metadata and omits the generic resource. |
| Android resources | `android-aapt2-runtime-variants-preserve-fallback-and-every-condition` | `different` | 20 / 18 | duplicate_legacy: 8, legacy_projection_collision: 9, missing_legacy: 1 | AAPT2 preserves ordinary fallback plus every mutable condition as independently translatable alternatives, while legacy Okapi collides native identities and omits both generic-string alternatives. |
| Android resources | `android-aapt2-build-macros-expand-strings-styles-protected-sections-arrays-and-plurals` | `different` | 20 / 17 | missing_legacy: 3, source_mismatch: 15 | AAPT2 expands local build-time macro definitions into translated strings, styled/protected placeholders, arrays, and plurals, while the existing Okapi filter emits unresolved aliases and drops generic string declarations. |
| Android resources | `android-aapt2-build-macros-resolve-private-package-auto-and-scoped-namespace-references` | `different` | 25 / 24 | missing_legacy: 1, source_mismatch: 22 | AAPT2 resolves local/private/application-package/res-auto/package-alias/definition-scoped macro references before native string, array, plural, style, and protected-placeholder extraction; legacy Okapi retains their unexpanded spellings and drops the generic declaration. |
| Android resources | `android-aapt2-build-macros-preserve-definition-site-namespace-content-and-normalize-native-references` | `different` | 10 / 16 | source_mismatch: 3, unexpected_legacy: 6 | AAPT2 resolves ordinary string references and protected namespaces using their original macro-definition scope; Okapi instead leaks standalone macro references, wrong array/plural aliases, and unexpanded protected content. |
| Android resources | `android-aapt2-attribute-dependencies-preserve-theme-references-and-native-symbols` | `different` | 8 / 13 | source_mismatch: 1, unexpected_legacy: 5 | AAPT2 treats theme references as untranslated slots and preserves only their required typed attribute declarations, while the legacy Okapi Android filter leaks every resource reference and does not expand the definition-scoped macro. |
| Android resources | `android-aapt2-styleables-preserve-weak-typed-attributes-and-build-api-order` | `different` | 7 / 12 | source_mismatch: 1, unexpected_legacy: 5 | Weak styleable attributes are build-only declarations; legacy extraction leaks every theme-reference slot and loses ordered generated R.styleable contracts. |
| Android resources | `android-aapt2-attribute-integers-use-decimal-leading-zeroes-and-unsigned-lowercase-hex` | `different` | 1 / 10 | unexpected_legacy: 9 | AAPT2 uses decimal leading zeroes, lowercase unsigned hexadecimal, empty enum/flag masks, weak styleable definitions, and untranslated theme references that the existing Okapi filter leaks. |
| Android resources | `android-aapt2-path-feature-flag-false-removes-all-linked-resources` | `different` | 0 / 10 | duplicate_legacy: 1, unexpected_legacy: 9 | AAPT2 applies the false directory condition to every resource and removes the entire file at link time; legacy Okapi ignores path-level feature flags and extracts its strings, arrays, and synthesized plural branches. |
| Android resources | `android-aapt2-feature-flags-reject-unconfigured-build-conditional-resources` | `compiler_rejected` | — | — | AAPT2 rejects unresolved build feature flags, while legacy Okapi silently extracts conditional string, array, and plural resources that cannot compile. |
| Apple .strings | `apple-strings-basic` | `different` | 4 / 4 | comment_mismatch: 2, missing_legacy: 1, unexpected_legacy: 1 | Legacy Apple extraction preserves native formatter spellings and its own translator-note projection. |
| Apple .strings | `apple-strings-foundation-disabled-printf-conversions-remain-zero-width` | `different` | 6 / 6 | comment_mismatch: 1, source_mismatch: 5 | Foundation disables native %n conversions without emitting a newline, while legacy Okapi retains raw conversion spellings and cannot distinguish them from a literal %%n or actual line break. |
| Apple .strings | `apple-strings-single-quotes-shortcut-and-unicode-comment-lines` | `different` | 8 / 2 | missing_legacy: 8, unexpected_legacy: 2 | Foundation-supported legacy quoting and key-only entries are not necessarily understood by the current regex filter. |
| Apple .strings | `apple-strings-foundation-grammar-boundaries` | `different` | 12 / 9 | comment_mismatch: 4, missing_legacy: 7, source_mismatch: 1, unexpected_legacy: 4 | Legacy extraction does not recognize Foundation structural comments, strict bare identifiers, or ASCII-only Unicode escape boundaries. |
| Apple .strings | `apple-strings-foundation-enforces-eight-digit-character-reference-boundaries` | `different` | 4 / 0 | missing_legacy: 4 | Foundation decodes bounded XML numeric entities, escaped ampersands, and supplementary keys while the legacy strings filter extracts no XML property-list messages. |
| Apple .strings | `apple-strings-foundation-preserves-default-namespaces-and-bound-attributes` | `different` | 3 / 0 | missing_legacy: 3 | Foundation ignores default namespaces and bound metadata attributes, while the legacy regex filter cannot extract XML property-list messages. |
| Apple .strings | `apple-strings-foundation-preserves-structural-processing-instructions-and-trusted-preamble` | `different` | 3 / 0 | missing_legacy: 3 | Foundation accepts structural XML processing instructions and trusted public declarations while the legacy regex filter extracts no property-list messages. |
| Apple .strings | `apple-strings-xml-property-list` | `different` | 8 / 0 | missing_legacy: 8 | Mojito's old regex filter treats valid XML property-list strings as malformed source text instead of native localized dictionary entries. |
| Apple .strings | `apple-strings-openstep-wrapped-dictionary` | `different` | 3 / 1 | missing_legacy: 3, unexpected_legacy: 1 | Mojito's old regex filter does not recognize Foundation's brace-wrapped OpenStep dictionaries, structural comments, and key-only resources. |
| Apple .stringsdict | `apple-stringsdict-foundation-enforces-typed-character-reference-ownership` | `rejected` | — | — | Foundation preserves escaped plural identities and protected string metadata, but legacy Okapi incorrectly interprets protected futureLiteral as a plural category. Actual exception: `java.lang.RuntimeException: Invalid plural form: futureLiteral`. |
| Apple .stringsdict | `apple-stringsdict-foundation-preserves-default-namespaces-and-bound-attributes` | `different` | 6 / 0 | missing_legacy: 6 | Foundation accepts default namespaces and bound metadata attributes, while the legacy stringsdict filter extracts zero native plural messages. |
| Apple .stringsdict | `apple-stringsdict-foundation-distinguishes-empty-data-from-self-closing-typed-values` | `rejected` | — | — | Foundation accepts explicitly empty binary data and typed collections, but the current Mojito Okapi stringsdict filter crashes while treating protected empty metadata as plural input. Actual exception: `java.lang.NullPointerException: Cannot invoke "java.lang.CharSequence.length()" because "this.text" is null`. |
| Apple .stringsdict | `apple-stringsdict-foundation-strict-scalar-content-preserves-native-cdata-and-empty-booleans` | `rejected` | — | — | Foundation accepts CDATA plural keys and strict typed XML content, but the current Mojito Okapi stringsdict filter crashes on the native-valid plural category. Actual exception: `java.lang.RuntimeException: Invalid plural form: <![CDATA[one]]>`. |
| Apple .stringsdict | `apple-stringsdict-plural` | `different` | 6 / 6 | none | Legacy Apple plural extraction flattens branch names rather than preserving one canonical multi-variant descriptor. |
| Apple .stringsdict | `apple-stringsdict-foundation-disabled-printf-conversions-remain-category-scoped` | `different` | 24 / 24 | source_mismatch: 24 | Foundation removes category-owned %n conversions, preserves escaped %%n and physical newlines, while the legacy stringsdict filter flattens every branch and exposes raw native conversion text. |
| Apple .stringsdict | `apple-stringsdict-xcode-positioned-plural-arguments-and-ordinary-formats` | `different` | 38 / 48 | missing_legacy: 2, unexpected_legacy: 12 | Legacy Apple stringsdict extraction flattens positioned independent plural rules into detached category text units instead of preserving native argument positions and canonical complete messages. |
| Xcode .xcstrings | `apple-xcstrings-foundation-disabled-printf-conversions-remain-selector-owned-across-substitution-and-device-branches` | `unsupported` | — | — | The existing Okapi extension mapper does not recognize Xcode catalogs, while the portable implementation preserves selector/category-owned Foundation-disabled conversions and protected shared device branches. |
| Xcode .xcstrings | `apple-xcstrings-foundation-disabled-printf-conversions-remain-zero-width-across-scalar-device-and-plural-branches` | `unsupported` | — | — | The existing extension mapper rejects Xcode catalogs completely; the replacement preserves disabled Foundation %n separately from escaped %%n and physical newlines across scalar, default-device, and plural branches. |
| Xcode .xcstrings | `apple-xcstrings-catalog` | `unsupported` | — | — | Mojito's existing extension mapper has no Xcode String Catalog route. |
| GNU gettext PO | `gettext-comments-and-context` | `unsupported` | — | — | Mojito's current extension mapper recognizes gettext .pot templates but not ordinary .po files. |
| GNU gettext PO | `gettext-plurals-and-multiline` | `different` | 6 / 6 | none | The existing gettext route flattens indexed plural forms and preserves native formatter spellings. |
| GNU gettext PO | `gettext-obsolete-and-flags` | `different` | 1 / 1 | none | Legacy gettext extraction treats fuzzy, obsolete, and untranslated source entries differently from canonical extraction. |
| Java properties | `properties-basics` | `different` | 4 / 4 | comment_mismatch: 2 | Legacy properties extraction preserves printf spelling and Okapi's leading-space comment convention. |
| Java properties | `properties-key-only-and-escaped-whitespace` | `different` | 3 / 3 | comment_mismatch: 1, missing_legacy: 1, source_mismatch: 1, unexpected_legacy: 1 | Legacy properties empty values and escaped-whitespace key identities require explicit comparison. |
| FormatJS JSON | `formatjs-descriptors` | `different` | 2 / 3 | missing_legacy: 2, unexpected_legacy: 3 | Mojito's generic JSON filter extracts nested descriptor fields instead of preserving the FormatJS descriptor contract. |
| FormatJS JSON | `formatjs-string-map` | `match` | 2 / 2 | none | Native-shaped extraction matches. |
| Android resources | `android-aapt2-validates-nontranslatable-native-resource-text` | `different` | 1 / 11 | unexpected_legacy: 10 | AAPT2 validates but never translates protected strings, generic resources, arrays, plural bags, unsafe XLIFF IDs, and style spans; the legacy filter leaks protected array/plural values. |
| Android resources | `android-aapt2-ignored-namespaces-preserve-protected-quote-state` | `different` | 1 / 13 | unexpected_legacy: 12 | AAPT2 treats foreign namespaces and unknown XLIFF elements as transparent while preserving quote state and protected resource ownership; legacy Okapi extracts forbidden array/plural values. |
| Android resources | `android-aapt2-erases-inline-style-attribute-namespaces` | `different` | 17 / 16 | missing_legacy: 1, source_mismatch: 14 | AAPT2 erases inline XML/Android/foreign attribute namespaces, orders native attributes by namespace URI, and applies real font/annotation effects; legacy Okapi preserves invalid prefixes and leaks protected array/plural sources. |
| Android resources | `android-aapt2-accepts-xml-space-values-rejected-by-okapi` | `rejected` | — | — | AAPT2 ignores all root xml:space values and compiles the native resource, but Mojito's configured Okapi ITS filter rejects the valid Android file before extracting any translation. Actual exception: `org.w3c.its.ITSException: Invalid value for 'xml:space'.`. |
| Java properties | `properties-jdk-terminal-backslash-empty-key-declaration` | `different` | 2 / 2 | missing_legacy: 1, unexpected_legacy: 1 | The JDK consumes a final unpaired backslash and retains the resulting empty-valued property; actual Okapi mishandles its terminal source identity. |
| Java properties | `properties-jdk-terminal-backslash-continued-key-whitespace-tail` | `different` | 2 / 2 | missing_legacy: 1, unexpected_legacy: 1 | JDK continuation removes physical-line indentation, leaving a real empty-valued key; legacy Okapi does not retain the native continued-key identity. |
| Apple .strings | `apple-foundation-disabled-conversions-reserve-native-argument-slots-openstep` | `different` | 9 / 9 | source_mismatch: 9 | Foundation reserves hidden native argument slots for genuine %n conversions, while Okapi exposes raw conversions and cannot recover visible argument ownership. |
| Xcode .xcstrings | `apple-foundation-disabled-conversions-reserve-native-argument-slots-xcode-catalog` | `unsupported` | — | — | The legacy extension mapper rejects Xcode catalogs; the portable implementation retains both visible and Foundation-disabled argument positions. |
| Apple .stringsdict | `apple-foundation-plural-disabled-conversions-reserve-native-argument-slots-xml` | `different` | 42 / 42 | source_mismatch: 42 | Actual Foundation plural branches reserve hidden %n argument slots; the existing stringsdict filter flattens every category and loses safe visible-argument ownership. |
| Xcode .xcstrings | `apple-foundation-plural-disabled-conversions-reserve-native-argument-slots-xcode-substitution` | `unsupported` | — | — | Legacy routing rejects Xcode catalogs; replacement preserves selector-owned hidden Foundation arguments that are required to avoid native crashes. |
| Apple .stringsdict | `apple-device-owned-plural-hidden-foundation-argument-positions-stringsdict-xml` | `different` | 24 / 0 | missing_legacy: 24 | Legacy extraction ignores device-owned Foundation plural dictionaries and cannot preserve category-local hidden native arguments or source-template ownership. |
| Xcode .xcstrings | `apple-device-owned-plural-hidden-foundation-argument-positions-xcode-device-variations` | `unsupported` | — | — | Legacy extension routing rejects Xcode catalogs; portable device-owned plural branches preserve hidden Foundation argument slots independently. |
| Apple .stringsdict | `apple-foundation-presentation-width-hidden-native-argument-positions-xml` | `different` | 10 / 10 | missing_legacy: 10, unexpected_legacy: 10 | Legacy extraction invents standalone width-threshold identities, omits device-owned width resources entirely, and cannot preserve hidden native argument positions. |
| Xcode .xcstrings | `apple-xcstrings-explicit-null-source-locale-runtime-fallback` | `unsupported` | — | — | Legacy extension routing rejects Xcode catalogs and cannot materialize explicit-null source locales or retain existing review states. |
| Xcode .xcstrings | `apple-xcstrings-missing-source-locale-runtime-fallback` | `unsupported` | — | — | Legacy extension routing rejects Xcode catalogs and cannot insert genuinely absent source-localization keys or retain existing target review states. |
| Xcode .xcstrings | `apple-xcstrings-target-locale-spelling-state-and-runtime-boundaries` | `unsupported` | — | — | Legacy extension routing rejects Xcode catalogs and cannot add or safely update target-locale values while preserving locale spelling, source values, and review states. |
| Xcode .xcstrings | `apple-xcstrings-russian-target-only-plural-categories-and-hidden-arguments` | `unsupported` | — | — | Legacy routing rejects Xcode catalogs and cannot preserve target-only Russian plural categories, native hidden arguments, or independent review states. |
| Xcode .xcstrings | `apple-xcstrings-atomic-missing-and-null-russian-target-plural-trees` | `unsupported` | — | — | Legacy routing cannot atomically insert missing/null target plural trees, infer target-only categories, or preserve native hidden arguments and untouched source bytes. |
| Xcode .xcstrings | `apple-xcstrings-independent-russian-device-and-nested-plural-branches` | `unsupported` | — | — | Legacy routing rejects Xcode String Catalogs and cannot preserve independently target-owned device/plural branches, review states, hidden Foundation arguments, or untouched source bytes. |
| Xcode .xcstrings | `apple-xcstrings-atomic-missing-and-null-russian-target-device-trees` | `unsupported` | — | — | Legacy routing cannot atomically insert absent/null scalar or plural target device trees, preserve independently selected native branches, review states, hidden arguments, and exact protected source bytes. |
| Xcode .xcstrings | `apple-xcstrings-independent-russian-target-substitution-categories-and-device-roots` | `unsupported` | — | — | Legacy routing cannot independently preserve target-language native substitution definitions, target-only plural categories, iPhone/Mac root markers, review states, hidden arguments, and original bytes. |
| Xcode .xcstrings | `apple-xcstrings-atomic-missing-and-null-russian-target-substitutions` | `unsupported` | — | — | Legacy routing cannot atomically insert absent/null target native substitution definitions, independent Russian plural categories, scalar/device roots, review states, hidden arguments, and source bytes. |
| Xcode .xcstrings | `apple-xcstrings-cldr-first-russian-target-plural-locale` | `unsupported` | — | — | Legacy routing cannot discover first-locale plural categories from version-pinned CLDR, materialize Russian branches, preserve protected entries, native hidden arguments, and exact original source bytes. |
| Xcode .xcstrings | `apple-xcstrings-cldr-first-russian-target-substitution-locale` | `unsupported` | — | — | Legacy routing cannot derive first-locale scalar/device substitution categories from pinned CLDR, retain source-owned argument definitions, avoid inventing hidden arguments, and preserve exact protected bytes. |
| Xcode .xcstrings | `apple-xcstrings-cldr-first-russian-target-device-locale` | `unsupported` | — | — | Legacy routing cannot derive first-locale device-owned scalar/plural trees from pinned CLDR, preserve native hidden arguments, protect unrelated locales, and keep every original source byte unchanged. |
| Xcode .xcstrings | `apple-xcstrings-first-brazilian-european-regional-plural-locales` | `unsupported` | — | — | Legacy routing cannot distinguish Brazilian and European Portuguese first-locale plural selection, preserve exact hyphen/underscore catalog spelling, or maintain protected original bytes and hidden arguments. |
| Xcode .xcstrings | `apple-xcstrings-opaque-known-and-future-review-state-matrix` | `unsupported` | — | — | Legacy routing rejects Xcode catalogs and cannot preserve opaque source, target, machine-generated, future, and protected review states. |
| Xcode .xcstrings | `apple-xcstrings-first-hebrew-deprecated-regional-locales` | `unsupported` | — | — | Legacy routing cannot resolve deprecated Hebrew language ownership, its modern compiler bundle, region-specific null slots, or native plurals. |
| Xcode .xcstrings | `apple-xcstrings-independent-portuguese-underscore-hyphen-regions` | `unsupported` | — | — | Legacy extraction cannot preserve compiler-distinct Portuguese underscore/hyphen regional identities or their independent source slots. |
| Xcode .xcstrings | `apple-xcstrings-first-english-obsolete-territory-and-numeric-world-locales` | `unsupported` | — | — | Legacy routing cannot preserve Xcode-owned obsolete British territory spelling, its modern native bundle, numeric world-region identity, hidden arguments, or original source-template bytes. |
| Xcode .xcstrings | `apple-xcstrings-first-serbian-latin-cyrillic-script-locales` | `unsupported` | — | — | Legacy routing does not own distinct Serbian script plurals, catalog spelling, minimized native bundle identities, or protected bytes. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-serbian-cyrillic` | `unsupported` | — | — | Xcode silently maps sr and sr-Cyrl to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-serbian-cyrillic-region` | `unsupported` | — | — | Xcode silently maps sr-RS and sr-Cyrl-RS to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-azerbaijani-latin` | `unsupported` | — | — | Xcode silently maps az and az-Latn to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-azerbaijani-latin-region` | `unsupported` | — | — | Xcode silently maps az-AZ and az-Latn-AZ to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-uzbek-latin` | `unsupported` | — | — | Xcode silently maps uz and uz-Latn to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-uzbek-latin-region` | `unsupported` | — | — | Xcode silently maps uz-UZ and uz-Latn-UZ to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-hebrew-deprecated` | `unsupported` | — | — | Xcode silently maps iw and he to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-hebrew-deprecated-region` | `unsupported` | — | — | Xcode silently maps iw-IL and he-IL to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-indonesian-deprecated` | `unsupported` | — | — | Xcode silently maps in and id to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-indonesian-deprecated-region` | `unsupported` | — | — | Xcode silently maps in-ID and id-ID to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-yiddish-deprecated` | `unsupported` | — | — | Xcode silently maps ji and yi to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-norwegian-bokmal` | `unsupported` | — | — | Xcode silently maps no and nb to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-english-region-case` | `unsupported` | — | — | Xcode silently maps en-us and en-US to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-english-language-case` | `unsupported` | — | — | Xcode silently maps EN-us and en-US to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-serbian-script-case` | `unsupported` | — | — | Xcode silently maps sr-LATN and sr-Latn to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-serbian-cyrillic-case` | `unsupported` | — | — | Xcode silently maps sr-cyrl and sr-Cyrl to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-mongolian-cyrillic` | `unsupported` | — | — | Xcode silently maps mn and mn-Cyrl to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-mongolian-cyrillic-region` | `unsupported` | — | — | Xcode silently maps mn-MN and mn-Cyrl-MN to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-kazakh-cyrillic` | `unsupported` | — | — | Xcode silently maps kk and kk-Cyrl to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-kazakh-cyrillic-region` | `unsupported` | — | — | Xcode silently maps kk-KZ and kk-Cyrl-KZ to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-bosnian-latin` | `unsupported` | — | — | Xcode silently maps bs and bs-Latn to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-croatian-latin` | `unsupported` | — | — | Xcode silently maps hr and hr-Latn to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-punjabi-gurmukhi` | `unsupported` | — | — | Xcode silently maps pa and pa-Guru to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-hausa-latin` | `unsupported` | — | — | Xcode silently maps ha and ha-Latn to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-hebrew-deprecated-underscore-region` | `unsupported` | — | — | Xcode silently maps iw_IL and he_IL to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-portuguese-underscore-region-case` | `unsupported` | — | — | Xcode silently maps pt_BR and pt_br to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-serbian-underscore-cyrillic-region` | `unsupported` | — | — | Xcode silently maps sr_Cyrl_RS and sr_RS to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-azerbaijani-underscore-latin-region` | `unsupported` | — | — | Xcode silently maps az_Latn_AZ and az_AZ to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-chinese-underscore-simplified-region` | `unsupported` | — | — | Xcode silently maps zh_Hans_CN and zh_CN to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-chinese-underscore-traditional-region` | `unsupported` | — | — | Xcode silently maps zh_Hant_TW and zh_TW to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-english-obsolete-united-kingdom` | `unsupported` | — | — | Xcode silently maps en-UK and en-GB to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-english-obsolete-united-kingdom-underscore` | `unsupported` | — | — | Xcode silently maps en_UK and en_GB to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-czech-obsolete-czechoslovakia` | `unsupported` | — | — | Xcode silently maps cs-CS and cs-CZ to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-tagalog-filipino` | `unsupported` | — | — | Xcode silently maps tl and fil to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-tagalog-filipino-region` | `unsupported` | — | — | Xcode silently maps tl-PH and fil-PH to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-tagalog-filipino-underscore-region` | `unsupported` | — | — | Xcode silently maps tl_PH and fil_PH to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-javanese-deprecated` | `unsupported` | — | — | Xcode silently maps jw and jv to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-javanese-deprecated-region` | `unsupported` | — | — | Xcode silently maps jw-ID and jv-ID to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-serbo-croatian-cyrillic-region` | `unsupported` | — | — | Xcode silently maps sh-RS and sr-RS to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-serbo-croatian-underscore-region` | `unsupported` | — | — | Xcode silently maps sh_RS and sr_RS to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-serbo-croatian-latin-region` | `unsupported` | — | — | Xcode silently maps sh-Latn-RS and sr-Latn-RS to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-klingon` | `unsupported` | — | — | Xcode silently maps i-klingon and tlh to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-klingon-region` | `unsupported` | — | — | Xcode silently maps i-klingon-US and tlh-US to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-amis` | `unsupported` | — | — | Xcode silently maps i-ami and ami to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-bunun` | `unsupported` | — | — | Xcode silently maps i-bnn and bnn to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-hakka` | `unsupported` | — | — | Xcode silently maps i-hak and hak to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-luxembourgish` | `unsupported` | — | — | Xcode silently maps i-lux and lb to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-navajo` | `unsupported` | — | — | Xcode silently maps i-navajo and nv to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-paiwan` | `unsupported` | — | — | Xcode silently maps i-pwn and pwn to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-tao` | `unsupported` | — | — | Xcode silently maps i-tao and tao to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-atayal` | `unsupported` | — | — | Xcode silently maps i-tay and tay to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-tsou` | `unsupported` | — | — | Xcode silently maps i-tsu and tsu to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-belgian-sign-language` | `unsupported` | — | — | Xcode silently maps sgn-BE-FR and sfb to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-flemish-sign-language` | `unsupported` | — | — | Xcode silently maps sgn-BE-NL and vgt to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-swiss-sign-language` | `unsupported` | — | — | Xcode silently maps sgn-CH-DE and sgg to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-norwegian-bokmal` | `unsupported` | — | — | Xcode silently maps no-bok and nb to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-norwegian-bokmal-region` | `unsupported` | — | — | Xcode silently maps no-bok-NO and nb-NO to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-norwegian-nynorsk` | `unsupported` | — | — | Xcode silently maps no-nyn and nn to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-norwegian-nynorsk-region` | `unsupported` | — | — | Xcode silently maps no-nyn-NO and nn-NO to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-lojban` | `unsupported` | — | — | Xcode silently maps art-lojban and jbo to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-min-nan` | `unsupported` | — | — | Xcode silently maps zh-min-nan and nan to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-mandarin` | `unsupported` | — | — | Xcode silently maps zh-guoyu and zh to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-mandarin-three-letter` | `unsupported` | — | — | Xcode silently maps zh-guoyu and cmn to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-hakka-region` | `unsupported` | — | — | Xcode silently maps zh-hakka-TW and hak-TW to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-xiang-region` | `unsupported` | — | — | Xcode silently maps zh-xiang-CN and hsn-CN to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-grandfathered-cantonese-region` | `unsupported` | — | — | Xcode silently maps zh-yue-HK and yue-HK to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-mandarin-three-letter` | `unsupported` | — | — | Xcode silently maps cmn and zh to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-mandarin-three-letter-simplified` | `unsupported` | — | — | Xcode silently maps cmn-Hans and zh-Hans to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-mandarin-three-letter-traditional` | `unsupported` | — | — | Xcode silently maps cmn-Hant and zh-Hant to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-serbo-croatian-three-letter` | `unsupported` | — | — | Xcode silently maps hbs and sr-Latn to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-serbo-croatian-three-letter-region` | `unsupported` | — | — | Xcode silently maps hbs-RS and sr-Latn-RS to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-moldovan-three-letter` | `unsupported` | — | — | Xcode silently maps mol and mo to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-moldovan-three-letter-region` | `unsupported` | — | — | Xcode silently maps mol-MD and mo-MD to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-norwegian-bokmal-region` | `unsupported` | — | — | Xcode silently maps no-NO and nb-NO to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-norwegian-bokmal-underscore-region` | `unsupported` | — | — | Xcode silently maps no_NO and nb_NO to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-extlang-mandarin-simplified` | `unsupported` | — | — | Xcode silently maps zh-cmn-Hans and zh-Hans to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-extlang-mandarin-traditional` | `unsupported` | — | — | Xcode silently maps zh-cmn-Hant and zh-Hant to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-english-variant-case` | `unsupported` | — | — | Xcode silently maps en-US-posix and en-US-POSIX to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-catalan-variant-case` | `unsupported` | — | — | Xcode silently maps ca-ES-valencia and ca-ES-VALENCIA to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-private-extension-case` | `unsupported` | — | — | Xcode silently maps en-x-HARBOR and en-x-harbor to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-unicode-numbering-system` | `unsupported` | — | — | Xcode silently maps en-US-u-nu-latn and en-US-u-nu to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-script-bundle-collision-unicode-numbering-system-case` | `unsupported` | — | — | Xcode silently maps en-US-u-nu-latn and en-US-u-nu-Latn to the same native bundle and nondeterministically drops one translation; portable extraction rejects the collision. |
| Xcode .xcstrings | `apple-xcstrings-native-distinct-locale-serbo-croatian-standalone-latin` | `unsupported` | — | — | Xcode preserves distinct native bundles for sh and sr-Latn; general Unicode/ICU alias minimization must not merge either value. |
| Xcode .xcstrings | `apple-xcstrings-native-distinct-locale-serbo-croatian-explicit-latin` | `unsupported` | — | — | Xcode preserves distinct native bundles for sh-Latn and sr-Latn; general Unicode/ICU alias minimization must not merge either value. |
| Xcode .xcstrings | `apple-xcstrings-native-distinct-locale-myanmar-historical-region` | `unsupported` | — | — | Xcode preserves distinct native bundles for en-BU and en-MM; general Unicode/ICU alias minimization must not merge either value. |
| Xcode .xcstrings | `apple-xcstrings-native-distinct-locale-german-historical-region` | `unsupported` | — | — | Xcode preserves distinct native bundles for de-DD and de-DE; general Unicode/ICU alias minimization must not merge either value. |
| Xcode .xcstrings | `apple-xcstrings-native-distinct-locale-soviet-historical-region` | `unsupported` | — | — | Xcode preserves distinct native bundles for hy-SU and hy-AM; general Unicode/ICU alias minimization must not merge either value. |
| Xcode .xcstrings | `apple-xcstrings-native-distinct-locale-arabic-numeric-world-region` | `unsupported` | — | — | Xcode preserves distinct native bundles for ar-001 and ar; general Unicode/ICU alias minimization must not merge either value. |
| Xcode .xcstrings | `apple-xcstrings-native-distinct-locale-chinese-hong-kong-script` | `unsupported` | — | — | Xcode preserves distinct native bundles for zh-HK and zh-Hant-HK; general Unicode/ICU alias minimization must not merge either value. |
| Xcode .xcstrings | `apple-xcstrings-native-distinct-locale-unicode-calendar-extension` | `unsupported` | — | — | Xcode preserves distinct native bundles for en-US-u-ca-gregory and en-US; general Unicode/ICU alias minimization must not merge either value. |
| Xcode .xcstrings | `apple-xcstrings-native-distinct-locale-norwegian-bokmal-legacy-variant` | `unsupported` | — | — | Xcode preserves distinct native bundles for no-BOKMAL and nb; general Unicode/ICU alias minimization must not merge either value. |
| Xcode .xcstrings | `apple-xcstrings-native-distinct-locale-norwegian-nynorsk-legacy-variant` | `unsupported` | — | — | Xcode preserves distinct native bundles for no-NYNORSK and nn; general Unicode/ICU alias minimization must not merge either value. |
| Xcode .xcstrings | `apple-xcstrings-native-distinct-locale-swedish-aaland-legacy-variant` | `unsupported` | — | — | Xcode preserves distinct native bundles for sv-AALAND and sv-AX; general Unicode/ICU alias minimization must not merge either value. |
| Xcode .xcstrings | `apple-xcstrings-native-distinct-locale-greek-polytonic-legacy-variant` | `unsupported` | — | — | Xcode preserves distinct native bundles for el-POLYTONI and el-polyton; general Unicode/ICU alias minimization must not merge either value. |
| Xcode .xcstrings | `apple-xcstrings-native-distinct-locale-afar-saaho-legacy-variant` | `unsupported` | — | — | Xcode preserves distinct native bundles for aa-SAAHO and ssy; general Unicode/ICU alias minimization must not merge either value. |
| Xcode .xcstrings | `apple-xcstrings-native-distinct-locale-english-posix-variant-extension` | `unsupported` | — | — | Xcode preserves distinct native bundles for en-US-POSIX and en-US-u-va-posix; general Unicode/ICU alias minimization must not merge either value. |
| Xcode .xcstrings | `apple-xcstrings-native-distinct-locale-english-oxford-legacy-variant` | `unsupported` | — | — | Xcode preserves distinct native bundles for en-GB-oed and en-GB-oxendict; general Unicode/ICU alias minimization must not merge either value. |
| Xcode .xcstrings | `apple-xcstrings-native-distinct-locale-slovenian-variant-order` | `unsupported` | — | — | Xcode preserves distinct native bundles for sl-rozaj-biske and sl-biske-rozaj; general Unicode/ICU alias minimization must not merge either value. |
| Xcode .xcstrings | `apple-xcstrings-native-distinct-locale-gregorian-legacy-keyword` | `unsupported` | — | — | Xcode preserves distinct native bundles for en-US-u-ca-gregorian and en-US-u-ca-gregory; general Unicode/ICU alias minimization must not merge either value. |
| Xcode .xcstrings | `apple-xcstrings-native-distinct-locale-german-phonebook-legacy-keyword` | `unsupported` | — | — | Xcode preserves distinct native bundles for de-DE-u-co-phonebook and de-DE-u-co-phonebk; general Unicode/ICU alias minimization must not merge either value. |
| Xcode .xcstrings | `apple-xcstrings-native-distinct-locale-private-extension-payload` | `unsupported` | — | — | Xcode preserves distinct native bundles for en-x-harbor-one and en-x-harbor; general Unicode/ICU alias minimization must not merge either value. |
| Xcode .xcstrings | `apple-xcstrings-native-distinct-locale-unmapped-deprecated-language` | `unsupported` | — | — | Xcode preserves distinct native bundles for aam and aas; general Unicode/ICU alias minimization must not merge either value. |
| Xcode .xcstrings | `apple-xcstrings-first-norwegian-bokmal-nynorsk-grandfathered-locales` | `unsupported` | — | — | Legacy routing cannot distinguish grandfathered Bokmål/Nynorsk source spelling, modern compiler bundles, region-sensitive language aliases, hidden arguments, or original source-template bytes. |
| Xcode .xcstrings | `apple-xcstrings-development-source-alias-target-substitutions-hebrew-deprecated` | `unsupported` | — | — | Xcode binds development language he to iw; legacy routing cannot own source device/substitution trees, target-only plural branches, hidden arguments, or exact bytes. |
| Xcode .xcstrings | `apple-xcstrings-development-source-alias-atomic-target-substitutions-hebrew-deprecated` | `unsupported` | — | — | Xcode binds development language he to iw; legacy routing cannot atomically insert missing/null Russian device/substitution trees, hidden arguments, or exact bytes. |
| Xcode .xcstrings | `apple-xcstrings-development-source-alias-first-target-substitutions-hebrew-deprecated` | `unsupported` | — | — | Xcode binds development language he to iw; legacy routing cannot derive first-locale Russian substitution categories from pinned CLDR or preserve alias-owned source bytes. |
| Xcode .xcstrings | `apple-xcstrings-development-source-alias-target-substitutions-norwegian-bokmal` | `unsupported` | — | — | Xcode binds development language nb to no-bok; legacy routing cannot own source device/substitution trees, target-only plural branches, hidden arguments, or exact bytes. |
| Xcode .xcstrings | `apple-xcstrings-development-source-alias-atomic-target-substitutions-norwegian-bokmal` | `unsupported` | — | — | Xcode binds development language nb to no-bok; legacy routing cannot atomically insert missing/null Russian device/substitution trees, hidden arguments, or exact bytes. |
| Xcode .xcstrings | `apple-xcstrings-development-source-alias-first-target-substitutions-norwegian-bokmal` | `unsupported` | — | — | Xcode binds development language nb to no-bok; legacy routing cannot derive first-locale Russian substitution categories from pinned CLDR or preserve alias-owned source bytes. |
| Xcode .xcstrings | `apple-xcstrings-development-source-alias-target-substitutions-serbian-default-script` | `unsupported` | — | — | Xcode binds development language sr to sr-Cyrl; legacy routing cannot own source device/substitution trees, target-only plural branches, hidden arguments, or exact bytes. |
| Xcode .xcstrings | `apple-xcstrings-development-source-alias-atomic-target-substitutions-serbian-default-script` | `unsupported` | — | — | Xcode binds development language sr to sr-Cyrl; legacy routing cannot atomically insert missing/null Russian device/substitution trees, hidden arguments, or exact bytes. |
| Xcode .xcstrings | `apple-xcstrings-development-source-alias-first-target-substitutions-serbian-default-script` | `unsupported` | — | — | Xcode binds development language sr to sr-Cyrl; legacy routing cannot derive first-locale Russian substitution categories from pinned CLDR or preserve alias-owned source bytes. |
| Xcode .xcstrings | `apple-xcstrings-development-source-alias-target-substitutions-british-obsolete-territory` | `unsupported` | — | — | Xcode binds development language en-GB to en-UK; legacy routing cannot own source device/substitution trees, target-only plural branches, hidden arguments, or exact bytes. |
| Xcode .xcstrings | `apple-xcstrings-development-source-alias-atomic-target-substitutions-british-obsolete-territory` | `unsupported` | — | — | Xcode binds development language en-GB to en-UK; legacy routing cannot atomically insert missing/null Russian device/substitution trees, hidden arguments, or exact bytes. |
| Xcode .xcstrings | `apple-xcstrings-development-source-alias-first-target-substitutions-british-obsolete-territory` | `unsupported` | — | — | Xcode binds development language en-GB to en-UK; legacy routing cannot derive first-locale Russian substitution categories from pinned CLDR or preserve alias-owned source bytes. |
| Xcode .xcstrings | `apple-xcstrings-development-source-alias-target-substitutions-mandarin-simplified-extlang` | `unsupported` | — | — | Xcode binds development language zh-Hans to zh-cmn-Hans; legacy routing cannot own source device/substitution trees, target-only plural branches, hidden arguments, or exact bytes. |
| Xcode .xcstrings | `apple-xcstrings-development-source-alias-atomic-target-substitutions-mandarin-simplified-extlang` | `unsupported` | — | — | Xcode binds development language zh-Hans to zh-cmn-Hans; legacy routing cannot atomically insert missing/null Russian device/substitution trees, hidden arguments, or exact bytes. |
| Xcode .xcstrings | `apple-xcstrings-development-source-alias-first-target-substitutions-mandarin-simplified-extlang` | `unsupported` | — | — | Xcode binds development language zh-Hans to zh-cmn-Hans; legacy routing cannot derive first-locale Russian substitution categories from pinned CLDR or preserve alias-owned source bytes. |
| Xcode .xcstrings | `apple-xcstrings-development-source-locale-hebrew-deprecated` | `unsupported` | — | — | Xcode resolves development language he to source-owned localization iw; legacy routing cannot extract its real scalar/plural text, hidden arguments, states, or original source slots. |
| Xcode .xcstrings | `apple-xcstrings-development-source-locale-hebrew-deprecated-region` | `unsupported` | — | — | Xcode resolves development language he-IL to source-owned localization iw-IL; legacy routing cannot extract its real scalar/plural text, hidden arguments, states, or original source slots. |
| Xcode .xcstrings | `apple-xcstrings-development-source-locale-norwegian-bokmal` | `unsupported` | — | — | Xcode resolves development language nb to source-owned localization no-bok; legacy routing cannot extract its real scalar/plural text, hidden arguments, states, or original source slots. |
| Xcode .xcstrings | `apple-xcstrings-development-source-locale-norwegian-nynorsk` | `unsupported` | — | — | Xcode resolves development language nn to source-owned localization no-nyn; legacy routing cannot extract its real scalar/plural text, hidden arguments, states, or original source slots. |
| Xcode .xcstrings | `apple-xcstrings-development-source-locale-british-obsolete-territory` | `unsupported` | — | — | Xcode resolves development language en-GB to source-owned localization en-UK; legacy routing cannot extract its real scalar/plural text, hidden arguments, states, or original source slots. |
| Xcode .xcstrings | `apple-xcstrings-development-source-locale-serbian-default-script` | `unsupported` | — | — | Xcode resolves development language sr to source-owned localization sr-Cyrl; legacy routing cannot extract its real scalar/plural text, hidden arguments, states, or original source slots. |
| Xcode .xcstrings | `apple-xcstrings-development-source-locale-mandarin-three-letter` | `unsupported` | — | — | Xcode resolves development language zh to source-owned localization cmn; legacy routing cannot extract its real scalar/plural text, hidden arguments, states, or original source slots. |
| Xcode .xcstrings | `apple-xcstrings-development-source-locale-mandarin-simplified-extlang` | `unsupported` | — | — | Xcode resolves development language zh-Hans to source-owned localization zh-cmn-Hans; legacy routing cannot extract its real scalar/plural text, hidden arguments, states, or original source slots. |
| Xcode .xcstrings | `apple-xcstrings-development-source-locale-language-case` | `unsupported` | — | — | Xcode resolves development language en-US to source-owned localization EN-us; legacy routing cannot extract its real scalar/plural text, hidden arguments, states, or original source slots. |
| Xcode .xcstrings | `apple-xcstrings-development-source-locale-region-separator` | `unsupported` | — | — | Xcode resolves development language fr-CA to source-owned localization fr_CA; legacy routing cannot extract its real scalar/plural text, hidden arguments, states, or original source slots. |
| Xcode .xcstrings | `apple-xcstrings-development-source-owner-collision-declared-active-alias` | `unsupported` | — | — | Xcode nondeterministically overwrites or suppresses equivalent development-source values; safe extraction and source-template ownership must reject the ambiguous locale collision. |
| Xcode .xcstrings | `apple-xcstrings-development-source-owner-collision-declared-null-alias` | `unsupported` | — | — | Xcode nondeterministically overwrites or suppresses equivalent development-source values; safe extraction and source-template ownership must reject the ambiguous locale collision. |
| Xcode .xcstrings | `apple-xcstrings-development-source-owner-collision-undeclared-double-alias` | `unsupported` | — | — | Xcode nondeterministically overwrites or suppresses equivalent development-source values; safe extraction and source-template ownership must reject the ambiguous locale collision. |
| Xcode .xcstrings | `apple-xcstrings-development-source-owner-collision-protected-alias` | `unsupported` | — | — | Xcode nondeterministically overwrites or suppresses equivalent development-source values; safe extraction and source-template ownership must reject the ambiguous locale collision. |
| Xcode .xcstrings | `apple-xcstrings-future-device-ownership-and-native-mac-fallback` | `unsupported` | — | — | Legacy routing cannot preserve unknown/supplementary future Xcode device branches or distinguish real Mac/other fallback from compiler-accepted but Foundation-unavailable device-only strings. |
| Xcode .xcstrings | `apple-xcstrings-cldr-first-russian-future-device-locale` | `unsupported` | — | — | Legacy routing cannot atomically insert a first target locale with unknown/private-use/supplementary scalar/plural device identities and an independently translatable native scalar other fallback while preserving unrelated source bytes. |

## Completion rule

A format is ready only when every platform-valid translatable value is
represented, every protected/nontranslatable value is excluded, canonical
runtime behavior matches the platform, source templates preserve untouched
bytes, intentional safety differences are documented, unresolved gaps have
explicit owners/contracts, and independent Java/Rust implementations pass
the complete native-oracle suite. Compatibility with an incorrect Okapi
result never overrides that standard.
