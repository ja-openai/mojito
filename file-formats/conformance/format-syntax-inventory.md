# Localization file-format behavior and native oracles

Every fixture in this directory is original and product-neutral. Platform
documentation identifies the syntax; executable platform tools determine what
actually happens when documentation and implementation disagree.

## Apple `.strings`

Sources:

- Apple's strings-file structure, translator comments, encoding, positional
  formatting, XML property-list dictionaries, special characters, and `plutil`
  troubleshooting:
  <https://developer.apple.com/library/archive/documentation/Cocoa/Conceptual/LoadingResources/Strings/Strings.html>
- Apple's actual property-list formats, including XML, OpenStep, and binary:
  <https://developer.apple.com/documentation/foundation/propertylistserialization/propertylistformat>
- Apple's property-list object graph and supported dictionary/string/number/
  boolean value types:
  <https://developer.apple.com/library/archive/documentation/Cocoa/Conceptual/PropertyLists/AboutPropertyLists/AboutPropertyLists.html>
- Apple's authoritative Core Foundation property-list XML type tags:
  <https://developer.apple.com/library/archive/documentation/CoreFoundation/Conceptual/CFPropertyLists/Articles/XMLTags.html>
- Apple's open-source XML plist parser, including permissive base64 decoding,
  Gregorian rollover, NaN/infinity spellings, and decimal floating syntax:
  <https://github.com/swiftlang/swift-corelibs-foundation/blob/main/Sources/CoreFoundation/CFPropertyList.c>
- Apple's open-source binary plist markers, IEEE-754 widths, CFAbsoluteTime
  dates, data lengths, ordered array references, and cycle protections:
  <https://github.com/swiftlang/swift-corelibs-foundation/blob/main/Sources/CoreFoundation/CFBinaryPList.c>
- Apple's open-source Gregorian absolute-time conversion and permissive
  month/day/time overflow behavior:
  <https://github.com/swiftlang/swift-corelibs-foundation/blob/main/Sources/CoreFoundation/CFDate.c>
- Apple's open-source old-style Foundation plist parser, including single
  quotes, key-only shortcuts, named control escapes, Unicode line terminators,
  and NextStep octal decoding:
  <https://github.com/swiftlang/swift-corelibs-foundation/blob/main/Sources/CoreFoundation/CFOldStylePList.c>
- Apple's actual NextStep Latin byte-to-Unicode conversion table:
  <https://github.com/swiftlang/swift-corelibs-foundation/blob/main/Sources/CoreFoundation/CFBuiltinConverters.c>
- Apple's documented built-in NextStep/OpenStep string encoding:
  <https://developer.apple.com/documentation/corefoundation/cfstringbuiltinencodings>
- Apple's modern String Catalog plural and device variations:
  <https://developer.apple.com/documentation/xcode/localizing-and-varying-text-with-a-string-catalog>
- Apple's first-party explanation of independent String Catalog substitutions,
  plural permutations, argument positions, and native stringsdict compilation:
  <https://developer.apple.com/videos/play/wwdc2023/10155/>
- Apple's Xcode 16.3 clarification that implicit plural-substitution arguments
  begin at the substitution's assigned argument position:
  <https://developer.apple.com/documentation/Xcode-Release-Notes/xcode-16_3-release-notes>
- Apple's legacy `.stringsdict` width and device rules:
  <https://developer.apple.com/documentation/xcode/creating-width-and-device-variants-of-strings>
- Apple's documented Foundation positional `n$` arguments and numeric format
  spellings:
  <https://developer.apple.com/library/archive/documentation/Cocoa/Conceptual/Strings/Articles/formatSpecifiers.html>
- Foundation's presentation-width fallback and ordered width selection:
  <https://developer.apple.com/documentation/foundation/nsstring/variantfittingpresentationwidth%28_%3A%29>
- Apple's documented canonical public plist declaration:
  <https://developer.apple.com/documentation/devicemanagement/remove-provisioning-profile-command>

Native oracle: `python3 file-formats/conformance/apple_plutil_oracle.py` invokes
Foundation's actual property-list reader through macOS `plutil -convert json`
and its actual localized-string formatter through Swift resource bundles.
It verifies UTF-8, little-endian UTF-16, big-endian UTF-16, single-/double-quoted
and bare keys, key-only shortcuts, escaped delimiters, multiple comment styles,
Foundation format arguments, NextStep octal escapes, Unicode scalars, surrogate
pairs, byte-order marks, malformed UTF-8, truncated UTF-16, and invalid UTF-16
surrogate sequences. The same native reader now receives manifest-selected LF,
CR, and CRLF sources; normalized writer cases separately require Foundation's
parsed dictionary to remain unchanged after deterministic UTF-8 regeneration.

Measured Foundation quirks captured in original fixtures:

- A backslash immediately before physical LF, CR, or CRLF preserves the exact
  LF, CR, or CRLF; converting escaped CR to LF silently corrupts the message.
- Uppercase `\U` accepts zero through four **ASCII** hexadecimal digits; zero
  digits produces U+0000, and full-width or Arabic digits remain ordinary text.
- Lowercase `\u` is not the uppercase Unicode escape: the slash disappears and
  `u` remains literal.
- Foundation accepts block and line comments between the key, equals sign,
  value, and semicolon, including key-only shortcuts. Only comments before the
  key become Mojito translator descriptions; structural comments are discarded.
- Grammar whitespace is exactly U+0009 through U+000D, ASCII space, U+2028,
  and U+2029. NBSP, NEL, figure/narrow/EM spaces, zero-width spaces, and unit
  separators are invalid between tokens even though quoted values retain them.
- Bare identifiers accept only ASCII letters/digits and `_`, `$`, `/`, `:`,
  `.`, `-`; punctuation and Unicode letters require quotes. URL-looking slash
  sequences remain valid while structural comment delimiters do not become IDs.
- Unicode whitespace inside translator comments is normalized consistently by
  the independent Java and Rust parsers without changing grammar whitespace.
- Real `Bundle.localizedString` accepts XML `<plist><dict>`, direct `<dict>`
  roots, and brace-wrapped OpenStep dictionaries. XML declarations, plist
  versions, and the standard Apple public DTD are optional; both UTF-16 byte
  orders work. XML entities, CDATA, empty values, ordered placeholders, numeric
  carriage-return entities, and supplementary keys retain native values.
- Only XML comments immediately before a dictionary key become translator
  descriptions; comments between a key and value remain structural. Comments
  inside a `<key>` or `<string>`, nested elements, unsupported nonstring values,
  extra root values, literal inter-element text, and malformed pairs fail.
- CoreFoundation skips XML processing instructions before the public plist
  declaration, around the plist/dictionary roots, and between every
  dictionary key/value pair. Instructions inside scalar `<key>`, `<string>`,
  `<integer>`, or other typed values are rejected by native Foundation, not
  silently discarded. Java DOM and Rust quick-xml preserve that distinction
  independently. Trusted public DTD stripping now allows structural
  processing-instruction/comment preambles without permitting custom/internal
  declarations or external entities.
- CoreFoundation permits comments and processing instructions between plist,
  dictionary, and array values, but rejects stray non-whitespace text and
  rejects even whitespace-only CDATA in those container positions. Scalar
  keys, strings, integers, reals, dates, binary data, and booleans reject
  embedded comments, processing instructions, and nested elements. CDATA is
  accepted in keys, strings, and—unexpectedly—real numbers, but rejected in
  integers, dates, and data. Explicit `<true></true>`/`<false></false>` are
  valid, while any boolean whitespace or text is rejected. Both Java/Rust
  readers preserve valid structural comments, CDATA keys/string fragments,
  exact real IEEE bits, typed-array ownership, and source-template bytes.
  Mojito's current Okapi filter crashes on native-valid `<![CDATA[one]]>`
  plural-category keys; its exact rejection is captured in the manifest.
- Empty typed values preserve Foundation's lexical distinction even though
  ordinary DOM trees erase it: `<data></data>` and whitespace-only explicit
  data contain zero native bytes, while `<data/>` is rejected in message
  fields, plural rules, nested arrays, and nested dictionaries. Empty
  strings, arrays, dictionaries, and booleans accept both native empty
  spellings; empty integers/reals/dates do not. Rust records source
  self-closing events, and Java scans secure original XML without confusing
  comments, CDATA text, processing instructions, or quoted attributes.
  Typed source-preservation oracles use Foundation `binary1` snapshots so
  zero-length data remains observable instead of failing JSON conversion.
  The actual legacy Okapi filter separately crashes with a reproducible null
  dereference on Foundation-valid protected empty data.
- Empty XML/OpenStep dictionaries normalize to a deterministic comment-only
  `.strings` file because Foundation rejects an actually empty zero-byte file.
- `plutil` accepts JSON dictionaries as converter input, but real Foundation
  bundles silently reject JSON `.strings` resources. An explicit native Bundle
  fallback probe prevents this converter-tool false positive from entering the
  canonical contract.
- Real Foundation bundles also accept binary property-list `.strings` files.
  Original `plutil -convert binary1` dictionaries are stored as readable,
  implementation-neutral hexadecimal fixtures and decoded independently by
  bounded Java/Rust readers. Foundation accepts every `bplist0?` version,
  ignores reserved trailer bytes, and allows every 1–255-byte offset/reference
  width, including odd three-byte widths, 9/16/255-byte widths, and ignored
  high-order bytes. Nonzero top-object indexes, shared string references,
  16/32-byte extended dictionary/string lengths, and two-byte references beyond
  255 objects are also native-valid.
- Ordinary plain-text bare keys beginning with `bplist`, `bplist0`,
  `bplist00`, or `bplist10` remain valid and are never confused with a binary
  header.
- The binary format's supposedly ASCII marker actually maps every byte to
  ISO-8859-1, including U+0080/U+0091 C1 controls, U+00A0, and U+00FF; UTF-16
  markers count big-endian code units and reject unpaired surrogates. The
  documented future UTF-8 marker is not accepted for any current `bplist0?`
  version and fails portably. Actual
  Foundation output verifies these undocumented boundaries.
- Top-level dictionaries require string keys and values; duplicate/blank keys
  fail portably even though Foundation accepts them. Readers reject malformed
  headers/trailers, integer/reference widths, object counts, object offsets,
  object references, extended lengths, and truncated strings. Inputs are
  bounded to 16 MiB, 65,536 objects, and 1,000,000 UTF-16/Latin-1 string units.
- Foundation also loads binary `.stringsdict` plural resources, not merely the
  XML representation described in Apple's localization guide. Nested/shared
  dictionaries retain multiple independent variables, positioned/reordered/
  repeated selectors, width/device variations, Unicode, signed integers, and
  booleans. Original and normalized bundles execute the same real selectors.
- Nested binary dictionaries reject duplicate keys, self-referential cycles,
  more than 64 nesting levels, and shared-object amplification beyond the
  65,536-visit budget. The dangerous expansion graph is never passed to native
  tools. Unknown message-owned and plural-rule-owned dictionaries, ordered
  heterogeneous arrays, strings, booleans, signed/unsigned integers, raw data,
  UTC dates, and exact IEEE-754 reals survive recursive `applePlistExtras`.
  Explicit tags preserve data/date/real types and genuine dictionaries whose
  own keys collide with the reserved tag marker. Original XML and binary
  resources, normalized XML, type-preserving native Foundation snapshots,
  and real localized bundles must agree; nested array cycles fail safely.
- `plutil -convert json` rejects otherwise valid data/date/nonfinite values,
  while `plutil -convert xml1` silently changes negative zero to positive zero.
  `appleTypedPlist` therefore converts with real Foundation `binary1` and
  compares exact native bytes, date seconds, and IEEE floating-point bits.
- Foundation's XML base64 reader ignores ASCII punctuation, incomplete input,
  and malformed padding; padding state still changes the resulting bytes.
  Both independent readers reproduce its actual source algorithm and emit
  canonical base64 without changing decoded data.
- Foundation's strict lexical UTC date shape still normalizes overflowing
  months, days, hours, minutes, and seconds instead of rejecting them. Native
  binary dates use floating-point seconds since 2001-01-01 UTC; whole seconds
  round-trip, while fractional seconds fail with
  `UNSUPPORTED_APPLE_PLIST_DATE_PRECISION` because XML dates cannot preserve
  them. Negative zero, NaN, both infinities, and 32/64-bit binary float markers
  retain exact canonical IEEE-754 values.
- XML `<integer>` accepts decimal leading zeros, an explicit `+`, signed
  `0x`/`0X` hexadecimal, signed 64-bit minima, and unsigned 64-bit maxima.
  Whitespace inside the tag, incomplete hexadecimal values, negative underflow,
  and unsigned overflow are rejected by actual Foundation and both readers.
  Binary integer objects use unsigned one-/two-/four-byte values, signed
  eight-byte values, and sixteen-byte sign/padding representations.
- Future `NSString...` string fields and typed dictionary/boolean/integer
  plural-rule annotations are metadata, while invented string-valued CLDR
  categories remain explicit `INVALID_PLURAL_CATEGORY` failures. Known format,
  value-type, and plural-category fields require real plist strings; metadata
  nulls, invalid type-tag payloads, noncanonical NaN bits, duplicate escaped
  dictionary keys, and collisions with owned declarations fail safely rather
  than regenerating a different native resource.
- Only Apple's exact standard public property-list DTD is stripped safely;
  custom/internal/external entity declarations are blocked without handing
  potentially dangerous inputs to Foundation.
- Literal Apple markup is not FormatJS rich text: opening angles are ICU-quoted
  with reversible `appleMarkupEscaping` metadata, ordinary apostrophes remain
  unchanged, both normalized writers restore the exact native string, and
  Foundation, FormatJS, and ICU4J must all render the same formatted text.
- A valid uppercase surrogate pair becomes a supplementary Unicode character.
- Both single-quoted and double-quoted entries are accepted; a key followed
  immediately by `;` implicitly uses that same key as its value.
- `//` comments end at LF, CR, U+2028, or U+2029.
- `\a` and `\v` produce bell and vertical tab. Unknown escapes discard the
  backslash instead of inventing an unsupported hexadecimal escape.
- Octal escapes accumulate in an unsigned 8-bit value and decode through
  NextStep Latin rather than ISO-8859-1 or Unicode. `\200` is U+00A0,
  `\201` is U+00C0, `\401` wraps to U+0001, and undefined `\376`/`\377`
  resolve to NUL. Every one of the 128 extended NextStep byte mappings is
  checked against CoreFoundation source and Foundation's actual `plutil`
  output, together with representative unsigned overflow cases.
- Unpaired surrogates pass `plutil -lint` but cannot be converted into JSON;
  both canonical parsers reject them.
- Foundation accepts duplicate keys with last-value-wins and accepts an
  unterminated final comment. Mojito deliberately rejects both to avoid silent
  message loss and ambiguous diagnostics.

The normalized Apple writer sorts keys by Unicode scalar, quotes all keys and
values, retains descriptions safely as line comments, reconstructs original
positional format conversions and literal `%%`, records `%n` when its native
spelling matters, escapes C0 controls with `\UXXXX`, and emits UTF-8 regardless
of the accepted source BOM/byte order. Supplementary-plane/private-use sorting,
embedded comment delimiters, delimiter-bearing keys, NUL, bell, vertical tab,
NextStep bytes, single quotes, and shorthand entries all pass identical Java
and Rust snapshots plus Foundation dictionary round trips.

A separate source-preserving Foundation/OpenStep and XML property-list sidecar
records half-open original-byte value slots instead of rebuilding the file.
OpenStep double/single quote delimiters, comments, whitespace, wrapped
dictionary braces, punctuation-bearing and escaped keys, resource order,
UTF-8/UTF-16 BOMs, supplementary-Unicode offsets, and CRLF stay untouched.
Unquoted translated values become safely quoted; a shorthand `key;` uses a
zero-width insertion immediately before the original semicolon, preserving its
source key and formatting. Single-quoted target apostrophes are escaped without
changing their original delimiter.

XML dictionaries preserve their original declaration, trusted public Apple DTD,
direct/wrapped root, comments, structural processing instructions, entity/CDATA
key spelling, attributes, and exact slot-adjacent whitespace. Ordinary values
use native XML character references,
literal/mixed CDATA survives translation, and `]]>` is safely split into
adjacent CDATA sections. Self-closing strings expand only inside their original
`/>` slot, while explicit empty elements use zero-width value insertion.
Original Foundation plural dictionaries similarly retain processing
instructions around selectors, plural branches, and protected typed metadata.
Foundation-native positional placeholders, escaped percentages, `%n`, numeric
newlines, supplementary Unicode, UTF-8 BOM, UTF-16 byte orders, and CRLF remain
identical in independently implemented Java/Rust snapshots. Actual `plutil`
checks complete original and translated native dictionaries. Binary property
lists remain a separate source-skeleton contract.

An explicit differential run through Mojito's current regex-based Okapi filter
shows that punctuation inside a quoted key can be split into the wrong legacy
ID/value, Foundation-supported single-quoted/key-only syntax can be missed or
misidentified, and translator notes retain leading/trailing comment spaces.
An original 12-message structural-grammar fixture produces only nine legacy
units, including seven missing native IDs, four malformed unexpected IDs, four
comment mismatches, and one ASCII-escape source mismatch. These are recorded as
legacy migration differences, not accepted as Foundation truth.
An XML property-list fixture has eight canonical messages and **zero** Okapi
units. A brace-wrapped OpenStep fixture has three native messages but only one
malformed legacy unit, with all three real IDs missing. Both discrepancies have
independent Java/Rust shadow snapshots.

`.stringsdict` fixtures cover plural variables, multiple independent variables,
value-type metadata, positive presentation-width variants, supported device
variants, combined plural/device rules, invalid property-list shape, and
required ICU `other`. Width/device spellings remain lossless metadata while the
canonical default is chosen deterministically from the widest entry or iPhone
preference. Apple's standard public plist DOCTYPE is stripped only from the
document prolog before secure parsing; unsafe internal/external entities remain
blocked, and DOCTYPE-looking CDATA remains untouched.

Source-preserving XML `.stringsdict` sidecars independently own selected
single-variable and selector-qualified independently varying plural categories,
widest-width values, and preferred-device values while retaining the original
DOCTYPE, comments, XML attributes,
formatter declarations, typed metadata, and protected alternative branches.
Original neutral fixtures include escaped supplementary message keys,
self-closing strings, literal CDATA, translated `]]>` boundaries, UTF-8 BOM,
both UTF-16 byte orders, and CRLF. Real `plutil` verifies both full property-list
snapshots, and Swift Foundation executes original and translated plural
resources from actual bundles, including all four combinations of independent
positioned selector categories. Binary source ownership remains explicitly
unsupported.

Additional original Foundation-confirmed plist boundaries:

- Presentation-width keys may contain leading zeros; `048` remains the exact
  native key while its numeric presentation width is `48`.
- `NSStringFormatValueTypeKey` is optional, and different plural variables
  within the same message may specify different integer/floating value types.
- Xcode emits `%1$#@name@` positioned plural markers in compiled Foundation
  dictionaries. Multiple selectors can be reordered or repeated; ordinary
  `%3$@` arguments and positional string arguments inside plural branches must
  retain their own native positions. A declared numeric value type supplies the
  selector even when plural branches spell their counts out. Foundation and
  both ICU runtimes accept digit-leading and underscored plural identifiers.
- `plutil` accepts Unicode plural dictionary keys, but actual Foundation
  formatting does not expand Unicode or emoji markers such as `%1$#@信号@`.
  Xcode sanitizes
  Unicode modern-catalog substitution names when generating legacy dictionaries;
  directly authored legacy Unicode markers are rejected as runtime-unsafe.
- Structurally accepted zero/overflow/conflicting marker positions, undefined
  or unused plural definitions, nonnumeric value types, missing selector
  formatters, and invented plural categories fail with stable portable errors.
- Mojito's actual legacy Okapi stringsdict filter emits 48 detached branch
  units from the original eight-message positioned fixture and silently retains
  only one plural variable per independently varying sentence. Independent
  Java/Rust shadow comparators agree on 38 projected canonical units, 48 legacy
  units, two missing complete messages, and twelve unexpected branch IDs.
- Future device identifiers are accepted by Foundation; when none of Apple's
  documented device identifiers are present, the canonical fallback uses
  deterministic Unicode-scalar key ordering.
- When a single entry combines plural formatting with
  `NSStringDeviceSpecificRuleType`, actual macOS Foundation selects its
  matching device string instead of executing any plural branch. An independent
  pure-plural fixture verifies that translated plural categories still execute.
- Numeric XML carriage-return entities retain real carriage returns; XML
  ampersands, angle brackets, supplementary Unicode, and literal DTD-looking
  text must retain identical Foundation dictionary values.
- Normalized writers preserve exact localized patterns, source-native plural
  definitions/format placeholders, width/device dictionaries, and optional
  value-type omission. Both Java/Rust output bytes must match, Foundation must
  decode the normalized XML to exactly the source dictionary, and its actual
  formatter must produce identical plural selections from both bundles.
- Forbidden XML controls, missing plural `other`, source-format mismatches,
  and unsupported translator descriptions fail with stable diagnostics.

`.xcstrings` fixtures cover source-language detection, extraction state,
per-locale values/states, source and translated device axes, device-nested
source plurals, source substitution metadata, plural categories, and
lossless preservation of otherwise unknown variation axes. Although
`xcode-select` currently points to Command Line Tools, the host also contains
full Xcode 26.6; invoking its installed `xcstringstool` binary directly compiles
every source and normalized fixture into native locale-specific resources.
Foundation `plutil` then decodes those resources into implementation-neutral
snapshots.

The independently implemented Xcode source-preserving sidecar owns only native
source-locale `stringUnit.value` JSON string interiors. Original root/descriptor
metadata, escaped message-ID spellings, whitespace/indentation, review states,
all translated locales, unselected device branches, nontranslatable entries,
and JSON property order remain byte-identical. Source scalar values, all
selected source plural categories, default-device values, and default-device
nested plural branches have stable message/category identities; native
positional placeholders are restored before JSON escaping. Real `xcstringstool`
accepts and compiles UTF-8 BOM, UTF-16LE/UTF-16BE BOM, and CRLF variants;
complete original/localized resource snapshots enforce source semantics and
unchanged translated/native fallback branches. Referenced plural substitution
trees have independent `id#selector#category` source slots, including Unicode
names, repeated/positioned root markers, and localization-root definitions
shared by multiple device branches. Missing/duplicated markers and device-local
substitution definitions fail closed. Null-source fallback IDs still have no
source-owned value and remain manifest-declared
`UNSUPPORTED_SKELETON_SOURCE` cases.

Android's separate source-preserving sidecar optionally retains its exact
original resource path and ordered mutable-flag declarations. This context is
necessary because `values-*` locale/configuration and `flag(...)` directory
gates are absent from the XML bytes themselves. Original neutral fixtures
verify `values-fr-night` locale precedence, compiler-implied `fr-night-v8`,
directory-gated scalar/generic/product/array/plural identities, UTF-16 source
ownership, unchanged nontranslatable references, original `.arsc.flat` names,
and native original/localized default/tablet package selection. Separate
SDK-10000 templates retain compacted fixed/mutable array-item positions across
generic arrays, bags, products, references, primitives, self-closing values,
and ignored root/plural feature annotations without rewriting protected source.

Original Xcode-confirmed catalog boundaries:

- Root `version` is mandatory but accepts either a JSON string or JSON number,
  including integral and fractional revisions. Both parsers and normalized
  writers retain the exact native JSON type; booleans and null are rejected.
- Every `stringUnit` requires a textual `state` and `value`. Documented
  `machine_translated`, ordinary review states, and unknown future state strings
  are preserved unchanged for source strings, translated strings, and individual
  plural/device branches.
- Optional descriptor `comment`, `extractionState`, and `shouldTranslate` may
  be JSON null. Null means absent, including an implicitly translatable
  `shouldTranslate`; string/numeric booleans and nontext comments or extraction
  states are rejected. Unknown root/descriptor metadata remains lossless.
- A null source localization falls back to the message ID while retaining real
  translated locales. Null translated locales produce no canonical translation
  or regenerated native resource, matching Xcode's compiled output. Missing,
  scalar, or array localization maps and nonobject localization descriptors are
  rejected.
- Unknown future device names are preserved exactly. If no documented Apple
  device is present, the selected canonical fallback is the first Unicode
  scalar-ordered key, including supplementary-plane versus private-use keys;
  Java UTF-16 insertion order cannot decide this fallback.
- Xcode accepts arbitrary plural category names, including uppercase strings,
  invented future names, and `=0`, and writes them unchanged to Foundation
  dictionaries. Portable catalogs deliberately reject these with
  `INVALID_PLURAL_CATEGORY`: only the six CLDR cardinal categories have native
  Foundation plural semantics. A plural must also reference at least one
  numeric native format conversion.
- Xcode also accepts empty source languages, empty message IDs, entirely empty
  or all-null localization sets, and empty variation maps. These either violate
  the versioned canonical schema or compile to no native resource, so explicit
  `xcstringsOracle: "accept"` fixtures record their safer portable rejection.
- Source-language/translated locale spelling is preserved even when canonical
  locale identifiers normalize underscores to hyphens. Two source locale keys
  that collide after normalization are rejected despite Xcode accepting both.
- Empty device axes produce no compiled resources and plurals missing `other`
  still compile; both portable parsers reject those unsafe ambiguities.
- Sibling `plural` and `device` variation axes compile nondeterministically:
  repeated Xcode invocations can emit either plural rules or device rules from
  identical source bytes. Canonical parsing rejects this layout while retaining
  deterministic nested device-to-plural variations.
- Actual `%#@name@` substitutions compile into Foundation
  `NSStringLocalizedFormatKey` plus independent plural dictionaries. Multiple
  substitutions form the Cartesian product of their selected variants, repeated
  names reuse one selector, positional arguments retain `argNum`, and a missing
  argument number remains valid. Xcode silently sanitizes non-ASCII names in
  compiled native dictionaries; canonical ICU names retain their safe original
  Unicode spelling while normalized catalogs reproduce exactly the same native
  sanitization.
- Device-specific string branches inherit substitution definitions from their
  enclosing localization; definitions inside a device branch are ignored and
  cause native undefined-substitution errors. Both independent parsers resolve
  the parent-owned definition, preserve every device branch in normalized
  output, and reject device-local definitions exactly as Xcode does. Source
  templates translate only the selected device sentence plus its shared
  root-owned plural branches, leaving other device sentences and target locales
  byte-identical. Actual Mac Foundation execution proves protected desktop
  sentences pick up the independently translated shared plural values.
- Implicit format conversions inside a plural substitution begin at the
  substitution's `argNum`, then advance normally for subsequent string or
  numeric arguments; explicit `%n$` positions retain their original order,
  including strings preceding the plural argument. Both parsers map only the
  numeric argument at the selector position to the plural variable and retain
  later conversions as their independent canonical arguments. Xcode compiles
  same-position type collisions and string-valued plural selectors despite
  their incompatible native runtime contracts, so portable extraction safely
  rejects both with `INVALID_PLACEHOLDER`.
- Both native implementations expand referenced substitutions into nested
  FormatJS/ICU plurals while retaining source and translated substitution trees,
  review states, original format spellings, and deterministic normalized
  catalogs. Real FormatJS and ICU4J verify every combination of two selectors
  plus Unicode, repeated, and implicit-position runtime selections.
- Xcode accepts missing substitution `other`, zero/negative argument positions,
  nonnumeric plural formats, punctuation-bearing variable names, and markers
  with no definitions. Portable extraction rejects them explicitly to avoid
  invalid ICU messages, argument ambiguity, silent identifier rewriting, or
  broken native substitutions. Fractional/string argument numbers, nonexistent
  references, and nonobject definitions also fail in Xcode itself.
- Source `shouldTranslate=false` entries still compile into the source locale
  even though they are excluded from the translatable canonical catalog. Such
  cases use an explicit normalized compiler snapshot for the intentional
  extraction omission.
- Both writers preserve substitutions, nested source devices/plurals, machine
  and future review metadata, locale-specific strings, source placeholder
  spelling, unknown metadata, numeric/string catalog versions, and
  Unicode-scalar JSON key order. Conflicting or nonstring/nonnumeric catalog
  version metadata fails with a stable writer diagnostic. Xcode's actual
  compiled `.strings`/`.stringsdict` dictionaries must match.

## GNU gettext PO/POT

Sources:

- GNU's complete PO-file format index:
  <https://www.gnu.org/software/gettext/manual/html_node/PO-Files.html>
- Official PO entries, translator/extracted comments, references, flags,
  previous IDs, context, and translation fields:
  <https://www.gnu.org/software/gettext/manual/html_node/PO-File-Entries.html>
- GNU's previous-context and previous-message history grammar:
  <https://www.gnu.org/software/gettext/manual/html_node/Entries-with-Context.html>
- GNU's current `#=` sticky-flag transition and future workflow compatibility:
  <https://www.gnu.org/software/gettext/manual/html_node/Sticky-flags.html>
- GNU's domain-aware PO API and implicit `messages` default domain:
  <https://www.gnu.org/software/gettext/manual/html_node/po_005ffile_005ft-API.html>
- GNU's plural formulas, locale headers, escaped strings, and workflow flags:
  <https://www.gnu.org/software/gettext/manual/gettext.html>
- GNU's portable PO header charset declarations:
  <https://www.gnu.org/software/gettext/manual/html_node/Header-Entry.html>
- GNU's ASCII-compatible MO charset requirements:
  <https://www.gnu.org/software/gettext/manual/html_node/MO-Files.html>
- GNU's restricted C-expression grammar, plural-form counts, and locale rules:
  <https://www.gnu.org/software/gettext/manual/html_node/Plural-forms.html>
- Unicode's locale-specific integer plural categories:
  <https://unicode.org/cldr/charts/latest/supplemental/language_plural_rules.html>
- Unicode's plural operands, visible fraction digits, and integer-versus-decimal
  category rules:
  <https://unicode.org/reports/tr35/tr35-numbers.html#Language_Plural_Rules>
- FormatJS ICU plural categories, required fallback, and exact-value selectors:
  <https://formatjs.github.io/docs/core-concepts/icu-syntax/#plural-format>

Native oracle: `python3 file-formats/conformance/gettext_msgfmt_oracle.py`
compiles each original fixture with GNU `msgfmt --use-fuzzy --check-format`,
adds `--check-header` for fixtures declaring `Plural-Forms`, and then decodes
the resulting binary MO catalog independently. It injects a UTF-8 declaration
only into temporary copies whose source fixtures intentionally focus on other
syntax. Policy-rejected but GNU-accepted resources can additionally declare
`gettextLossyCompiled` to prove their actual truncated/empty MO output, while
`gettextNativeDomains` uses GNU `msgcat` to verify original and normalized
domain directives, including embedded-NUL domain truncation.

A separate source-preserving GNU PO sidecar keeps the entire original file and
assigns half-open byte ranges only to active singular/plural translation C
strings. Charset/language/plural headers, custom project fields, historical
`#|` messages, every comment/flag/reference, contextual source IDs, obsolete
`#~` entries, source ordering, original quote groups, indentation, and CRLF
remain byte-identical outside translated slots. Native plural indexes map to
their canonical primary category; contextual identities and placeholder
spellings survive target injection. UTF-8, ISO-8859-1, GNU CP1252, and
US-ASCII retain their source encoding. Original Windows euro/smart quote/dash
bytes and escaped `\\x80` survive physical wrapping and CRLF; French native
plural indexes retain their actual `one`/`many` ownership. Unmappable Latin-1,
CP1252, and ASCII targets fail closed instead of silently changing the source
header or encoding. GNU msgfmt
separately compiles original and translated PO and verifies their complete
native MO dictionaries.

Metadata and normalized output use Java's exact `Character.isWhitespace`
boundary. NEL, NBSP, figure space, and narrow no-break space remain literal
characters in references, flags, domain names, and header locales; 48
original/normalized MO snapshots and 24 real `msgcat` domain observations pin
that behavior. GNU
also accepts C0 file/group/record/unit separators, but Mojito deliberately
rejects 20 control-bearing output domains and refuses four unsafe references;
24 metadata mutations separately validate references, flags, domains,
domain-header locales/plural expressions, and qualified source identities.
Four UTF-8, ISO-8859-1, and CRLF source skeletons retain the complete original
Unicode domain, reference, flag, header, and untranslated source bytes.

Coverage includes source and target text, multiline strings, gettext contexts,
explicit and empty translation domains, multiline previous context/singular/
plural history, multiple reference lines, translator comments, extracted
comments, obsolete entries, both `#,` and modern `#=` sticky/workflow flags,
last-declaration-wins positive/negative format modes, unknown project flags,
Python named arguments, C escapes, octal/hex
escapes, byte-oriented UTF-8 sequences, French two-form plurals, Polish and
Russian three-form plurals,
current Hebrew three-form rules, Slovenian four-form plurals, and Arabic
six-form plurals. Original French, Brazilian Portuguese, European Portuguese,
Spanish, Italian, and Catalan three-form fixtures additionally distinguish
the CLDR `many` category at `1,000,000` and its multiples. Brazilian Portuguese
treats zero
and one as `one`, while European Portuguese treats only one as `one`; genuine
fractional inputs expose the same locale-specific split. Modern Hebrew and its
legacy `iw` locale alias both use the
current CLDR categories rather than the historical, now-invalid `many` form.
The independent Java and Rust parsers evaluate actual
`Plural-Forms` expressions with conditional, logical, comparison, arithmetic,
remainder, unary-not, and parenthesized operators, including both logical-AND
and logical-OR short-circuit protection around potentially unsafe division.
Declared form counts, complete indexed translations, invalid expressions,
out-of-range indexes, divide-by-zero, and deliberately reordered formula indexes
are checked against GNU `msgfmt`.

Mojito's actual existing extension mapper routes gettext `.pot` templates
through its custom Okapi filter but rejects ordinary `.po` paths. Differential
snapshots retain the filter's flattened six-category plural units, original
native source spellings, usages, and fuzzy/obsolete behavior without claiming
the existing production mapper already supports `.po`.
GNU's strict header checker observes only the inclusive `0..1000` integer
sample window. Both independent portable implementations retain that entire
window and add a shared, bounded set of representative values through
`1,000,000,000`, including `1,001`, `999,999`, `1,000,000`, `1,000,001`,
`2,000,000`, and large round boundaries. Native-accepted fixtures demonstrate
that GNU misses both an invalid plural index and a divide-by-zero appearing at
`1,001`; Mojito intentionally rejects those unsafe formulas. A separate valid
formula has a genuine `=1001` exact-selector override that GNU never samples
but both canonical implementations and ICU runtimes preserve.

Actual GNU `msgfmt --check-header` accepts only ASCII space and horizontal tab
between plural-expression tokens or around the expression itself. Vertical
tab, form feed, carriage return, the four ASCII file/group/record/unit
separators, C1 next-line, Unicode no-break/typographic/ideographic spaces,
zero-width space, and Unicode line/paragraph separators all produce `invalid
plural expression`; Unicode-aware Java `Character.isWhitespace`, Rust's
`is_ascii_whitespace`, and either language's general-purpose `strip`/`trim`
would therefore accept invalid resources. GNU also requires `nplurals=` and
`plural=` without whitespace before the equals sign. Four accepted original
formulas and 61 native-rejected separator/assignment cases pin the precise
token boundary in both implementations. A byte-preserving original/translated
PO source skeleton independently retains its escaped horizontal tabs and
parenthesized formula while updating both native plural branches.

GNU's documented decimal grammar treats arbitrarily long runs of leading
zeroes as insignificant: the real `msgfmt` accepts 512-zero `nplurals`
declarations and 512-zero expression literals when their significant values
remain valid. Parsing directly into Java `int`/`long` or Rust `usize`/`i64`
therefore rejects genuine native catalogs. Both implementations strip only
insignificant decimal zeroes before bounded numeric conversion while retaining
the original expression and source-template bytes; significant arithmetic
overflow still fails closed. The independent FormatJS oracle separately
normalizes only its JavaScript BigInt token syntax because `01n` is invalid
even though GNU's original decimal literal `01` is valid; canonical metadata
and source bytes remain untouched. GNU silently uses the first duplicate count or
formula and tolerates decimal-looking/exponent/word suffixes, trailing Unicode
spaces, and even a second conflicting or malformed declaration. The portable
contract intentionally rejects those ambiguous/non-decimal headers rather
than relying on first-declaration accidents. Eight valid native snapshots,
23 stable invalid/deliberately stricter cases, and a second translated
byte-exact PO source template pin all of these decisions.

GNU's native identity is domain plus optional context plus source ID. Identical
source IDs and contexts are therefore legal in genuinely distinct domains when
`msgfmt` emits separate `<domain>.mo` files; compiling those same entries with
`msgfmt -o single.mo` incorrectly collapses the domains and reports duplicates.
The omitted domain and explicit `domain "messages"` are one effective domain;
explicit `domain ""` is distinct. Canonical IDs remain unchanged until they
collide across distinct effective domains, at which point both independent
parsers append reversible `@domain=` UTF-8 percent escapes and preserve
`gettextOriginalId`. An existing genuine source ID that collides with such an
identity is rejected instead of overwritten.

Domains also own independent `Language` and `Plural-Forms` headers, exposed as
typed `gettextDomainHeader` metadata. Mixed-locale catalogs omit a misleading
catalog-wide locale, and each domain's French, Russian, or English translations
use its own CLDR mapping. Unicode and literal-percent domain names round-trip;
whitespace and path separators are rejected before split native output. The
native oracle decodes every real domain-specific MO independently and executes
actual `GNUTranslations.ngettext()` selections, while FormatJS and Java ICU
replay the same arguments and expected translations. Java ICU groups bare
French numeric arguments by default even though GNU `%d` and FormatJS do not;
`::group-off` makes the integrated runtime assertion faithfully match native
one-million-count output.

Native GNU also preserves project/translator headers as ordered physical data,
including repeated or mixed-case field names, Unicode and empty names, empty
values, and folded continuation lines. The canonical
`gettextDomainHeader.fields` array preserves those values independently for
each domain while reserving `Content-Type`, `Language`, and `Plural-Forms` for
their typed normalized representation. Duplicate reserved `Language` fields
use GNU's final effective value. Normalized UTF-8 writers preserve custom field
order and multiplicity, fold continuations safely, and retain native project,
revision, translator, team, MIME, transfer, and generator metadata. A native
continuation targeting a reserved field, forged reserved writer metadata,
colon-bearing names, CR/NUL injection, or continuation text that would become a
separate header is rejected with `INVALID_GETTEXT_DOMAIN_HEADER` even though
GNU itself accepts certain unsafe source spellings.

Additional GNU-confirmed parser and writer boundaries:

- GNU's lexer accepts horizontal tabs, vertical tabs, form feeds, optional
  directive whitespace, adjacent quoted fragments, multiple directives on one
  physical line, bracket-separated plural indexes, LF/CRLF backslash splicing,
  and explicit domain changes. Java and Rust independently preserve effective
  domains as `gettextDomain`, including the distinction between implicit
  `messages`, explicitly empty directives, and named domains. Normalized output
  groups domains deterministically, emits each domain's independently owned
  native header, and preserves every separately compiled MO dictionary.
- Bare-CR-only files are more dangerous: GNU accepts them but does not treat
  carriage returns as comment-terminating physical newlines, so the first
  translator comment swallows the remaining catalog and the compiled MO
  contains only its header. Portable parsing explicitly rejects the
  continuation-bearing original rather than pretending its dropped messages
  are valid.
- Previous `#| msgctxt`, `#| msgid`, and `#| msgid_plural` history, including
  separately continued C strings, survives as typed `gettextPrevious` metadata
  and is regenerated in native grammatical order. Missing IDs, orphaned
  continuations, reordered fields, and duplicate previous IDs fail safely.
- GNU gettext added `#=` as an accepted flag-line spelling in 2025. Both
  portable parsers combine `#=` and `#,`, preserve project-owned unknown flags,
  collapse duplicate flags, and apply native last-wins polarity between
  `language-format` and `no-language-format`. Writers normalize both spellings
  into the currently documented interoperable `#,` form.
- GNU accepts escaped NUL bytes in domains, contexts, source IDs, and
  translations but silently truncates those values in its compiled MO output.
  Both implementations deliberately reject them as `INVALID_GETTEXT_NUL`;
  native-acceptance policy cases prove the platform disagreement explicitly.
- Repeated singular translations and repeated indexed plural translations are
  rejected by actual `msgfmt`, rather than being silently overwritten.
- Hexadecimal/octal escapes represent encoded bytes, not Unicode scalars:
  `\xC3\xA9` and `\303\251` each decode to `é` under a UTF-8 header. Treating
  each byte as a code point silently produces incorrect mojibake.
- The declared `Content-Type` charset controls both raw PO bytes and escaped
  bytes. Original fixtures cover strict UTF-8, US-ASCII, lowercase/underscore
  ISO-8859-1 aliases, and GNU's portable `CP1252` spelling. Raw and escaped
  CP1252 `\x80` become the euro sign, smart punctuation follows the real
  Windows code-page table, and a UTF-8 file mislabeled Latin-1 intentionally
  produces the same mojibake as actual GNU.
- GNU rejects `UTF8`, `latin1`, and `WINDOWS-1252` despite equivalent names
  existing in generic charset libraries. Unsupported names, malformed spacing,
  invalid bytes, undefined CP1252 positions, and UTF-8/UTF-16 byte-order marks
  fail with stable shared diagnostics. Accepted legacy inputs normalize to
  deterministic UTF-8 while preserving the native MO dictionary.
- GNU `msgfmt` accepts an isolated high byte such as `\xE9` and can write a
  binary MO payload that cannot be decoded as its declared UTF-8 charset. Both
  portable parsers reject that unsafe case with `INVALID_GETTEXT_ENCODING`.
- Unknown C escapes are rejected rather than silently dropping the backslash.
- GNU normally excludes fuzzy messages; normalized parity therefore compiles
  with `--use-fuzzy` and retains the original `fuzzy` flag.
- An empty singular translation has no MO entry, while an empty indexed plural
  translation remains a present empty MO value. Canonical untranslated-state
  metadata preserves both outcomes.
- Literal `%%`, positional `%2$n`, Python named formats, literal newlines, and
  formatter-disabled `no-c-format` text retain their original source spellings.
- Normalized UTF-8 PO headers retain the exact locale and original plural
  expression; original binary MO entries, contexts, and plural-index values
  must match after recompilation and strict header validation.

Policy differences are explicit:

- The canonical message ID is gettext context when present. Two entries with
  the same context therefore collide even if GNU considers their full
  `(context, msgid)` pairs distinct; Mojito rejects the collision.
- GNU omits untranslated messages from compiled MO output. Mojito retains
  them and uses source text as the canonical fallback while recording their
  untranslated state for normalized native regeneration.
- GNU accepts overflowing arithmetic and trailing garbage after `nplurals`.
  Both native Mojito parsers intentionally reject those unsafe or malformed
  headers; manifest oracle policies record the platform disagreement.
- GNU also accepts formulas whose indexes become invalid or whose arithmetic
  divides by zero just outside its checked range. Portable validation rejects
  those cases with `INVALID_GETTEXT_PLURAL_FORMS`; the manifest marks GNU's
  acceptance explicitly.
- GNU plural indexes are mapped into locale-specific CLDR/ICU categories by
  evaluating the declared formula over representative integer values, rather
  than assuming conventional index order. When several gettext indexes share a
  CLDR category, the dominant index retains its named category while exceptions
  use ICU exact-value selectors such as `=0` and `=2`. When one gettext index
  spans several CLDR categories, each category receives the same translated
  variant and metadata records every selector associated with its source index.
  The exact plural expression and declared form count remain available for
  loss-aware round-trip writers. French and Portuguese two-form catalogs retain
  a shared translation for both `many` and `other`, while proper three-form
  catalogs preserve their distinct million-group translations.
- Gettext evaluates nonnegative integer counts only. Canonical ICU descriptors
  can additionally receive fractions, so manifest-declared fractional probes
  separately verify real FormatJS and ICU4J category selection: French and
  Brazilian Portuguese map `1.2` to `one`; European Portuguese, Spanish,
  Italian, and Catalan map
  it to `other`; Russian and Arabic select `other`; and Slovenian selects `few`.
  Fractional behavior is an explicit canonical-runtime extension rather than a
  claim that GNU's integer formula accepts decimals. JavaScript numeric inputs
  cannot preserve the visible trailing-zero distinction between `1` and `1.0`,
  so those text-sensitive operands remain outside the numeric runtime contract.
- ICU4J and the real FormatJS runtime independently verify every integer across
  GNU's window, all bounded large-number probes, manifest-specific exact
  selectors, and declared fractional selections.

## Java `.properties`

Sources:

- JDK `Properties.load`/`store` grammar, odd-backslash logical-line continuation,
  ASCII whitespace delimiters, escaped separators, Unicode escapes, comments,
  writer escaping, and legacy ISO-8859-1 byte handling:
  <https://docs.oracle.com/en/java/javase/24/docs/api/java.base/java/util/Properties.html>
- OpenJDK's actual natural-line reader and `saveConvert` writer implementation:
  <https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/Properties.java>

Native oracle: `python3 file-formats/conformance/java_properties_oracle.py`
compiles a tiny independent helper and loads each source fixture with the
actual JDK `Properties.load(Reader)` parser, then compares decoded keys and
values with language-neutral snapshots. Opt-in normalized fixtures load both
the original input and deterministic UTF-8 output and require their actual JDK
key/value dictionaries to be identical.

Coverage includes `=`/`:`/whitespace delimiters, keys without delimiters, escaped
leading whitespace and separators, odd/even backslashes, physical-line joins,
unknown escapes, tab/newline/form-feed escapes, supplementary Unicode surrogate
pairs, Unicode scalar/private-use ordering, UTF-8, and explicitly selected
ISO-8859-1.

Original JDK-confirmed boundary cases include:

- Only ASCII space, tab, and form feed are property whitespace. NBSP, EM SPACE,
  and similarly visible Unicode whitespace remain part of keys; a `#` following
  Unicode whitespace is not a comment introducer.
- LF, CR, and CRLF terminate natural lines. U+2028 and U+2029 remain ordinary
  value characters rather than Apple-style line/comment terminators.
- A comment ending in an odd backslash never continues into the next property.
- A final unpaired backslash is consumed at EOF, including inside a key, an
  empty value, or the last segment of a continued key. Three terminal slashes
  leave exactly one literal backslash. A source file consisting only of an
  escaped continuation yields an empty JDK key and is intentionally rejected.
- Continuation indentation disappears before key/value splitting. A key whose
  final continued line contains only spaces, tabs, or form feed therefore has
  no implicit separator even though the final physical source byte is
  whitespace. Source templates insert `=` by inspecting the logical key rather
  than that byte, while actual whitespace-delimited and explicit `=`/`:` values
  do not acquire invented separators. Escaped key separators, multiple joins,
  comment-looking `#` content, LF, CR, and CRLF remain byte-preserved.
- Java `String.isBlank` does not classify NBSP, FIGURE SPACE, NARROW NO-BREAK
  SPACE, or U+0085 as whitespace. Escaped keys containing only those code
  points remain native-valid identities; escaped ASCII/EM whitespace does not.
  Independent Rust identity checks use the exact Java whitespace predicate.
- Unknown `\b` removes its slash and produces ordinary `b`.
- A Unicode escape permits exactly one `u`; repeated `\uu0041` is invalid.
- Supplementary escaped Unicode requires a complete surrogate pair. The JDK
  can accept isolated UTF-16 surrogates, but both portable parsers deliberately
  reject them because they cannot safely survive canonical UTF-8 JSON.
- Literal raw `%`, printf `%%`, and `%n` have distinct native strings even when
  their normalized FormatJS text overlaps. Scalar-position metadata preserves
  those distinctions through independent Java/Rust writer round trips.

Normalized writers match JDK `store(Writer)` escaping without nondeterministic
timestamps: all key spaces are escaped, only leading value spaces are escaped,
punctuation/comment introducers are protected, standard controls use short
escapes, supplementary Unicode remains raw UTF-8, and keys sort by Unicode
scalar. For safety, other C0 controls and DEL use `\uXXXX` even though actual
`store(Writer)` emits those bytes raw; native JDK parsing proves their decoded
values remain identical. Line-separator metadata records scalar positions and
the exact original conversion so mixed escaped newlines and positional `%2$n`
survive without collapsing into plain `%n`.

A separate source-preserving sidecar keeps the original byte source rather than
reconstructing JDK output. Comments, escaped/continued keys, Unicode key
escapes, `=`/`:`/whitespace delimiter spellings, empty key-only declarations,
NBSP identities, declaration order, LF/CR/CRLF line endings, and untouched
whitespace remain exact. Original odd-backslash value continuations retain
their physical line breaks and indentation after translation. UTF-8 and
explicit ISO-8859-1 source encodings survive; unmappable legacy target
characters become native `\uXXXX` escapes, including supplementary surrogate
pair. A final key/value slot can own the discarded terminal backslash and end
exactly at EOF; reinjection safely introduces `=` without changing its JDK
identity. No-op rendering is byte-identical, and JDK `Properties.load(Reader)`
independently verifies both original and localized dictionaries.

JDK `Properties` silently keeps the last duplicate and discards comments. Mojito
rejects duplicate keys and attaches preceding comments to the FormatJS
descriptor; this policy difference is declared in the manifest. Actual Okapi
also retains a final discarded key backslash, replacing the correct empty-value
message with an invented escaped identity; a second real differential records
the same wrong identity after a whitespace-only continuation tail. Multiline
descriptions and plural variants fail with stable writer diagnostics.

## Android XML and FormatJS

Android has a dedicated compiler-grounded inventory in
`android-syntax-inventory.md`. Android's official resource-reference syntax is
specified at
<https://developer.android.com/guide/topics/resources/providing-resources>;
its heterogeneous `<array>`/`TypedArray` values are documented at
<https://developer.android.com/guide/topics/resources/more-resources>;
AAPT2's actual package/private/theme parser and resource-type allowlist are at
<https://android.googlesource.com/platform/frameworks/base/+/b092c78a7a0e/tools/aapt2/ResourceUtils.cpp>
and
<https://android.googlesource.com/platform/frameworks/base/+/a3462cecdccc/tools/aapt2/Resource.cpp>.
Its root namespaces, `<skip>`/`<eat-comment>` controls, latest-comment ownership,
strict array/plural children, and ignored foreign resource declarations come
directly from
<https://android.googlesource.com/platform/frameworks/base/+/b092c78a7a0e/tools/aapt2/ResourceParser.cpp>;
the same primary parser and pinned real AAPT2 additionally prove undocumented
generic `<bag type="array|string-array|integer-array|plurals">` dispatch,
effective direct-resource behavior, preserved `androidBagType` writer identity,
generic/direct overlay replacement, and integer-bag tombstones. The public
heterogeneous-array guide does not document that generic XML spelling;
the current AAPT2 compile pipeline separately establishes the exact lowercase
`donottranslate` filename-prefix rule and whole-file pseudolocalization gate:
<https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/cmd/Compile.cpp>;
original `--pseudo-localize` compiler snapshots prove prefix-only, exact-case
behavior and private-file source-set tombstones.
the exact Unicode resource-entry validator is at
<https://android.googlesource.com/platform/frameworks/base/+/b092c78a7a0e/tools/aapt2/text/Unicode.cpp>.
Native probes additionally prove those generated XID tables reject
supplementary letters/CJK and join controls even though newer ICU/Rust Unicode
identifier properties can accept them.
Source-preserving Android product fixtures additionally compile and link
original/localized default, tablet, and watch alternatives; independent
Java/Rust scanners normalize padded names and explicit default-product identity
without editing original attributes or claiming colliding foreign namespaces.
Version-one sidecars optionally preserve ordered Android fixed/mutable flag
declarations; native-linked APK snapshots verify positive/negated
runtime-gated strings, generic items, arrays, plurals, product alternatives,
protected disabled declarations, and AAPT2's last-wins cross-product conditional
collision.
Native JSON accepts both plain FormatJS message
maps and descriptors, preserves existing ICU syntax and extra descriptor
metadata, and can re-ingest versioned canonical catalogs.

XLIFF remains intentionally deferred until bilingual segment state, inline code
equivalence, notes, skeletons, and version-specific round trips have an explicit
shared contract.
