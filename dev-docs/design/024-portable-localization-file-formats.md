# Portable localization file-format converters

## Goal

Replace the most important Okapi extraction dependencies with small native Java
and Rust converters that have provably identical observable behavior. A shared
fixture corpus, rather than a shared parser implementation, defines parity.

Correct native-platform behavior, source preservation, and safety take
precedence over matching legacy Okapi mistakes. The generated
`025-portable-localization-compatibility-ledger.md` records every real Okapi
comparison, measured extraction defect, intentional incompatibility, and
remaining portable-implementation gap; the shared verifier rejects stale
ledger evidence.

## Canonical catalog

A version-one catalog contains `schemaVersion`, `sourceFormat`, optional
`locale`, and a map of FormatJS-compatible message descriptors. Each descriptor
has `defaultMessage` and optional `description`, plural `variants`, normalized
`placeholders`, and format-specific `metadata`.

Native printf placeholders become named ICU/FormatJS placeholders. Original
placeholder spelling, position, type, and Android translator examples are
retained so a later writer can reconstitute each platform's format. Android and
Apple plural categories remain CLDR categories. Gettext plural indexes are
mapped into locale-specific CLDR categories by evaluating the file's actual
`Plural-Forms` expression, including nonconventional index ordering. A dominant
translation owns each CLDR category; additional translations within the same
category become exact-value ICU selectors, while several categories can share
one translation. Original indexes, selector aliases, declared form counts, and
exact source expressions remain in metadata so later writers can reconstruct
the native formula without losing variants.

The versioned wrapper permits evolving metadata without redefining the existing
`messages` descriptor map. Deterministic duplicate-key and malformed-input
errors prevent one runtime silently accepting data another rejects. UTF-8 and
UTF-16 decoders reject malformed input and unpaired surrogates rather than
silently inserting replacement characters; byte-order marks and explicitly
selected ISO-8859-1 remain portable fixture behavior.

Android theme-reference dependencies use a deliberately narrow source-skeleton
extension inside ordinary descriptor metadata rather than changing the catalog
wrapper. The version-one schema defines optional
`androidAttributeDependencies`: typed, deduplicated declarations containing a
native attribute name, normalized format mask, optional signed 32-bit integer
bounds, sorted enum/flag symbols, and generic `<bag type="attr">` identity.
Only attributes actually referenced by extracted array/plural slots enter the
catalog; neither declarations nor their symbols become translatable messages.
An optional `weak: true` marker distinguishes attributes declared only inside
an Android styleable group from ordinary strong `<attr>` declarations.
Companion `androidStyleableDependencies` preserve ordered
`<declare-styleable>` or `<bag type="declare-styleable">` groups, repeated
attribute entries, qualified framework references, inferred enum/flag symbols,
integer bounds, and generic declaration identity without changing schema
version one.

## Parser boundaries

Android XML uses real XML parsers, not expressions over raw XML. Java uses the
JDK DOM parser with external entities and DTDs disabled; Rust uses `quick-xml`
and rejects actual document-type events. Literal document-type-looking text in
CDATA or XML comments remains safe ordinary content. Both retain inline markup,
CDATA, XML entities,
`xliff:g` named placeholders, translator comments, `description`, string arrays,
CLDR plural groups, `formatted="false"`, and Android's special whitespace,
quote, Unicode, apostrophe, percent, and backslash escapes. Resources marked
`translatable="false"` are omitted when that attribute belongs to the enclosing
resource declaration; AAPT2 ignores the same attribute on individual array
items, so those item values remain translatable.

AAPT2 compiles Android plural bags without an `other` quantity and preserves
their incomplete category map, but those bags cannot become valid canonical
ICU/FormatJS messages: the real FormatJS parser requires an `other` clause,
and inventing one would change the resource's runtime fallback semantics. Both
independent readers and writers therefore reject this compiler-accepted input
with `MISSING_OTHER_VARIANT`; an original AAPT2 compiled-value snapshot pins the
deliberate safety boundary.

Both implementations enforce the same W3C XML document boundary rather than
trusting permissive native parsers. There must be exactly one document element;
only XML whitespace, comments, and nonreserved processing instructions may
surround it. An XML declaration appears at most once and only at the first byte,
with a supported version, ordered `version`/`encoding`/`standalone` fields, and
valid standalone values. Reserved `xml`/`xmlns` namespace bindings, empty
prefixed bindings, and duplicate namespace-expanded attribute identities fail
closed. Real AAPT2 surprisingly accepts trailing text, entity references,
CDATA, extra document elements, misplaced trailing declarations, and unsupported
XML versions; Foundation accepts those plus malformed declaration fields and
forbidden namespace bindings. Original native snapshots record that deliberate
portable safety difference for Android, `.strings`, and `.stringsdict`; Java's
secure DOM parser and Rust's independently validated `quick-xml` event stream
agree on stable `INVALID_XML`. Valid XML declarations, root-relative
comments/instructions, the correctly bound `xml:` prefix, original UTF-8 and
UTF-16 bytes, and localized AAPT2 snapshots remain source-template preserving.

XML names have a separate native boundary from general XML character validity.
Processing-instruction targets must begin with a real BMP XML NameStart
character; ASCII digits, hyphens, periods, combining marks, middle dots, join
controls, supplementary emoji, and empty targets are invalid starts. Combining
marks and middle dots remain valid after an ordinary initial character, as do
accented and CJK names. AAPT2 additionally rejects every colon-bearing
processing-instruction target even though XML and Foundation accept such
targets, so Android and Apple intentionally apply different native target
policies. Element and attribute qualified names must have zero or one colon,
nonempty valid NCName components, and a bound namespace prefix; malformed
leading/trailing/multiple-colon names are rejected even when the JDK DOM or
CoreFoundation would otherwise accept them. Foundation also accepts invalid
processing-instruction names, reserved `xml` targets, emoji/joiner starts, and
malformed qualified attributes. Original native snapshots explicitly record
44 accepted-Foundation/rejected-portable lexical safety boundaries; independent
Java/Rust parsers agree on stable `INVALID_XML`, while four original/localized
UTF-8 and UTF-16 source templates preserve valid accented, CJK, and Apple
colon-bearing instruction bytes exactly.

The exact Unicode boundary is older than the broad XML 1.0 Fifth Edition
`NameStartChar` intervals. Real Android Expat and JDK Xerces use the Fourth
Edition `BaseChar`, `Ideographic`, `CombiningChar`, `Digit`, and `Extender`
tables: legacy Greek, Hebrew, Arabic, Indic, Hangul, and CJK names remain
valid, but modern-only characters such as `U+02FF`, `U+0370`, `U+203F`,
`U+3001`, `U+9FA6`, `U+F900`, and `U+FFFD` do not become legal merely because
the newer W3C intervals include them. Rust independently embeds sorted legacy
NCName start/continuation ranges rather than depending on Java at runtime.
The valid `xmlns=""` default namespace undeclaration must clear inherited
namespace ownership instead of becoming a nonempty namespace; Android roots
and nested Foundation dictionaries can both reset it safely. A 310-case native
AAPT2/JDK/Rust differential now has zero disagreements; 426 original shared
fixtures additionally cover accepted starts, continuation-only combining marks
and extenders, modern-only rejections, 148 deliberately stricter Foundation
cases, namespace resets, and four byte-preserving UTF-8/UTF-16 templates.

The reserved `xml` prefix is also bound intrinsically to
`http://www.w3.org/XML/1998/namespace`, even when no `xmlns:xml` declaration
appears. AAPT2 treats `xml:resources` as an invalid root, ignores complete
`xml:`-prefixed top-level declarations/macros/controls, rejects namespaced bag
and attribute children, and treats `xml:`-prefixed inline elements as foreign
transparent wrappers instead of real styles, annotations, or XLIFF placeholders.
Only genuinely unnamespaced nested style elements retain native span ownership;
an ignored `xml:eat-comment` cannot consume translator context. Java's secure
namespace-aware DOM already followed this contract; Rust now seeds the reserved
binding independently before resolving any element. The original 33-case
AAPT2/Java/Rust differential has zero disagreements, with four exact
UTF-8/UTF-16 templates preserving ignored private declaration bytes.

The same portable boundary applies to every original Unicode scalar, not just
decoded element text. XML 1.0 permits only TAB, LF, CR,
`U+0020..U+D7FF`, `U+E000..U+FFFD`, and `U+10000..U+10FFFF`.
Independent Java and Rust implementations reject raw NUL, other forbidden C0
controls, and `U+FFFE`/`U+FFFF` wherever they appear: element values,
attribute values, comments, CDATA sections, and processing-instruction data.
Java also rechecks the parsed DOM so an XML 1.1 numeric character reference
cannot smuggle an otherwise forbidden control into a canonical catalog; Rust
applies the same rule directly to each reference event. Android's actual AAPT2
compiler rejects all these raw/reference forms, while Foundation accepts
150 independently captured invalid `.strings`/`.stringsdict` variants. Legal
XML 1.1 declarations with safe characters remain accepted, including original
and localized UTF-8/UTF-16 source-template snapshots.

XML declaration encodings are validated against the actual original bytes
before either independent parser constructs a character stream. Android accepts
case-insensitive `UTF-8`, `UTF-16`, endian-specific `UTF-16LE`/`UTF-16BE`,
`ISO-8859-1`, and genuine ASCII, but its real AAPT2 parser rejects convenient
`UTF8`, `UTF_8`, and `latin1` aliases, incompatible byte-order marks, and
opposite-endian labels. A declared Latin-1 document really is decoded as
Latin-1: UTF-8 bytes under that declaration intentionally become the same
mojibake as the actual compiler, while genuine single-byte resources retain the
correct accented text and exact source-template byte offsets. Apple additionally
accepts `UTF8`, `UTF_8`, and `latin1`; Foundation treats an existing UTF-8 or
UTF-16 byte-order mark as authoritative even when the XML declaration claims a
different or unknown encoding, and portable extraction preserves that
format-specific precedence for compatibility with existing real resources.
Without a byte-order mark, both Apple readers reject false UTF-16 and unknown
declarations. Native Foundation also accepts non-ASCII bytes despite an ASCII
declaration; portable parsers deliberately reject that contradictory data with
`INVALID_ENCODING` instead of silently changing text. Original and translated
Android/Foundation snapshots verify UTF-8, both UTF-16 endiannesses,
single-byte Latin-1 source templates, native alias differences, and Foundation
byte-order-mark override behavior.

W3C XML does not impose a 512-byte limit on declaration whitespace. Encoding
labels can therefore occur after hundreds of spaces, tabs, physical line breaks,
or mixed CRLF separators; native AAPT2 and Foundation still apply the actual
declared charset. A fixed-size probe previously caused both portable
implementations to accept false UTF-16/unknown declarations and reject valid
single-byte Latin-1 resources once the label crossed that boundary. Java and
Rust now independently scan through the real `?>` declaration terminator,
including original 65,536-character preambles. Native-backed fixtures cover the
480/511/512/513-byte transition, much larger declarations, actual Latin-1
octets, false/unsupported labels, and spaces/TAB/LF/CRLF. Original and
translated UTF-8, Latin-1, BOM-less UTF-16, and Foundation BOM-precedence source
templates preserve every declaration byte and correct downstream slot offset.

AAPT2 additionally autodetects both BOM-less UTF-16 endiannesses from an XML
declaration, a direct root, leading XML whitespace, a comment, or a processing
instruction; Foundation rejects those same documents. Version-one source
templates now distinguish `UTF-16LE` and `UTF-16BE` from their existing
BOM-bearing counterparts, retain exact supplementary-character/CRLF slot
offsets, and inject translations without adding an invented byte-order mark.
Independent Java and Rust implementations accept generic or correctly
endian-specific declarations, reject contradictory labels, and merge mixed
UTF-16LE/UTF-16BE/UTF-8 Android source sets using real native-linked overlay
precedence. AAPT2 silently accepts odd trailing bytes and unpaired surrogate
units after its document root; portable decoders intentionally reject both with
`INVALID_ENCODING` rather than allowing ignored or corrupted source bytes into
a supposedly lossless template.

Encoding ownership also extends through AAPT2's later selected-product pass,
not only the first resource parse. Both portable implementations previously
reparsed every selected-product source-set file as UTF-8 after successfully
decoding its original XML, which made valid mixed-endian UTF-16 and declared
Latin-1 overlays fail with `INVALID_ENCODING`. Java and Rust now independently
detect the native XML charset again for each product-selection input. Seven
original AAPT2-linked overlays cover tablet, default, watch, and combined
default/tablet selections across BOM-less UTF-16LE/BE, both UTF-16 BOMs, UTF-8
BOM, and actual ISO-8859-1 bytes. Two selected tablet/default multi-file source
templates additionally preserve every original and localized encoding,
byte-order mark, XML declaration, product-owned slot offset, protected branch,
winning source-set identity, and original/localized linked package snapshot.

Actual AAPT2 behavior takes precedence over simplified Android documentation.
At the end of an XML text segment, including immediately before inline markup,
its Unicode state machine accepts zero through four hexadecimal digits; a fifth
digit becomes ordinary text, while earlier nonhexadecimal text rejects the
resource. Escaped UTF-16 high/low surrogate units and pairs are silently
discarded, a segment-final backslash disappears, and `\r` becomes literal `r`
rather than a carriage return. Original neutral fixtures check real compiler
strings and UTF-16 style offsets alongside both independent implementations.

The AAPT2 oracle must also decode the compiler's actual diagnostic grammar,
not assume its output is escaped JSON or CSV. Both `dump apc` and linked
`dump resources` leave literal quotation marks unescaped inside scalar strings,
plural branches, comma-bearing array entries, and styled visible text. Native
span descriptors can themselves contain quotation marks. The verifier locates
validated style suffixes and uses each compiler-declared array entry count to
recover every original value without truncating quotes or dropping multiline
styled items. Original neutral resources exercise literal/native XML-entity
quotes, resource-reference boundaries, protected placeholder examples, nested
style attributes, actual linked APK values, normalized Java/Rust round trips,
and byte-preserving source templates with untouched comments and declarations.

Native resource recognition follows AAPT2's actual resource-type allowlist and
reference parser, including package-qualified aliases, private `@*` references,
all real resource types, dotted/dashed entry names, valid `@+id` creation,
qualified/private theme attributes, shorthand `?name`, `@null`, and `@empty`.
Standalone aliases are deliberately omitted from canonical translatable
messages. Array message IDs retain their original physical indexes even when
surrounding slots are references; each remaining descriptor preserves those
slots in `androidArrayReferences`. Plural quantities retain native reference
branches in `androidPluralReferences`; a referenced `other` is rejected
explicitly because the canonical ICU contract cannot invent a translatable
fallback. AAPT2 normalizes creation IDs and shorthand/private theme syntax in
compiled resources, while canonical metadata and regenerated XML preserve their
exact original source spelling. An original native-compiled fixture covers 15
standalone aliases, nine array-reference slots, and five plural references;
its normalized compiled snapshot explicitly records the omission of standalone
aliases. Unknown resource types, numeric-looking strings, invalid non-ID/private
creation, and invalid theme types stay translatable literals. Quantity
attributes are trimmed exactly as AAPT2 does, including XML-provided tabs and
line breaks but never Unicode EM SPACE or NO-BREAK SPACE. Those non-ASCII spaces
also preserve reference-looking values as ordinary text; using generic
Java/Rust Unicode trimming would silently disagree with the real compiler.
Escaped or quoted reference-looking text likewise remains an ordinary
translatable literal.

Generic `<item type="string">` is not necessarily a string value: AAPT2's
default `TYPE_ANY` parser converts unquoted booleans, decimal/hex integers,
colors, dimensions, floats, and fractions into native primitives. Those values
are omitted from canonical translatable messages; quoted numbers and escaped
colors remain ordinary strings. The explicit `format` attribute accepts exactly
`reference`, `string`, `integer`, `boolean`, `color`, `float`, `dimension`, and
`fraction`. Unlike default generic items, `format="string"` switches to the
native `ParseString` path, validates and honors `formatted`/`translatable`, and
rejects multiple non-positional substitutions. Empty format leaves `TYPE_ANY`
unchanged; ASCII-padded `" string "` activates the strict path. Canonical
`androidGenericFormat="string"` metadata lets Java and Rust writers regenerate
the same strict resource behavior. The native source snapshot records 12
primitive values, two aliases, and ten strings; the normalized snapshot
contains only the nine translatable string descriptors.

Android `<array>` is a distinct heterogeneous resource, unlike
`<string-array>`: its default `TYPE_ANY` permits translatable strings alongside
native booleans, integers, colors, dimensions, floats, fractions, and
resource/theme references. Each extracted message retains the original physical
index, `androidGenericArray=true`, and complete `androidArrayPrimitives` plus
`androidArrayReferences` slot maps. Explicit `<array format="string">` keeps
`androidArrayFormat="string"` while continuing to ignore array `formatted` and
item-level boolean attributes. Normalized Java/Rust writers reproduce original
typed-array identity, format, primitive spellings, references, and every sparse
position; all-primitive/reference arrays remain intentionally absent because
they contain no translatable descriptor. Native snapshots prove six source
arrays, nine primitive slots, seven reference slots, and two normalized arrays.
Source-set merging treats `<array>`, `<integer-array>`, and `<string-array>` as
the same native resource identity, allowing mixed arrays to replace lower
strings atomically and all-integer arrays to suppress stale translations.

AAPT2's undocumented generic `<bag>` element dispatches its ASCII-trimmed
`type` through the native `array`, `string-array`, `integer-array`, or
`plurals` parser. Generic bags consequently inherit their effective resource
type's `TYPE_ANY` primitives, references, strict string format, translator
comments, ignored controls, normalized products, parent translatability, and
plural rules. Canonical `androidBagType` retains the original declaration so
normalized Java/Rust writers regenerate `<bag type="...">` instead of
silently changing its syntax; malformed metadata fails with
`INVALID_ANDROID_BAG`. The compiler snapshot records five arrays, four native
primitive slots, two references, and one plural group. Independently linked
source-set overlays prove generic bag arrays/plurals replace direct resources
and integer-only bags tombstone lower string arrays.

AAPT2 also imposes namespace-sensitive document structure. The `<resources>`
root must be unnamespaced and cannot contain nonwhitespace top-level text;
foreign top-level declarations and all nested descendants are ignored. Arrays
and plurals accept only unnamespaced `<item>`, `<skip>`, and `<eat-comment>`
children. The controls consume no array positions, while root controls clear
pending translator context; consecutive comments replace one another and
foreign top-level declarations do not consume the latest pending comment.
Resource and product names trim ASCII whitespace before becoming canonical IDs,
collision identities, or overlay winners. Native resource identifiers require
Unicode `XID_Start` or `_`, then `XID_Continue`, `.`, or `-`; its generated
native tables nevertheless reject supplementary XID letters/CJK, supplementary
continuations, and join controls that newer Unicode identifier libraries may
accept. Missing names, digit-leading names, embedded slashes, emoji, and
Unicode-space padding likewise fail with a stable diagnostic. ICU4J and Rust's
Unicode regex engine independently apply the native BMP/join-control boundary.
An AAPT2-linked overlay proves padded names collide with their
normal spellings while ignored namespaced declarations cannot suppress lower
resources. Real Okapi extraction produces 15 units against 13 canonical units,
with 25 measured leaked/missing/comment differences.

Formatting validation is deliberately resource-type-sensitive. AAPT2 checks
multiple non-positional printf substitutions only for plain `<string>` values
and explicit `format="string"` declarations; styled strings, default generic
items, array entries, and plural branches accept them. Relative `%<s` reuse in
styled messages remains lossless. `formatted` is validated only on actual
string declarations and strict generic strings; `translatable` is validated on
those strings plus arrays, while irrelevant attributes on default generic
items, array items, and plural groups are ignored by AAPT2. Its actual `ParseBool`
accepts only the ASCII-trimmed `true`, `True`, `TRUE`, `false`, `False`, and
`FALSE` spellings; arbitrary mixed-case permutations are invalid, as are numeric
`0`/`1`. Canonical extraction additionally honors those three valid false
spellings on plural groups as a deliberate author-intent policy even though the
native compiler still emits them. Invalid mixed-case flags on generic/plural
resources remain ignored and do not suppress their visible text. Original and
normalized AAPT2 snapshots document intentionally omitted nontranslatable
resources; linked source-set overlays prove ignored mixed-case flags still
replace lower-priority plural/generic resources. A real legacy Okapi extraction
of the same original fixture produces 28 text units versus 20 canonical units:
it omits the generic declaration and leaks nine nontranslatable string, array,
or synthesized plural entries. The bounded migration-shadow snapshot records
those precise categories and message IDs without expanding metric cardinality.

AAPT2's positional check is a dedicated lexical scanner, not Java Formatter
parsing or the shared canonical-placeholder regex. It ignores `%%` and `%n`,
counts malformed `%_`, percent-plus-whitespace, Python named formats, width
without `$`, and optional `%<$s` reuse, then rejects mixed nonpositional
arguments. Any unmistakable legacy `Time.format()` directive from
`DFKMWZkmwyz` immediately accepts the resource instead, even when another
ordinary substitution is unpositioned. Actual Java `%tY`/`%tm` do not get that
shortcut and still require explicit argument positions. Java and Rust now
implement this scan independently; original neutral fixtures and native AAPT2
compiled/normalized snapshots cover plain and strict-generic resources plus
the explicit `formatted="false"` boundary.

Native AAPT2 accepts a terminal literal `%` and one malformed fragment such as
`%_`; escaped `%%` remains a different compiled string. Canonical FormatJS text
cannot distinguish those spellings by itself, so singular/array messages carry
typed `androidRawPercentOccurrences` and plural groups carry independent
`androidPluralRawPercentOccurrences` maps. Occurrence indexes stay stable across
supplementary Unicode, attributed/ordinary style markup, ICU angle quoting,
argument insertion, generic resource shapes, and multiple escaped/raw percent
combinations. Both normalized writers validate ordered in-range occurrence
metadata before restoring the exact original visible-text percent spelling,
independently of opaque annotation attributes and plural branches. Actual
AAPT2 compiled value/style snapshots prove source and normalized resources are
identical. Empty, duplicate, negative, noninteger, out-of-range, unformatted,
wrong-shape, or unknown-plural-category metadata fails with
`INVALID_ANDROID_PERCENT`.

Android's native `\\n` escape and Java Formatter's `%n` have distinct compiled
spellings even though both normalize to the same canonical newline. The legacy
`androidPrintfLineSeparator` boolean remains backward compatible for resources
whose newlines all came from `%n`; mixed sources additionally record
`androidPrintfLineSeparators` as zero-based canonical-newline occurrence
indexes. Plural resources use independent
`androidPluralPrintfLineSeparators` category maps, including categories with no
printf newline, so one branch cannot silently rewrite another. Occurrence
indexes survive supplementary Unicode, generic strings, arrays, attributed and
nested style spans, positional placeholders, and adjacent raw or escaped
percent signs. An escaped literal `%%n` is not a printf newline. Both writers
reject invalid ownership, unformatted resources, wrong resource shapes,
unsorted/duplicate/out-of-range indexes, and unknown categories with
`INVALID_ANDROID_LINE_SEPARATOR`. The native AAPT2 oracle also preserves real
multiline array and plural entries instead of flattening or dropping them.

AAPT2 records style-tag attributes directly in the native span name rather than
passing them through string-format parsing. Its
[span builder contract](https://android.googlesource.com/platform/frameworks/base/+/HEAD/tools/aapt2/ResourceUtils.h)
encodes each tag and its attributes separately from visible string contents.
Both converters therefore shield style attributes before normalizing printf
placeholders, literal percentages, and line separators. Attribute `%n`, `%%n`,
`%1$s`, `%_`, and lone percent fragments remain literal compiler-owned data;
they never create visible placeholders or consume percent/newline occurrence
indexes. Numeric XML attribute references for LF, CR, and TAB round-trip as
`&#10;`, `&#13;`, and `&#9;` rather than physical XML whitespace, preserving
the exact native annotation/font value. This applies
to nested spans, generic strings, arrays, plurals, `formatted="false"`, and
supplementary Unicode. The AAPT2 oracle reads APC dumps as bytes to preserve
carriage returns, supports multiline span descriptors, and corrects an older
snapshot that had silently mistaken native CR for LF.

AAPT2's actual
[span builder](https://android.googlesource.com/platform/frameworks/base/+/HEAD/tools/aapt2/ResourceParser.cpp)
concatenates annotation attributes as `annotation;key=value` without escaping
attribute values. Android's
[runtime StringBlock decoder](https://android.googlesource.com/platform/frameworks/base/+/c3bc12c484ef/core/java/android/content/res/StringBlock.java)
splits that representation at every semicolon. Consequently, a value such as
`north;alert=west` creates a second runtime annotation, a repeated key preserves
both native annotations in order, an assignment-free suffix is discarded, and
`north;;alert=west` produces the literal key `;alert`. Equals signs and commas
inside values remain data; AAPT2 sorts actual XML attributes before encoding
them. Canonical attributed markup still retains the original XML values, while
`androidRuntimeAnnotations` and plural-category
`androidPluralRuntimeAnnotations` expose the ordered native annotation sequence
only when runtime semantics differ from the source attributes. The independent
Java and Rust writers reject missing, reordered, duplicated, malformed, or
wrong-category metadata with `INVALID_ANDROID_ANNOTATION`. Native AAPT2
snapshots additionally preserve the exact encoded tag and its ordered runtime
annotations across strings, arrays, plurals, nested spans, and supplementary
Unicode. Normalized writers safely regenerate literal attribute `<` as `&lt;`.

The same unescaped native encoding affects Android's
[font and link runtime span decoder](https://android.googlesource.com/platform/frameworks/base/+/c3bc12c484ef/core/java/android/content/res/StringBlock.java).
It searches the entire encoded tag for `;height=`, `;size=`, `;fgcolor=`,
`;color=`, `;bgcolor=`, `;face=`, and link `;href=` in a fixed runtime order.
An otherwise ignored attribute can therefore inject a genuine absolute size,
foreground/background color, typeface, or URL; repeated names use their first
encoded value, and a semicolon inside a URL truncates the destination.
Canonical `androidRuntimeStyles` and per-category `androidPluralRuntimeStyles`
retain each differing span's ordered runtime effects without changing safe
existing messages. Normalized XML and independently generated AAPT2 snapshots
preserve the original and effective effects across nested spans, arrays, and
plurals. Malformed, missing, reordered, or wrong-category metadata fails with
`INVALID_ANDROID_STYLE`.

AAPT2 also accepts malformed numeric font attributes that throw
`NumberFormatException` when Android loads the compiled string. Both parsers
reject direct, semicolon-injected, sign-only, overflowing, and supplementary
Unicode-digit numeric spans with `INVALID_ANDROID_STYLE`; their native snapshots
explicitly retain the accepted compiled resource and runtime failure. Android's
numeric parser is Java's
[Integer.parseInt](https://docs.oracle.com/en/java/javase/18/docs/api/java.base/java/lang/Integer.html),
which accepts signed 32-bit values and `Character.digit(char, 10)` rather than
ASCII alone. Independent Java and Rust implementations therefore accept
Arabic-Indic, fullwidth, and other BMP decimal scripts while rejecting
supplementary-plane decimal digits, which are UTF-16 surrogate pairs at the
actual Android call site.

Android's
[Color.parseColor implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/graphics/java/android/graphics/Color.java)
is not CSS color parsing. It accepts exactly 23 case-insensitive platform color
names, opaque `#RRGGBB`, and explicit-alpha `#AARRGGBB`; unsupported names,
empty/whitespace-padded values, short hexadecimal, invalid digits, and
supplementary hexadecimal digits silently resolve to opaque black. Its
`Long.parseLong(..., 16)` also accepts BMP Unicode decimal/fullwidth hexadecimal
digits plus signed values, so `#+12345` becomes `#ff012345` and `#-00001`
becomes opaque white. Foreground `@android:color/...` resources can become
stateful `TextAppearanceSpan` colors; background references resolve their
default state, and explicit non-Android package references deterministically
fall back to black because lookup uses `Resources.getSystem()`. Typed nested
runtime-color metadata records normalized lowercase `#AARRGGBB`, literal versus
fallback mode, and system-reference/stateful/default-fallback provenance for
all color spans. Both independent writers reject tampered color channels,
fallback claims, span ordering, and stateful-reference metadata.

Android's
[StringBlock paragraph-span expansion](https://android.googlesource.com/platform/frameworks/base/+/c3bc12c484ef/core/java/android/content/res/StringBlock.java)
also changes the effective ownership of exact native `li` spans and font
`height` spans. The platform expands a start backward to the beginning of its
line and an end forward through the terminating LF, using UTF-16 code units;
Unicode line separators are not paragraph boundaries. A zero-width `<li></li>`
inside text consequently becomes a bullet over the entire surrounding paragraph.
A `<li>` with any attribute is not the exact built-in `li` style ID and creates
no bullet. Direct and semicolon-injected font heights use the same expansion,
including nested spans, arrays, plurals, newline-aligned edges, and emoji.
Typed `androidRuntimeParagraphSpans` and per-category
`androidPluralRuntimeParagraphSpans` preserve each changed span's original and
expanded UTF-16 half-open offsets. Native AAPT2 snapshots retain both the
compiler's inclusive span offsets and Android's derived runtime paragraph
boundaries. Missing, fabricated, reordered, wrong-kind, wrong-category, or
off-by-one projections fail with `INVALID_ANDROID_PARAGRAPH`.

Android build-time macros are actual linker substitutions, not translatable
resources or ordinary aliases. AOSP's
[macro resource parser](https://android.googlesource.com/platform/frameworks/base.git/+/211bec2871da597f9f3fd81df7faffea1754437e/tools/aapt2/ResourceParser.cpp)
records the original text, style spans, protected sections, and namespaces,
while its
[reference linker](https://android.googlesource.com/platform/frameworks/base/+/master/tools/aapt2/link/ReferenceLinker.cpp)
reparses the winning macro as though its contents appeared at the use site.
Both independent converters expand `<macro name="...">` and
`<item type="macro" name="...">` before classifying string aliases, generic
string items, styled spans, named `xliff:g` placeholders, plural quantities,
heterogeneous arrays, primitive slots, resource references, empty values, and
transitive macro chains. Actual native reference syntax includes plain
`@macro/name`, private `@*macro/name`, explicit application-package and private
package references, `res-auto` aliases, public/private package namespace
aliases, and aliases declared only on the original macro definition. The
optional application-package build context makes package ownership explicit;
qualified references fail closed without that context, and foreign-package or
missing definitions fail with a stable unresolved-macro diagnostic. Escaped
`\@macro/name` and the invalid-reference-shaped `@*:macro/name` remain literal
source strings. Definition-scoped namespace aliases survive transitive chains,
styled text, protected placeholders, string arrays, plurals, and cross-file
source-set overrides. Macro declarations themselves never produce catalog
messages. Their `translatable` and `formatted` attributes do not override the
use site's resource semantics; declared read-only false and read/write macro
flags likewise do not suppress an otherwise referenced expansion, although
unknown feature flags still fail native compilation.

A macro's XML namespace stack also governs ordinary resources embedded in its
body, not only references to other macros. AOSP's `MacroDeclStack` reparses
`@route:string/anchor`, `@*route:string/anchor`, and `?route:attr/beacon`
under their original definition-site aliases before linking. Both portable
implementations normalize local `res-auto`, public-package, and private-package
aliases to compiler-equivalent `@string/anchor` or `?attr/beacon`. Definition-only
prefixes therefore cannot leak into canonical `androidArrayReferences`,
`androidPluralReferences`, or normalized XML, even when the target binds that
prefix to another package. Definition-scoped `xliff:g` remains protected across
target-prefix shadowing, while unknown foreign wrappers retain visible text.
Cross-file and higher-priority source-set snapshots independently verify these
behaviors. A standalone `<attr>` declaration remains nontranslatable, but both
implementations preserve the exact typed declaration as a message-level
dependency when a retained array/plural slot contains `?attr/name`. AOSP's
[actual attribute parser](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/ResourceParser.cpp)
accepts absent or composite formats, integer `min`/`max`, inferred enum/flag
formats and child symbols, ignored control elements, and generic attr bags.
Java and Rust independently reproduce those native boundaries, reject malformed
formats/bounds/symbols/duplicates before writing, and normalize symbols, format
masks, and signed values deterministically. Source-set overlays preserve the
globally winning declaration even when the translated use occurs in another
file. Real AAPT2 now verifies original and normalized compiler/linker snapshots
for the formerly blocked definition-scoped theme macro.

Attribute `min`/`max` and enum/flag symbol values are parsed by the Android
framework's
[actual `ResTable::stringToInt` implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/androidfw/ResourceTypes.cpp),
not by general-purpose Java/Rust integer decoding. Plain digits always mean
decimal, even with arbitrarily many leading zeroes (`010` means ten and `08`
is valid). Decimal values must fit signed 32-bit range; only lowercase `0x`
activates unsigned 32-bit hexadecimal, whose high bit is preserved in the
canonical signed representation. Native grammar rejects `+`, negative hex,
uppercase `0X`, unsigned decimal overflow, 33-bit hex, and Unicode-padded
numbers. Formats `enum`, `flags`, or `enum|flags` can exist without symbols;
different names can intentionally share the same numeric value. Both
implementations independently match these rules and generate compiler-valid
normalized decimal symbols/bounds.

AOSP's `ParseDeclareStyleable` additionally calls `ParseAttrImpl` with weak
declaration semantics. A nested `<attr>` creates a local attribute resource
only when it supplies a format or enum/flag symbols; an untyped child is solely
an ordered reference, including framework names such as `android:label`.
Identical weak declarations merge, matching strong declarations replace the
weak definition, and incompatible weak/strong definitions fail. The styleable
group itself remains a `PUBLIC` compiled resource required for generated
`R.styleable` constants, but disappears from linked APK resource dumps. Both
independent parsers preserve the complete relevant ordered group, including
duplicate attribute positions and `res-auto`/application-package aliases, and
both writers regenerate the real group instead of silently converting its weak
members into standalone strong attributes. Actual AAPT2 compiled snapshots
verify group names and exact entry order; linked snapshots separately verify
resulting typed attributes, and cross-file/source-set fixtures prove global
group ownership. Safe writer mutations reject orphaned weak attributes and
invalid nested metadata. Native private `*android:` and `*local:` styleable
references normalize to the same framework/local identity without leaking the
private marker into the generated group. Attributes and styleables always
compile in the default configuration even when declared in `values-fr-night`;
AAPT2 emits explicit ignored-configuration warnings while the translated array
retains `fr-night-v8`. Dedicated original/normalized snapshots track both
configuration kinds so this platform-specific boundary cannot silently regress.

Macros are global within a linked resource configuration. Same-priority files
can supply a definition separately from its string use, and a higher-priority
macro definition rewrites both higher-priority strings and surviving
lower-priority aliases; duplicate same-priority macro definitions fail. Java
and Rust independently build the complete winning macro table before parsing
source-set resources, then preserve the actual resource declaration's source-set
provenance. Native compiled APC snapshots retain original macro names and
unresolved references; linked APK snapshots verify fully expanded strings,
UTF-16 styled-span positions, plural branches, typed primitive/reference array
slots, and source-set precedence. Normalized writers inline the resolved
values and use separate compiler snapshots because build-only macros disappear
while the linked runtime values remain identical.

Macro declarations are restricted to the effective default resource
configuration and require a default product. The AOSP
[link-command pipeline](https://android.googlesource.com/platform/frameworks/base/+/master/tools/aapt2/cmd/Link.cpp)
runs `ReferenceLinker` before `ProductFilter`, and the
[reference linker requires exactly one macro value](https://android.googlesource.com/platform/frameworks/base/+/master/tools/aapt2/link/ReferenceLinker.cpp).
Consequently, default-plus-tablet macro alternatives compile but abort with
`macro_values.size() == 1`, even when `--product tablet` is supplied: they are
not valid product-selectable macros. Named-only macro definitions instead fail
normal product stripping because no default exists. Duplicate macro identities
are compared by normalized `(name, product)` before rejecting unsupported
product variants, matching compile-time duplicate precedence. Cyclic aliases
segfault AAPT2, while runtime-gated macro directories and runtime-conditional
references abort with `bad_optional_access`. Manifest-declared native crash
signals and assertion text verify all four actual failure classes; both
portable parsers reject them deterministically without crashing.

Android's framework-level
[resource-name splitter](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/libs/androidfw/ResourceUtils.cpp)
also consumes an additional leading `@` after AAPT2 has already stripped its
reference marker. Therefore `@@string/name`, `@@color/name`, and
`@@macro/name` are actual resource or build-macro references, including XML
entity, CDATA, and comment-split spellings. Triple markers, quoted or escaped
values, unknown resource types, `@@null`, and `@@+id/name` remain text.
Independent Java/Rust parsers exclude standalone aliases, preserve exact
doubled array/plural reference metadata, and expand direct or chained doubled
macro uses before extraction. Byte-preserving skeletons retain the original
alias spelling, inline comments, macro definitions, protected nontranslatable
resources, and sparse array/plural slots. Native original, normalized, and
localized compiled/linked snapshots verify runtime equivalence.

The same framework helper does not reject whitespace inside an entry name.
AAPT2 compiles opaque references containing spaces, tabs, line feeds, carriage
returns, CRLF, nonbreaking spaces, and em spaces across ordinary/generic
strings, theme aliases, arrays, and plural branches, even though those names
cannot resolve at link time. Diagnostic snapshots retain literal controls and
multiline array entries rather than silently dropping them. Independent
Java/Rust normalized writers encode preserved CR as `&#13;`, preventing XML's
mandatory CR-to-LF normalization from corrupting a round trip. Byte-preserving
templates keep original XML entities, reference text, comments, and sparse
array/plural ownership unchanged.

AAPT2 ignores `xml:space` on resource roots and individual resource bodies;
even nonstandard values do not disable its ordinary ASCII whitespace collapse.
Actual unnamespaced style spans behave differently: AAPT2 serializes every XML,
Android, and foreign inline attribute using its local name only. Native
attribute iteration follows `(namespace URI, local name)`, so qualified
`android:size`, foreign `color`, `xml:space`, `xml:lang`, and annotation keys
become ordinary runtime style attributes. Independent Java and Rust parsers
strip these prefixes and use deterministic local-name ordering in canonical
markup, making normalized XML writer round trips stable. Original, normalized,
and localized AAPT2 snapshots independently verify actual font-size,
foreground-color, and annotation effects across scalar, generic, array, and
plural resources. UTF-8/UTF-16 source templates compare inline attributes by
their native local identity while retaining every original namespace prefix,
inline namespace declaration, attribute entity spelling, and untranslated byte.

AAPT2 also accepts distinct qualified attributes whose local names collide;
its native span may contain duplicate ordered annotation or font attributes.
The current canonical XML representation cannot preserve these duplicates
without either creating invalid XML or silently changing runtime effects, so
both portable implementations explicitly reject them until a lossless
per-occurrence namespace and writer contract exists. Separate executable
native-accepted fixtures and snapshots document this open gap. A further
real-Okapi differential proves that the existing ITS pipeline throws
`org.w3c.its.ITSException: Invalid value for 'xml:space'.` for a root attribute
value that actual AAPT2 accepts and ignores.

The platform documentation currently overstates Android whitespace collapsing.
[Android's string-resource guide](https://developer.android.com/guide/topics/resources/string-resource)
claims Unicode spaces collapse after XML parsing, but real AAPT2 9.3 preserves
all 16 tested non-ASCII boundaries, including next-line, nonbreaking, ogham,
Mongolian, en/em/figure/punctuation/hair spaces, zero-width space,
line/paragraph separators, narrow no-break, mathematical, ideographic, and
byte-order-mark characters. ASCII whitespace adjacent to those characters
still collapses; explicit quotes preserve their complete surrounding spacing,
and `\\u` escapes reproduce the same literal Unicode values as XML entities.
Both independent implementations intentionally use the compiler's ASCII-only
collapse predicate. Forty-nine original neutral values, normalized writer
round trips, original/localized AAPT2 snapshots, and separate byte-preserving
UTF-8/UTF-16 source templates keep this discrepancy executable rather than
silently following incorrect prose.

[XML attribute-value normalization](https://www.w3.org/TR/xml/#AVNormalize)
creates a separate round-trip hazard: literal TAB/LF/CR become spaces while
numeric character references preserve the original values. Android uses those
attributes for translator descriptions and `xliff:g example` context across
ordinary/generic strings, array entries, and plural branches. Both independent
writers encode controls as `&#9;`, `&#10;`, and `&#13;`, including CRLF,
preventing silent metadata and protected-example corruption. Real AAPT2
original/normalized compiled and linked snapshots also preserve newline-bearing
annotation spans; native dump capture remains byte-oriented because Python's
text mode would otherwise silently replace CR with LF. Source skeletons retain
the original quote style, attribute order, numeric entity spellings, XLIFF
examples, protected style spans, and untranslated declarations.

Real legacy Okapi extracts 17 unresolved units where the original canonical
macro-expanded catalog has 20: it entirely misses one generic string and two
heterogeneous-array text slots, while 15 other string, array, protected,
styled, and synthesized plural units retain the wrong unexpanded `@macro/...`
source. The original package/visibility/namespace boundary has 25 projected
canonical units versus 24 Okapi units: one generic declaration is missing and
22 private/package/alias/chained/styled/protected/array/plural sources retain
their unresolved native spelling.

Android protected sections are recognized by namespace URI rather than the
spelling of their XML prefix; foreign namespaced tags are ignored while their
visible content is retained, and unnamespaced `g` remains ordinary style markup.
Every namespaced `g` is protected even when its ID is absent or empty, matching
AOSP's actual parser; deterministic `_xliff0`, `_xliff1`, and later names make
anonymous literals and formatters explicit in the canonical catalog and
normalized writers. Numeric, accented, CJK, emoji, and combining-mark IDs are
accepted by actual FormatJS and ICU4J runtimes, including repeated protected
formatters in plural branches. Nested protected sections are rejected regardless
of whether either element has an ID. AAPT2 accepts whitespace/punctuation IDs,
ambiguous same-name literal or formatter bindings, and nested style spans, but
the portable contract rejects them explicitly: unsafe argument names cannot be
parsed by both message runtimes, conflicting bindings lose identity, and styled
protected sections require a future reversible inline-skeleton contract. A real
Okapi comparison captures 21 protected units with 21 legacy source mismatches
because the existing filter preserves XML wrappers instead of canonical FormatJS
placeholders.
Product-specific strings, plurals, and array items receive deterministic
`@product=<name>` ID suffixes plus `androidProduct` metadata; `product="default"`
keeps the normal base ID while recording the explicit fallback product.
An optional Android source-resource path validates ordered `values-*`
configuration qualifiers, safely rejects path traversal, derives legacy and
BCP-47 locales, and records the exact path/qualifier list on every descriptor.
Directory locales override `tools:locale`; mobile network normalization,
pseudolocales, grammatical gender, screen/device/night variants, legacy square
orientation/stylus touchscreens, physical pixel dimensions, case-insensitive
qualifiers, leading-zero aliases, and the three-letter-language interpretation
of `values-rUS` are verified against AAPT2's actual compiled configuration.
Pixel widths/heights and density-independent widths are narrowed to 16 bits;
physical width must remain at least its height after narrowing. Density first
rejects zero-valued 32-bit input, then narrows to 16 bits so numeric `65534dpi`
means `anydpi`, `65535dpi` means `nodpi`, and `65536dpi` disappears. `mcc000`,
invalid density, descending physical dimensions, and SDK versions above
`65535` produce the same rejection as the real compiler.

Modern AAPT2 also treats a values filename beginning with exact lowercase
`donottranslate` as entirely nonlocalizable. The compiler sets its default
string/array translatability to false and suppresses whole-file pseudolocale
generation, even when individual strings, arrays, generic bags, or plurals
carry `translatable="true"`. Both independent parsers return an empty
canonical catalog; native source-set declarations nevertheless remain real
overlay identities and tombstone lower translations. An internal substring,
uppercase initial letter, or mixed-case prefix has no suppression effect.
Actual `--pseudo-localize` snapshots independently prove both base left-to-right
and right-to-left configurations are absent only for the exact prefix. The
false default accepts non-positional substitutions; explicit true reenables
native format validation, and malformed booleans continue to reject.

Nontranslatable does not mean unchecked. Actual AAPT2 still rejects malformed
Unicode escapes, unescaped apostrophes, and nested `xliff:g` sections inside
explicitly protected scalar strings, generic format-string items, ordinary and
generic string arrays, ordinary and generic plural bags, and whole
`donottranslate*` files. Both portable implementations independently validate
these native string-builder rules before dropping the value from the catalog.
Ordinary style boundaries reset quote state; native protected `xliff:g`
boundaries do not. Unknown XLIFF elements and every other namespaced inline
wrapper are transparent: their nested text retains the existing quote state,
whereas an actual unnamespaced style anywhere beneath those wrappers still
resets it. Nested protected sections remain illegal even when separated by
foreign or ignored-XLIFF wrappers. Real AAPT2 warning snapshots retain the
native unknown-namespace diagnostics, and private `donottranslate*` files obey
exactly the same boundary. Crucially this validation never applies translator-only
FormatJS constraints to values that remain untranslated: punctuation-bearing
protected IDs, style spans inside protected sections, and multiple unnumbered
format arguments still compile and remain byte-identical in UTF-8/UTF-16
source templates. Original/localized AAPT2 snapshots retain every protected
string, array position, plural category, and style span while the only exposed
translation slot updates its visible sibling. The real Okapi differential
separately records which protected array/plural values the existing filter
incorrectly exposes, including namespace-wrapped values and synthetic plural
categories that have no legitimate translation owner.

AAPT2 resource feature flags are a separate, build-conditional control rather
than an ordinary `translatable` boolean. The compiler recognizes only an
attribute whose namespace URI is `http://schemas.android.com/apk/res/android`
and whose local name is `featureFlag`: arbitrary namespace-prefix aliases are
equivalent. Without its `--feature-flags` build configuration, any nonempty
flag on an actual top-level resource declaration or a direct array/generic-bag
item rejects compilation, including negated flags, ASCII-padded names,
nonlocalizable resources, unrelated native resource types, and
`donottranslate*` files. The default Java/Rust entry points and source-set
overlay merges therefore fail closed with
`UNRESOLVED_ANDROID_FEATURE_FLAG` rather than inventing messages for an
unspecified build. Empty/ASCII-whitespace values, foreign or unqualified
attributes, root/control attributes, and plural-item attributes have no native
gating effect and remain ordinary accepted resources.

Explicit Java `LocalizationFileConverters.parse(..., Map<String, Boolean>)` /
`parseAndroidOverlay(..., Map<String, Boolean>)` and Rust
`parse_with_feature_flags` / `parse_android_overlay_with_feature_flags`
entry points independently resolve the same named read-only build values.
False resources disappear, `!flag` reverses the selected value, and disabled
array items are removed before allocating native string, reference, and
primitive indexes. Crucially, both parsers still validate disabled strings and
native array items before discarding them, matching actual AAPT2 compile
failures. Resource-level `androidFeatureFlag` and shared per-index
`androidArrayFeatureFlags` metadata preserve exact normalized conditions;
both normalized writers restore `xmlns:android`, top-level conditional
attributes, flagged references/primitives, and flagged text slots. Real AAPT2
independently compiles source and regenerated XML and links both products;
the resulting native arrays, references, primitive positions, strings, and
plurals are identical. Disabled higher-priority declarations do not tombstone
lower active resources, while surviving flagged upper arrays replace and
compact lower arrays atomically. Original manifests retain inverse flag maps,
native compile/link snapshots, fallback overlays, malformed writer-metadata
rejections, and the exact undefined-flag diagnostic.

The boolean entry points deliberately describe fixed flags only. Additive Java
`AndroidFeatureFlag` / `parseWithAndroidFeatureFlags` /
`parseAndroidOverlayWithFeatureFlags` and Rust `AndroidFeatureFlag` /
`parse_with_android_feature_flag_definitions` /
`parse_android_overlay_with_feature_flag_definitions` APIs accept an ordered
list of named `read_only` or `read_write` declarations with nullable values.
Like AAPT2's [actual flag-argument parser](https://android.googlesource.com/platform/frameworks/base/+/master/tools/aapt2/cmd/Util.cpp),
duplicate declarations are valid and the final declaration replaces both the
previous mode and value. A referenced fixed flag without a value fails with
`MISSING_ANDROID_FEATURE_FLAG_VALUE`; unreferenced unset definitions are
harmless. Mutable flags are never folded using their supplied true/false value:
positive and negated strings, generic resources, plural resources, and complete
`flag(condition)` resource directories remain present as runtime-conditional
translations. `androidFeatureFlagMode`, `androidPathFeatureFlagMode`, and
per-slot `androidArrayFeatureFlagModes` preserve mutable identity explicitly;
the normalized XML writers keep the original conditions and compile against
the same ordered build definition.

One native Android ID can simultaneously own its ordinary fallback and several
independent mutable alternatives, including positive and negated conditions.
Pinned AAPT2 retains every branch for strings, generic string items, plural
groups, top-level arrays, and inherited `flag(condition)` resource directories;
the flag's configured boolean does not select a mutable branch. Canonical IDs
therefore leave the ordinary fallback untouched and append
`@flag=neutral.flags.first` or `@flag=!neutral.flags.first` to each conditional
resource, before `[index]` for array slots and after any `@product=` suffix.
Both normalized writers remove only the verified matching runtime suffix;
mismatched string, plural, or array identities fail with
`INVALID_ANDROID_FEATURE_FLAG` instead of silently writing a different native
resource.

Overlay ownership is scoped to resource kind, native name, product, and runtime
condition. A higher-priority fallback does not erase lower conditional branches,
a higher-priority condition replaces only its identical lower condition, and
different conditions remain independently translatable. A runtime-gated
directory also keeps the lower unconditional fallback. Pinned AAPT2 has a subtle
same-priority distinction: repeated mutable conditions inside one source XML
are rejected, but the same condition in separate equally prioritized compiled
resources keeps its first declaration; ordinary same-priority duplicates still
fail. The independent Java/Rust overlay implementations and original neutral
linked-APK snapshots reproduce each boundary. Product selection chooses the
ordinary tablet/watch fallback normally but retains mutable alternatives from
every source product, removing obsolete `@product=` identity and metadata
while retaining the stable `@flag=` suffix.

Mutable direct array items have a separate native link boundary. AAPT2
compiles them but rejects APK linking until the resource configuration or
minimum SDK is at least `10000`; `values-v9999` still fails, while
`values-v10000` retains both positive and negated branches regardless of the
mutable flag's supplied true, false, or absent value. Both parsers expose the
link failure as `UNSUPPORTED_ANDROID_RUNTIME_ARRAY_FLAG` instead of silently
dropping either branch. Linked native snapshots distinguish ordinary
`strings`/`plurals`/`arrays` from `conditionalStrings`/
`conditionalPlurals`/`conditionalArrays`, preserving every ordered mutable
alternative alongside its fallback. A focused real Okapi comparison reports ten
projected canonical units against nine legacy units because the existing filter
loses a mutable generic string; the simultaneous-variant fixture exposes 20
projected canonical units against 18 legacy units, native-ID collisions for
fallback/flagged strings, plurals, and arrays, and both missing generic-string
alternatives.

Current AOSP `master` contains a newer `GetFlagStatus` path that says only
read-only resource flags are allowed, while the pinned production-like Google
AAPT2 `9.3.1-15703166` actually compiles and links mutable conditional strings,
plurals, products, directories, and SDK-eligible array slots. The pinned
compiler plus original neutral source/APK snapshots therefore remain the
versioned contract oracle; upstream source is useful context but must not
override measured behavior from a different release.

The same compiler also recognizes a complete path component spelled
`flag(condition)` anywhere in the original resource path. It removes that
component before resolving the `res/values-*` directory, then applies the
condition to every top-level string, array, plural, primitive, and bag in the
file. Java and Rust preserve the exact original path while recording inherited
`androidPathFeatureFlag` metadata separately from resource-level conditions;
normalized writers retain the path gate without emitting a conflicting XML
attribute. Disabled files produce an empty linked catalog but still validate
every hidden placeholder and item. Negation works, multiple nonempty flag
directories fail, empty `flag()` components are ignored, and path flag names
are not whitespace-trimmed. A top-level in-file flag or flagged nested style
item conflicts with a path gate, while independently flagged direct array
items remain valid and compact normally; plural-item attributes remain
ignored. Original compiler snapshots cover arbitrary path placement, locale
qualifiers, selected products, source-set fallback, exact diagnostics, and
native writer round trips, including the compiler's literal
`values_name.(condition).arsc.flat` intermediate filename and its unflagged
`flag()` spelling.

An explicit Android build can also select one or more AAPT2 products with Java
`LocalizationFileConverters.parse(..., Map<String, Boolean>, List<String>)` /
`parseAndroidOverlay(..., Map<String, Boolean>, List<String>)` and Rust
`parse_with_android_build` / `parse_android_overlay_with_build`; existing
all-products entry points remain backward compatible. AOSP's real
`ProductFilter` requires a default declaration for each native resource,
rejects multiple matching selected products, chooses the requested variant
before its default, and only then strips disabled read-only feature flags. As a
result, an explicitly selected but disabled product suppresses its enabled
default entirely instead of falling back. Conversely, a disabled
higher-priority source-set declaration cannot overwrite an enabled lower
declaration for the same product. Both independent selectors reproduce these
orderings across strings, plurals, generic arrays, primitive/reference slot
compaction, inverse conditions, and source-set provenance. Selected catalogs
use final runtime IDs without `@product=` suffixes or source-product metadata,
so normalized resources independently compile, link, and round-trip as real
selected-build artifacts. Neutral fixtures cover tablet/watch/default output,
missing-default and ambiguous-product native diagnostics, disabled selected
variants, exact linked APK snapshots, and selected source-set fallback. The
linker also ignores an explicit `default` token when it accompanies a real
named product: `["default", "tablet"]` selects tablet rather than treating
the ordinary fallback as a competing product. Independent Java/Rust resource
and source-set selectors now reproduce that native boundary, including mutable
tablet variants that remain conditional in the final linked APK.

Attribute-bearing Android style tags preserve their original platform markup by
quoting only the opening angle character with ICU apostrophe syntax. Matching
closing tags use the same reversible policy, ordinary attribute-free tags remain
FormatJS rich-text elements, and `androidMarkupEscaping` records the conversion.
Style attributes remain opaque to printf normalization, percent ownership, and
line-separator provenance; XML character-reference whitespace is reproduced
without unsafe physical attribute newlines. The actual Android compiler
verifies tag attributes and span offsets while ICU4J/FormatJS verify that the
original formatted markup is preserved.

Apple `.strings` uses a small stateful lexer instead of line-based splitting,
which preserves block/line comments, single-/double-quoted or bare keys,
Foundation's key-only shortcut, escaped delimiters, octal/Unicode escapes,
line continuations, Unicode line/paragraph comment boundaries, and
UTF-8/UTF-16 BOM handling. Its grammar matches the actual open-source
CoreFoundation lexer: block/line comments are legal between every token but
only leading comments describe a translation; separator whitespace is limited
to ASCII U+0009 through U+000D, space, U+2028, and U+2029; unquoted tokens use
only ASCII alphanumerics and `_`, `$`, `/`, `:`, `.`, `-`; Unicode escape digits
are ASCII-only; escaped physical LF/CR/CRLF retain their exact characters; and
Unicode whitespace inside retained translator comments is normalized equally
in both languages. Twenty-one negative native fixtures reject unsupported
Unicode separators, control characters, punctuation, and unquoted Unicode.
The structural fixture also records 12 canonical messages versus nine actual
Okapi units: seven missing IDs, four malformed extra IDs, four comment
mismatches, and one escaped-source mismatch. Octal escapes use actual unsigned-byte NextStep
Latin decoding, including 8-bit overflow and undefined bytes becoming NUL;
named `\\a` and `\\v` become bell and vertical tab rather than literal letters.
Foundation also accepts three actual `.strings` container representations:
ordinary unwrapped syntax, brace-wrapped OpenStep dictionaries, and XML
property-list dictionaries with either a `<plist>` wrapper or direct `<dict>`
root. Optional declarations/public Apple DTDs, UTF-16 byte orders, XML
entities, CDATA, supplementary keys, ordered Foundation placeholders, and
numeric carriage-return entities are parsed independently in Java and Rust.
HTML-looking literal values receive reversible ICU angle quoting plus typed
`appleMarkupEscaping` metadata; apostrophes and native positional formatters
remain unchanged, and Foundation, FormatJS, and ICU4J render identical text.
Only XML comments immediately before a key become translator context;
interstitial comments, arbitrary root versions, and harmless string attributes
do not alter values. CoreFoundation also accepts processing instructions
before its standard public plist DTD, around the document/dictionary roots,
between ordinary key/value pairs, and throughout `.stringsdict` plural
selectors, branches, and protected typed metadata. It rejects processing
instructions embedded directly inside `<key>`, `<string>`, `<integer>`, or
other scalar property-list values. Java preserves the distinction with DOM
processing-instruction nodes; Rust tracks instruction ownership separately
from visible XML text. The exact-source templates preserve every valid
instruction byte while replacing only owned translation slots. Public-DTD
stripping accepts leading processing-instruction/comment preambles while
continuing to reject custom/internal declarations and external entities.
Foundation's parser dispatches on complete raw plist tag names, not
namespace-local names. Default namespace declarations, nested default-namespace
resets, intrinsically bound `xml:` attributes, and bound application-metadata
attributes do not change dictionary semantics and remain byte-for-byte owned
by their original source templates. Conversely, prefixed root, dictionary,
key, string, category, integer, array, and boolean elements are rejected at
every nesting level, even when their prefixes are correctly declared. Java
compares DOM qualified tag names; Rust independently compares complete parsed
element names. CoreFoundation also ignores unbound attribute prefixes, but the
portable security boundary intentionally rejects that malformed XML in both
languages; explicit native-accepted conformance cases document the divergence.
Its parser also accepts invalid trailing document content, multiple roots,
misplaced/duplicate/unsupported XML declarations, invalid standalone values,
forbidden reserved namespace bindings, empty namespace-prefix bindings, and
duplicated namespace-expanded attributes. Explicit original Foundation
snapshots prove this native permissiveness; both portable implementations
reject every form before extraction or source-template ownership.
Foundation further accepts raw NUL/C0 controls and `U+FFFE`/`U+FFFF` in text,
attributes, comments, CDATA, and processing instructions, plus XML 1.1
numeric references to otherwise forbidden controls. Those values are unsafe
even when the platform returns a dictionary; both independent parsers and
source-template extractors reject them with stable `INVALID_XML`.
The actual legacy Okapi Apple strings and stringsdict extractors both emit zero
units from these valid namespaced dictionaries, so the manifest records exact
native extraction gaps and independent three-message/six-plural-category
migration-shadow snapshots.
CoreFoundation's raw entity parser also enforces an unexpected maximum of eight
decimal or lowercase-`x` hexadecimal digits, including leading zeroes. Numeric
references decode inside keys, strings, and real values, while its dedicated
integer/date/data parsers reject all named or numeric entity references.
Attributes, comments, processing instructions, CDATA, and escaped ampersands
are not part of that reference ownership and preserve arbitrarily long literal
spellings. Java lexically walks the already-secure original document with an
element stack; Rust checks independent quick-xml reference events and their
typed owners. Both preserve the full XML 1.0 character boundary instead of
matching CoreFoundation's unsafe acceptance of NUL, empty references, C0
controls, or U+FFFE/U+FFFF. Explicit native-accepted fixtures document that
intentional safety divergence. Source templates retain original
eight-digit/supplementary/decimal/hexadecimal key and plural-category spelling,
escaped single-pass ampersands, exact TAB/LF/CR references, protected typed
real metadata, and every untouched comment, attribute, and instruction byte.
Reference-looking literals also exposed a cross-format plural construction bug:
`#` in native Android/Apple/gettext/Xcode-derived branches is ordinary text,
but ICU treats it as the selected plural count. Both independent plural
builders now quote literal hashes only inside the assembled canonical ICU
pattern, leaving public `variants`, native source spellings, writer metadata,
and exact-source translation slots unchanged. Foundation, FormatJS, and ICU4J
now render the same literal. The existing Okapi stringsdict extractor also
rejects valid protected `futureLiteral` metadata as
`Invalid plural form: futureLiteral`; that separate legacy failure is captured
as an exact migration conformance case.
CoreFoundation distinguishes structural XML nodes from scalar content more
strictly than a generic DOM/text reader. Container-level comments and
processing instructions are valid, while literal unowned text and even
whitespace-only CDATA between plist/dictionary/array entries are rejected.
Scalar comments, processing instructions, and nested elements are invalid.
CDATA remains valid in keys, strings, and, unexpectedly, `<real>` values, but
not integer/date/data values. Explicit empty boolean pairs are valid, whereas
whitespace or other boolean content is rejected. Independent Java DOM/Rust
quick-xml validation preserves these boundaries, exact real bits, protected
typed arrays, and byte-identical plural-source templates. The configured
legacy Okapi stringsdict filter crashes on Foundation-valid CDATA plural
categories with `Invalid plural form: <![CDATA[one]]>`; that native/legacy
divergence is preserved as a stable rejected-extraction conformance case.
Native empty-data semantics require source lexical ownership: Foundation
accepts `<data></data>` or explicit data containing only ignored base64
whitespace, but rejects `<data/>`, including attributes and nested
message/rule/array/dictionary positions. Empty string, dictionary, array,
and boolean spellings may be self-closing or explicitly closed; integer,
real, and date tags require actual content. Java inspects the secure original
XML while Rust retains self-closing parser events, avoiding false positives in
comments, CDATA, or processing instructions. Source-preserving templates keep
empty typed metadata byte-for-byte while translating plural branches. Their
native oracle now uses Foundation `binary1` snapshots for typed source
templates because JSON cannot represent zero-length data. Mojito's actual
legacy Okapi filter crashes on these valid protected data fields with a
reproducible `NullPointerException`, distinct from its CDATA-category crash.
Duplicate/blank keys, nonstring plist values, missing pairs, nested markup,
unexpected XML text, extra roots, and unsafe custom DTDs fail with stable
diagnostics. Empty dictionary writers emit a deterministic
comment-only resource because Foundation rejects truly empty files. The native
oracle now executes actual `Bundle.localizedString` on original and normalized
resources, not merely `plutil`: JSON is accepted by `plutil` but silently
rejected by Foundation bundles and therefore remains outside the resource
contract. Existing Okapi extracts zero of eight XML messages and produces only
one malformed unit for three wrapped OpenStep messages; both migrations have
shared Java/Rust shadow snapshots. The processing-instruction fixture adds
three Foundation messages versus zero actual Okapi units.

Binary property-list `.strings` resources are supported end-to-end as well.
The manifest's explicit `BINARY_PLIST` encoding points at original,
implementation-neutral lowercase hexadecimal bytes, generated reproducibly by
`generate_apple_binary_fixtures.py` from neutral XML inputs using Apple's real
`plutil -convert binary1`; malformed variants are deterministic mutations of
those generated bytes. Java and Rust decode the identical bytes independently,
following CoreFoundation's real object-table, offset-table, reference, extended
integer/string/dictionary length, UTF-16BE, and 32-byte-trailer contracts. The
readers support every Foundation-accepted `bplist0?` header, ignored reserved
trailer bytes, 1–255-byte offsets/references including 3/9/16/255-byte widths,
shared string objects, nonzero top-object indexes, empty dictionaries,
16/32-byte extended string/dictionary lengths, and two-byte references
spanning more than 255 objects. More-than-eight-byte offsets/references retain
their low 64 bits exactly like CoreFoundation, including nonzero ignored
high-order bytes.
Although CoreFoundation describes a future UTF-8 object marker, the actual
`bplist00`, `bplist01`, and other `bplist0?` decoders reject that marker;
both implementations preserve the same stable malformed-binary diagnostic.
Ordinary valid plain-text bare keys beginning with `bplist`, including apparent
binary versions, continue through the textual parser instead of being
misclassified as binary resources.

Native probing revealed that Foundation's nominal ASCII object marker actually
maps every byte directly to ISO-8859-1, including C1 controls; original
U+0080, U+0091, U+00A0, and U+00FF cases enforce that undocumented behavior in
both implementations, compiled native snapshots, and normalized writer
round trips. UTF-16BE counts code units, preserves supplementary characters,
and rejects unpaired surrogates. Inputs, objects, and string units have
explicit 16-MiB, 65,536-object, and 1,000,000-unit limits. Oversized counts,
malformed headers/trailers, integer widths, roots, object offsets, references,
extended lengths, and truncated/invalid strings fail before unsafe allocation.
Nonstring values and blank/duplicate dictionary keys retain the same canonical
policy as textual `.strings`, including native-accepted cases where Foundation
is intentionally more permissive. Foundation's actual Swift bundles format both
the binary originals and normalized UTF-8 output; FormatJS and ICU4J verify
identical positional arguments, literal markup, apostrophes, and supplementary
Unicode. Legacy Okapi extraction still consumes Java `String` source rather than
binary bytes, so binary migration must use an explicit byte-aware routing seam.
`.stringsdict` is parsed as a property-list dictionary and supports multiple
plural variables, adaptive-width rules, device-specific rules, and combined
plural/device variants without mistaking plist metadata for messages. Exact
localized-format strings, per-variable value types, and original native plural
branches remain loss-aware metadata. Leading-zero width keys are retained
without confusing their numeric presentation width, and unsupported future
device identifiers receive deterministic Unicode-scalar fallback ordering.
Actual Swift Foundation bundles also accept binary `.stringsdict` resources,
despite Apple's documentation describing their XML serialization. The shared
binary object-table decoders recursively materialize dictionary graphs and
directly reuse each language's established stringsdict semantic parser;
Foundation, FormatJS, and ICU4J verify original binary and normalized XML
bundles for complete positioned, reordered, repeated, and independent plural
selectors. Original native fixtures additionally cover shared dictionary
references, alternate plist versions, 3/9/255-byte offset widths,
supplementary Unicode, width/device variations, signed integers, and booleans.

Nested dictionaries have an explicit maximum depth of 64, cycle detection, and
a 65,536-visit graph-expansion budget. A small neutral graph with only 20
objects expands exponentially through shared references and is rejected before
resource exhaustion; it is intentionally not executed by Foundation. Every
native-accepted textual plural error is also independently replayed as a
binary dictionary. Unknown message-level and plural-rule values preserve all
seven native property-list families: dictionaries, ordered heterogeneous
arrays, strings, booleans, signed/unsigned 64-bit integers, raw bytes, UTC
dates, and IEEE-754 floating-point values. Recursive, version-one
`applePlistExtras` uses explicit reversible tags for data, dates, real-number
bits, and genuine dictionaries whose own keys collide with the tag marker.
Native array ordering, zero-length collections/data, negative zero, NaN,
infinities, exact floating-point bits, and separately owned future plural-rule
metadata survive normalized writing. XML decimal/hexadecimal, leading-zero, and
explicitly signed integers normalize to Foundation's numeric values; its
undocumented malformed-base64 tolerance and overflowing calendar components
normalize exactly like the open-source CoreFoundation parser. Fractional
binary dates deliberately fail with `UNSUPPORTED_APPLE_PLIST_DATE_PRECISION`
because XML property lists cannot represent their subsecond values; cyclic
array/dictionary graphs fail safely. Platform-generated binary snapshots and
real Swift bundles prove both independent readers and writers preserve opaque
native resources without silent loss.
Xcode-generated legacy dictionaries use explicitly positioned
`%1$#@signals@` markers, which both native parsers now expand into independently
selected canonical plurals while preserving repeated/reordered selectors,
ordinary outer `%3$@` arguments, separate positional string arguments within
plural branches, and spelled-out branches with numeric value-type metadata.
Foundation and both ICU runtimes accept ASCII digit-leading and underscored
plural variable names.
Unlike modern String Catalog substitution identifiers, direct legacy plural
dictionary names must remain Foundation-safe ASCII: `plutil` accepts Unicode
keys structurally, but Foundation's actual localized formatter does not expand
them. Xcode sanitizes modern Unicode names in its compiled dictionary, so modern
catalogs retain full Unicode support without producing unsafe legacy markers.
Running the same fixture through Mojito's actual configured Okapi stringsdict
filter exposes a migration-critical loss: sentences with two independent
plural selectors retain only one native plural definition. The shared
implementation-neutral shadow snapshot records 38 projected canonical units
versus 48 emitted legacy units, two missing complete messages, and twelve
unexpected detached branches. Digit-leading canonical arguments are restored by
both shadow comparators before comparison so this signal measures actual legacy
loss rather than instrumentation drift.
Apple's
exact standard public plist DOCTYPE is safely removed only from the XML prolog;
arbitrary document types, internal subsets, and external entities remain
forbidden. Modern Apple `.xcstrings` string catalogs preserve the source
language, per-locale translated values/states, extraction state, device-nested
source plural variations, source substitutions, and additional variation axes
as loss-aware metadata. Xcode's actual compiler requires a catalog `version` and
a textual `state` plus `value` in every `stringUnit`, but its catalog `version`
accepts both strings and native JSON numbers. Integral/fractional revisions,
documented machine-translation states, arbitrary future review states, raw
source/translated localization branches, original locale spellings, unknown
root/descriptor metadata, source states, and source plural states are retained
with their original types. Optional comments, extraction states, and
`shouldTranslate` accept JSON null; null does not mean `false`. Null source
localizations use the original message-ID fallback while null translated
localizations disappear from canonical and regenerated resources. Future
device identifiers remain lossless, with unknown-only fallback selected by
Unicode scalar order rather than Java UTF-16 or input insertion order.

Referenced `%#@name@` String Catalog substitutions are not opaque metadata:
Xcode compiles them into Foundation plural dictionaries and allows multiple
independently selected variables in one localized sentence. Both native parsers
expand each referenced substitution into its own nested FormatJS plural,
preserve `argNum` positions and original numeric conversion spellings, reuse
repeated selectors, accept FormatJS-safe Unicode names, and retain the complete
source/translated substitution trees plus branch review states. A missing
`argNum` gets a deterministic implicit position. Xcode silently sanitizes
Unicode substitution names in compiled `.stringsdict` output; the normalized
catalog preserves its original identifier and reproduces the identical native
Foundation dictionary. FormatJS and ICU4J independently verify all four
combinations of two plural selectors plus Unicode, repeated, and
implicit-position selections. Missing `other`, nonpositive/nonintegral
positions, nonnumeric selector formats, unsafe names, undefined references, and
malformed definitions fail explicitly, including documented policy differences
when Xcode accepts unsafe input.

Xcode's native compiler also imposes a less obvious structural boundary: a
`%#@name@` substitution reference may appear in a plain root or device string,
but never inside an existing plural category. Original neutral source-locale,
device-nested, and target-locale fixtures all produce the actual diagnostic
`Cannot reference substitution 'lanterns' from here because it is not a plain
string`. Both independent parsers reject those resources with stable
`INVALID_XCSTRINGS`; the conformance oracle checks the specific native error
rather than accepting any unrelated compiler failure. Unused root substitution
definitions remain legal when no plural branch references them.

Real Xcode accepts invented, uppercase, and exact-selector-looking plural
category names, but Foundation simply writes those names into its native rule
dictionary without assigning FormatJS exact-selector semantics. Portable
catalogs deliberately reject non-CLDR names instead of inventing runtime
behavior, and require a native numeric format argument for plurals. Empty
source locales, empty message IDs, inactive localization sets, and empty
variation maps are also explicitly rejected despite native acceptance because
they either violate the canonical schema or compile no resource. Locale
identifiers that collide after underscore/hyphen normalization are rejected,
even though Xcode compiles them into conflicting locale directories. Sibling
plural/device axes are rejected because repeated invocations of Apple's own
compiler select different output axes nondeterministically; properly nested
device-to-plural variations remain supported.

Gettext uses an entry-state parser for extracted/translator comments,
references, contexts, effective translation domains, retained previous
context/singular/plural history, multiline C strings, obsolete entries, locale
headers, and indexed plural translations. Native lexical behavior includes
adjacent directives and quoted fragments, optional ASCII horizontal spacing,
spaced plural indexes, and C backslash-LF/CRLF line splicing. Both `#,` and
the 2025 `#=` flag spelling preserve unknown project flags and native
last-wins `*-format`/`no-*-format` polarity. Explicit empty translation domains
remain distinct from absent directives through `gettextDomain`; previous
message history is represented by typed `gettextPrevious` metadata. Duplicate
translations, invalid previous-field ordering, and malformed domains fail
safely. GNU accepts escaped embedded NULs but silently truncates them inside
compiled MO catalogs, so both portable readers reject source/context/domain/
translation NULs with `INVALID_GETTEXT_NUL`. Bare-CR-only sources are also
unsafe: GNU accepts the file but its first comment
consumes the remaining catalog because CR does not terminate native comment
lines; both portable implementations reject the affected fixture rather than
silently dropping every translated message.

GNU translation domains own independent message identities, `Language` headers,
and `Plural-Forms` expressions. The same source ID is valid in different
domains when `msgfmt` writes separate `<domain>.mo` files; forcing all domains
into `msgfmt -o one.mo` incorrectly rejects that valid identity. An absent
domain directive and explicit `domain "messages"` name the same effective
default domain, whereas `domain ""` remains distinct. Both parsers retain an
ordinary canonical ID when it belongs to just one effective domain. If the same
ID belongs to distinct domains, they append a reversible `@domain=` suffix
whose domain bytes use uppercase RFC 3986 UTF-8 percent escapes, while
`gettextOriginalId` preserves the native ID. A genuine source ID that collides
with an automatically qualified identity fails safely instead of overwriting a
message. Unsafe domain names containing whitespace or path separators are
rejected before native output can escape its isolated destination.

Each explicit domain carries its own typed `gettextDomainHeader`; the implicit
default domain also carries one whenever domain locales differ. Mixed-locale
catalogs deliberately omit a misleading global locale. Domain-specific French,
Russian, and English plural formulas select CLDR categories using their own
locales rather than whichever header appeared first. Original neutral fixtures
compile to separate GNU MO files, including Unicode and literal-percent domain
names, and execute real `GNUTranslations.ngettext()` selections. Independent
FormatJS and Java ICU runtime oracles verify the same source arguments and
rendered translations. Java ICU normally groups bare numeric arguments in
French, unlike GNU `%d` and FormatJS, so native printf integer samples use the
explicit `::group-off` ICU skeleton before runtime comparison.

GNU's compiled header also retains arbitrary project and translator metadata;
discarding it silently changes ownership, generation provenance, and revision
history even when visible translations remain identical. Ordered
`gettextDomainHeader.fields` now preserves `Project-Id-Version`, bug-report and
creation/revision dates, `Last-Translator`, `Language-Team`, MIME and transfer
fields, project-specific generators, duplicate field names, mixed casing,
Unicode and empty field names, empty values, and folded continuation text for
each real domain. The implicit `messages` domain carries header metadata even
when its locale is the catalog-wide locale. Duplicate reserved `Language`
fields use GNU's last-value-wins behavior rather than accidentally declaring a
mixed-locale catalog. Reserved fields stay typed/canonical; folded custom
fields normalize to one space-prefixed continuation per line. A continuation
that would modify `Language`, `Plural-Forms`, or `Content-Type`, a forged
reserved field, colon-bearing name, injected continuation header, carriage
return, embedded NUL, or inconsistent domain-owned metadata fails with stable
`INVALID_GETTEXT_DOMAIN_HEADER` diagnostics.

The ASCII-readable `Content-Type`
header selects strict UTF-8, ISO-8859-1, US-ASCII, or GNU's portable `CP1252`
encoding before source bytes are decoded. Hexadecimal and octal C escapes use
that same declared charset rather than representing Unicode code points:
CP1252 `\x80` becomes a euro and undefined Windows code-page slots fail.
Lowercase/underscore Latin-1 aliases match GNU, whereas `UTF8`, `latin1`,
`WINDOWS-1252`, unsupported names, malformed declarations, invalid bytes, and
UTF-8/UTF-16 BOMs are rejected. A UTF-8 resource claiming Latin-1 deliberately
produces GNU's observed mojibake rather than silently guessing a different
encoding. Accepted legacy inputs regenerate deterministic UTF-8 without
changing compiled MO dictionaries. GNU `msgfmt` can emit malformed UTF-8 MO
payloads when a lone high byte is escaped under a UTF-8 declaration, so the
portable contract rejects those payloads with `INVALID_GETTEXT_ENCODING`. Java and Rust
independently parse
gettext's restricted C-expression grammar, preserve short-circuit semantics,
and reject malformed formulas, malformed counts, unsafe arithmetic,
out-of-range results, and missing declared plural translations. Real GNU
`msgfmt --check-header` accepts only ASCII space/tab inside or around plural
expressions and requires the exact `nplurals=`/`plural=` assignments; vertical
tab, form feed, carriage return, ASCII record separators, Unicode whitespace,
and whitespace before `=` fail. Both implementations therefore use explicit
horizontal-only trimming/tokenization instead of Java Unicode whitespace,
Rust ASCII-whitespace classes, or general-purpose `strip`/`trim`. Four accepted
and 61 rejected native fixtures cover leading, internal, and trailing
separators; an independent source skeleton preserves escaped tabs and its
original formula while injecting both plural translations. GNU's decimal
counts/literals may contain hundreds of harmless leading zeroes, so both
parsers remove only insignificant zeroes before bounded numeric conversion;
the canonical expression and source skeleton retain their original spelling,
while the independent FormatJS oracle converts the same decimals into legal
JavaScript BigInt tokens without mutating catalog metadata.
Significant overflow, ambiguous duplicate `nplurals`/`plural` declarations,
decimal/exponent/word count suffixes, and Unicode-padded counts deliberately
fail closed even where GNU silently accepts or chooses its first declaration.
Eight native-valid catalogs, 23 stable error cases, and another byte-exact
translated plural source skeleton make that improvement explicit. GNU's actual
strict header checker validates only inclusive integer counts `0..1000`; both
native Mojito implementations retain that range and independently add bounded
representative probes through `1,000,000,000`. These expose French, Brazilian
Portuguese, European Portuguese, Spanish, Italian, and Catalan `many`
categories at multiples of
one million, an otherwise invisible `=1001` exact-selector exception, and
invalid indexes/divide-by-zero accepted by GNU beyond its window. Locale
category selection distinguishes Brazilian zero-as-`one` from European
zero-as-`other`. Real ICU4J and FormatJS independently verify all integer probes
plus manifest-declared fractional selections; decimals are explicitly a
canonical ICU extension because GNU's plural-expression input is integral.
Java properties implement the actual JDK's LF/CR/CRLF natural lines,
odd-backslash logical-line continuations, noncontinuable comments, ASCII-only
space/tab/form-feed delimiters, escaped separators, unknown escapes, supplementary
Unicode escape pairs, and configurable UTF-8 versus ISO-8859-1 decoding without
using `java.util.Properties`, which would discard comments, order, and
duplicate-key diagnostics. Unicode whitespace remains part of property keys,
and Unicode line/paragraph separators remain ordinary values. Unpaired escaped
surrogates are deliberately rejected even though the JDK can retain them in a
UTF-16 string that cannot be represented safely by the canonical UTF-8 contract.

FormatJS JSON accepts both string maps and message descriptors. Descriptions and
existing ICU message syntax are preserved.

## Android source-set overlays

Android resource merging uses build variant > build type > product flavor >
main > library priority. Priority is explicitly attached to each original
source rather than inferred from an arbitrary filesystem layout; inputs can be
supplied in any order. Equal-priority declarations of the same resource are
errors, while disjoint resources at equal priority merge normally.

Java `LocalizationFileConverters.parseAndroidOverlay` and Rust
`parse_android_overlay` independently parse and merge one effective Android
resource configuration. The merge key is native resource type, name, normalized
configuration, and product identity, not an individual canonical array/plural
message. A higher-priority array replaces the entire lower array, and a
higher-priority plural replaces its entire native quantity set. Declarations
marked `translatable="false"` still suppress lower-priority translatable
resources even though the winning declaration is excluded from the canonical
catalog. Product `default` and device-specific variants remain distinct;
legacy `values-en-rUS` and BCP-47 `values-b+en+US` normalize to the same native
configuration.

Overlay equivalence is based on AAPT2's effective configuration rather than
raw directory tokens. Both independent implementations normalize locale case,
mobile-country/network leading zeros, MNC-zero sentinels, named/numeric density
buckets, 16-bit density/dimension wrapping, omitted zero-valued dimensions,
physical pixel spelling, and the larger of explicit and compiler-implied SDK
versions. Feature floors match native compilation: ordinary density/size API
4, night/UI mode API 8, width qualifiers API 13, `anydpi` API 21, round API 23,
wide color/HDR/VR API 26, and grammatical gender API 34. Explicit versions and
true differing densities remain separate configurations.

Each winning canonical message records `androidOverlaySourceSet`, the winning
original `androidResourcePath`, and its exact directory qualifiers. Empty
source sets, unknown source-set priorities, same-priority collisions, and mixed
resource configurations fail with stable portable diagnostics. Mixed
configurations are rejected because a single canonical catalog has only one
locale/configuration scope; callers must merge each resource configuration
separately.

Overlay extraction also owns a separate, version-one, lossless multi-file
sidecar defined by
`file-formats/conformance/android-overlay-source-skeleton.schema.json`.
Java `extractAndroidOverlaySkeleton`/`renderAndroidOverlaySkeleton` and Rust
`extract_android_overlay_skeleton`/`render_android_overlay_skeleton` first
compute actual winning native resource identities, then independently derive
and filter each original file's byte-addressed source slots. The original file
list, source-set priorities, resource paths, XML layout, namespaces, comments,
styles, and protected `<xliff:g>` attributes are preserved. A lower-priority
default product may remain editable while its tablet alternative, replaced
scalar/array/plural bodies, and values masked by a higher-priority
`translatable="false"` tombstone remain byte-for-byte untouched. Both original
and translated source sets are linked for `default` and `tablet` by actual
AAPT2.

A selected product has different runtime and source identities. AOSP's actual
[ProductFilter](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/process/ProductFilter.cpp)
chooses one requested product, falls back to `default`, rejects ambiguous
matches/missing defaults, and rewrites the chosen native value as the ordinary
runtime declaration. A version-one overlay sidecar therefore optionally keeps
`androidSelectedProducts` together with complete `androidRuntimeSlotOwners`.
For example, runtime `product_signal`, `product_routes[0]`, and
`product_lights#one` can independently own
`product_signal@product=tablet`, `product_routes@product=tablet[0]`, and
`product_lights@product=tablet#one` in their original winning build-type file.
All unused default/tablet scalar, whole-array, and whole-plural declarations
remain byte-identical. Java and Rust independently verify direct `tablet`,
explicit `default`, absent-`watch` fallback, and `default,tablet` behavior
against original/localized real AAPT2 APKs. Unknown/shadowed translation keys
fail with `UNKNOWN_OVERLAY_SKELETON_SLOT`; incomplete, duplicated, or
inconsistent runtime-to-source ownership fails with
`INVALID_ANDROID_OVERLAY_SKELETON`, and malformed build-product lists preserve
the existing native `INVALID_ANDROID_PRODUCT` boundary.

Product identity is not governed by Unicode whitespace. AAPT2's
[link command](https://android.googlesource.com/platform/frameworks/base/+/master/tools/aapt2/cmd/Link.cpp)
passes product spellings to `ProductFilter` without Unicode normalization, so
NEL, nonbreaking/figure/narrow spaces, em spaces, and other Unicode separators
remain literal name characters even when they make up the entire identity.
Java's `String.trim()` correctly rejects surrounding ASCII controls only, but its
previous additional `isBlank()` check incorrectly rejected 15 compiler-valid
Unicode-only names; Rust's Unicode `str::trim()` additionally rejected 61 valid
leading/trailing or nonbreaking-only names. Both independent implementations
now apply the actual ASCII-only ownership boundary. Ninety-five original
AAPT2-linked overlays cover leading, trailing, internal, doubled, and
Unicode-only spellings across 19 code points. Four native-linked multi-file
templates additionally preserve mixed ISO-8859-1, UTF-8/BOM, and UTF-16/BOM
source ownership and permit translations only through the unqualified runtime
identity. Empty, ASCII-padded, comma-bearing, and duplicate selections remain
explicitly invalid.

Build macros can be declared in a completely different source file from the
translated body. AOSP's
[resource macro parser](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/ResourceParser.cpp)
captures the macro's original namespace stack, while its
[reference linker](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/link/ReferenceLinker.cpp)
resolves the globally winning default-configuration definition and expands its
styled/protected content using that declaration-site namespace. Overlay source
sidecars therefore additionally retain optional `androidApplicationPackage`
and typed `androidMacroOwners` maps. Java and Rust independently validate the
entire original source set, resolve higher-priority definitions, and associate
every editable use-site slot with its compiler-expanded descriptor without
rewriting any lower/higher macro declaration. A translated alias is safely
inlined into the original use-site body; its styles and protected `<xliff:g>`
example/argument are checked against the expanded definition rather than the
alias's tag-free spelling. Original namespace-qualified, private, `res-auto`,
and transitive aliases remain untouched unless translated. Shared original
neutral fixtures and real original/localized AAPT2 APKs cover higher-priority
overrides, scalar/array/plural expansions, selected tablet products,
definition-only files, and exact protected/style spans. Forged definition
ownership, changed style attributes, and missing protected placeholders fail
closed.

The shared manifest's original neutral overlay fixtures are independently
compiled and linked by Google's real AAPT2 binary. Real linked-resource
snapshots prove source-set precedence, atomic array/plural replacement,
winning native array/plural references, nontranslatable tombstones,
product-specific selection, locale qualifier normalization, numeric/density/
pixel aliases, compiler-implied API floors, and same-priority duplicate
rejection. The linker intentionally retains nontranslatable values in its native
snapshot while the canonical extraction omits them.

## Source-preserving Android skeletons

The canonical FormatJS catalog is intentionally semantic; it is not, and cannot
be, a source-file template. Source-preserving translated-file generation uses a
separate version-one sidecar specified by
`file-formats/conformance/source-skeleton.schema.json`. The sidecar records the
decoded original source, its exact UTF-8/UTF-8-BOM/UTF-16LE-BOM/UTF-16BE-BOM
encoding, and ordered half-open **original-byte** translation slots. Scalar and
array slots use their canonical message IDs; plural slots additionally record
their native quantity and accept `<message-id>#<quantity>` replacement keys.
XML-escaped `name`, `type`, `product`, and plural `quantity` attributes are resolved
for native identity matching while the original entity spellings remain
untouched in the source template.
Android templates can also retain their exact relative `androidResourcePath`.
Directory locale/configuration and `flag(...)` ancestry are compiler inputs,
not XML text, so the source bytes alone cannot recover them. Omitting this
optional field leaves all existing version-one sidecars unchanged.

Both implementations independently validate the complete file with the
existing secure native-semantic parser, lex the actual original XML, and map
only extracted descriptors to editable message bodies. Nontranslatable
resources, macro definitions, reference-only array/plural entries, comments,
blank lines, original resource ordering, attribute ordering and quote spelling,
namespace aliases, XML declarations, BOMs, CRLF line endings, and all untouched
bytes remain in the template. An empty translation map is a byte-identical
identity operation. Partial translation maps modify only the slots explicitly
selected by their keys.

AAPT2 and current AOSP flatten XML comments, CDATA, and processing instructions
without breaking quote state, adjacent Unicode escapes, native formatter
tokens, style-span offsets, or protected `xliff:g` sections. Java and Rust now
classify embedded instructions as preserved zero-width source decorations
rather than rejecting native-valid templates: independent UTF-8 and UTF-16LE
sidecars retain every root/style/protected/array/plural instruction and its
exact position while changing only translated text. The real compiler verifies
both original and translated resource snapshots, including the preserved
inline style span. Invalid reserved `<?xml ...?>` targets and unterminated
instructions fail closed with stable `INVALID_XML`; Rust independently checks
that XML declarations occur only before the root element.

Product-qualified source declarations retain the exact native semantic
identity: ASCII-padded `name` and `product` attributes are normalized only for
slot lookup, `product="default"` owns the ordinary unqualified message, and
nondefault strings, generic string items, arrays, and plurals own their
`message@product=value` descriptors. The original attribute spelling is never
rewritten. Source scanners ignore foreign-namespace lookalikes even when their
local element name, product, and resource ID collide with a real Android
declaration. Protected resource references, nontranslatable values, and all
unselected product alternatives retain their exact original bytes.

Mutable Android feature flags require their complete ordered build context, not
just a boolean enabled value. The version-one sidecar therefore optionally
retains `androidFeatureFlags` with each original declaration's name,
read-only/read-write mode, and nullable value. Existing source skeletons omit
the field and remain byte-for-byte compatible. Java and Rust independently
reuse these declarations when extracting and reinjecting the same source;
runtime alternatives retain their native
`message@flag=expression`, `message@flag=!expression`, and
`message@product=value@flag=expression` identities. Root namespace aliases are
resolved to Android's actual namespace instead of assuming a literal `android`
prefix, disabled read-only declarations remain protected original source, and
conditional arrays/plurals preserve their own resource-level identities and
native item indexes.

When the original source lives under `values-fr-night`, both implementations
reuse the retained path for extraction and reinjection: the directory selects
locale `fr` over a conflicting `tools:locale="de_DE"` annotation, and native
AAPT2 resolves the complete configuration as `fr-night-v8`. A path such as
`src/main/res/flag(neutral.flags.path)/values-fr-night/strings.xml` also applies
its mutable condition to every eligible scalar, generic item, array, and plural
while preserving product-qualified ownership, untouched resource references,
the original `values-fr-night_strings.(neutral.flags.path).arsc.flat`
intermediate identity, UTF-16 source offsets, and nontranslatable source bytes.
Real original/translated links verify default/tablet selection, conditional
string/array/plural values, and the native last-product-wins collision for a
shared mutable condition.

Direct array-item gates have a different ownership rule from resource-level
gates. Fixed false items disappear before native indexing, including strings,
references, primitives, empty elements, generic arrays, and generic bags;
fixed true and positive/negated mutable items retain compacted positions. The
independent lexical scanners now count only enabled items, so translated slots
match the semantic parser's compacted `array[index]`, protected references and
primitives retain their native positions, and runtime conditions remain in
`androidArrayFeatureFlags`/`androidArrayFeatureFlagModes` rather than becoming
resource-level `@flag=` identities. Root-level and plural-child feature
annotations are compiler-ignored even when their names have no definitions.
Original UTF-8/UTF-16/CRLF fixtures preserve every disabled declaration,
attribute spelling, CDATA wrapper, and self-closing conditional item while
linking default/tablet packages under the required `values-v10000`
configuration.

Translated text is escaped with the existing independent Android-native
writers, preserving native printf placeholders and protected `xliff:g` values.
Java and Rust independently match each translated opening span to an
unambiguously identified original sibling using its local element name,
complete attribute values, and original parent. Closing tags follow the
matching original pair. This permits linguistically necessary reordering of
ordinary bold/italic/underline spans, differently attributed repeated spans,
named protected `xliff:g` sections, nested styled placeholders, scalar strings,
string-array items, and plural branches without changing any original tag
lexeme. Namespace-prefix aliases, attribute order, single versus double quotes,
protected IDs/examples, and original nesting survive even when their sibling
positions change. Added, removed, duplicated, renamed, attribute-mutated,
cross-parent, malformed, or ambiguous inline tags fail closed with
`INVALID_SKELETON_MARKUP`; unknown translation keys fail with
`UNKNOWN_SKELETON_SLOT`, and duplicated, overlapping, or out-of-range byte
ownership fails with `INVALID_SKELETON`.

Self-closing translatable scalar strings, generic string items, string-array
items, and plural quantities have an original slot over their `/>` suffix.
Reinjection retains the complete original opening element/attribute spelling
while expanding only that suffix into `>translated</original-name>`. Java and
Rust independently assign every source text/comment/CDATA section to its
original inline parent, including the root between sibling tags. When a
styled or protected subtree moves, its internal comments and CDATA move with
that same source-owned subtree; root-level comments stay in their root-relative
positions. Comment-only styled branches can safely acquire translated text
without dropping their original comment. Original CDATA wrappers retain
literal angle brackets/ampersands instead of converting their payload to XML
entities; a translated `]]>` safely splits into adjacent CDATA sections.
Comments and CDATA surrounding untranslated bytes never change. Processing
instructions, ambiguous sibling identity, changed parent ownership, and
malformed nesting still fail closed.

Original neutral fixtures independently cover styled text, protected
placeholders, ordinary/generic strings, mixed reference/string arrays, empty
scalar/generic/array/plural declarations, inline comments, literal and mixed
CDATA, safe translated CDATA terminators, plural quantities, supplementary
Unicode before source slots, padded names/products, explicit default and
tablet/watch alternatives, foreign-namespace collisions, both UTF-16 byte
orders, UTF-8 BOM, and CRLF. Reordered source fixtures additionally verify
protected placeholder swaps, sibling and nested style swaps, repeated style
identity, original attribute spelling, styled arrays/plurals, native UTF-16
span offsets, and shared stable rejection for missing, modified, duplicated,
cross-nested, and parent-changing inline tokens. Mixed source-owned fixtures
also interleave 24 exact original XML comments with styled/protected scalar,
array, and plural subtrees; preserve original CDATA wrappers and safe `]]>`
splits; move nested decorations with their matched parent; retain root and
comment-only boundaries; and verify both UTF-16 and CRLF original offsets.
Google AAPT2 compiles the original and localized output for every
encoding/line-ending variation and verifies distinct native value and style
snapshots. Product-qualified templates additionally link both original and
localized resources for the independently selected `default`, `tablet`, and
`watch` products, proving fallback, selected strings, arrays, and plural
categories match actual AAPT2 runtime packages. Runtime-flagged templates run
the real ordered AAPT2 flag declarations through compile/link and retain both
ordinary and conditional native values. The native snapshots also expose an
important linker boundary: the same mutable condition in multiple product
alternatives collapses to the last conditional value even when the ordinary
selected-product fallback differs. Every default/negated/product-gated
conditional string, generic item, array, plural, and disabled fixed flag is
verified in original and translated APKs. Java and Rust must produce the
exact same portable sidecar and localized bytes.

Production Okapi filter events, persistent skeleton transport, and route-level
cutover remain explicitly separate follow-up work. Xcode itself rejects
substitution references inside top-level or device-nested plural branches, so
that specific combination is invalid native input rather than missing
functionality. Foundation/OpenStep Apple strings, GNU gettext PO, and
Java properties have their own independently implemented source-preserving
contracts below. Normalized writers remain valuable for compiler parity but are
never presented as source-preserving production filters.

## Normalized Android regeneration

Both runtimes independently regenerate deterministic UTF-8 Android XML from an
Android-origin canonical catalog. Resource names are sorted, arrays are grouped
by their source array/product with contiguous native indexes including preserved
reference slots, and plural categories use the fixed `zero`, `one`, `two`,
`few`, `many`, `other` ordering. Descriptions,
`formatted="false"`, product variants, `tools:locale`, protected `xliff:g`
placeholder names/examples, original printf conversions, plain/rich style tags,
attributed spans, and the original `values-*` configuration all round-trip back
into the identical canonical JSON. Separate quoted XML text segments preserve
leading/trailing whitespace and style-boundary offsets without altering actual
AAPT2 spans.

`androidGenericString` retains an original `<item type="string">` declaration.
Regenerating that descriptor as an ordinary `<string>` would cause AAPT2 to
reject previously valid multiple implicit printf substitutions, so both writers
emit the original generic resource element instead.

NUL, carriage return, and other C0 controls that XML cannot represent literally
are regenerated as Android `\uXXXX` escapes. This preserves actual native
characters while ensuring normalized output is always well-formed XML; source
escaped surrogate units that AAPT2 discards are never recreated.

Two otherwise invisible native distinctions are retained explicitly in message
metadata: `androidLiteralMarkup` keeps escaped HTML-looking text from becoming
an Android style span, and `androidPrintfLineSeparator` preserves a source `%n`
instead of replacing its compiled spelling with an Android `\\n` escape. When
both spellings coexist, `androidPrintfLineSeparators` and
`androidPluralPrintfLineSeparators` preserve their exact occurrence ownership
without changing legacy `%n`-only catalogs. Each normalized snapshot is
byte-for-byte identical across Java and Rust,
idempotent after reparsing, and compiled again by official AAPT2. The compiler's
normalized strings, arrays, plurals, span attributes/UTF-16 offsets, product
variants, and directory configuration must match the source-native snapshot.

Normalized writing is deliberately not skeleton-preserving: comments, source XML
namespace aliases, standalone reference-only resources, entirely reference-only
arrays, build-time macro definitions/alias declarations, and
`translatable="false"` resources that were excluded from the canonical catalog
cannot be reconstructed. References inside mixed translatable arrays and plural
groups are preserved explicitly, as is generic `<bag>` declaration identity for
every retained translatable array/plural group. Macro-expanded messages are
written as their final native strings; original and normalized linked APK
snapshots prove that flattening preserves runtime values and style spans. Such
omissions use an
explicitly separate normalized compiler snapshot. Production translated-file
generation stays on its existing path until the separate source-preserving
skeleton covers its full format/overlay contract, shadow comparisons, safety
flags, and rollout metrics are independently defined.

## Source-preserving Apple strings skeletons

The same versioned sidecar independently supports textual Foundation/OpenStep
and XML property-list `.strings` resources. Both implementations validate the
full original resource against their existing native-semantic parser before
separately lexing its original representation. OpenStep source retains comments,
whitespace, optional dictionary braces, escaped/unescaped keys, single/double
quoted values, unquoted tokens, and Foundation shorthand. XML dictionaries
retain their exact declaration, trusted Apple public DOCTYPE, optional `<plist>`
wrapper or direct `<dict>` root, translator/structural comments, key entity and
CDATA spelling, element attributes, declaration order, and all surrounding
bytes. Source slots own only the original value ranges.

Quoted-value slots exclude their surrounding delimiter. Single-quoted targets
escape literal apostrophes while retaining double quotes; double-quoted targets
use Foundation's normal double-quote escapes. Unquoted source values become a
safe quoted target without rewriting their key or spacing. A native key-only
`"message";` entry gets a zero-width slot immediately before its semicolon;
translation inserts ` = "translated"`, while the no-translation path remains
byte-identical and keeps the original shorthand. Original escaped key spellings
such as `"escaped\\U002Ekey"` continue to map to their actual Foundation key
without ever rewriting the source token.

Existing Apple writers provide native printf/positional placeholder escaping and
retain source `%n` behavior. Independent original neutral fixtures cover block
and line comments, wrapped dictionaries, delimiter-bearing quoted keys, escaped
keys, apostrophes, literal double quotes, shorthand insertion, unquoted values,
supplementary Unicode before translation slots, UTF-8 BOM, both UTF-16 byte
orders, and CRLF. Real `plutil` separately parses original and translated files
for every source variant and checks complete native Foundation dictionaries.

XML text and entity values are serialized as XML rather than OpenStep escaped
strings. Existing literal/mixed CDATA boundaries remain in place; translated
`]]>` is safely represented by adjacent CDATA sections. Empty `<string/>`
values expand only inside their original `/>` slot, preserving every attribute
and preceding space; explicit `<string></string>` values use zero-width
insertion. Supplementary Unicode, encoded carriage returns/newlines/tabs,
literal percentage escaping, native positional placeholders, `%n`, CRLF,
UTF-8 BOM, and both UTF-16 byte orders are all native-verified. Comments nested
inside keys or values, arbitrary/internal/external DTDs, and unknown or invalid
slots remain fail-closed before source ownership begins.

Binary property lists, non-Unicode legacy encodings, and persistent production
filter skeleton ownership remain intentionally unsupported until their own
reversible source contracts exist. Foundation `.stringsdict` and Xcode String
Catalog JSON have their own independently scoped source contracts.

## Source-preserving Apple stringsdict skeletons

Java and Rust independently preserve XML Foundation `.stringsdict` resources
with the existing version-one source sidecar. Each implementation validates
the original resource through its own established native-semantic parser, then
lexes nested dictionary keys and XML value boundaries without rebuilding the
document. A message with one plural variable retains its existing
`message#category` identities. Multiple independently varying variables use the
backward-compatible optional source-slot `selector`, yielding unambiguous
`message#variable#category` identities without changing the canonical
FormatJS-compatible catalog or any existing sidecar. Widest-width and
preferred-device values own the ordinary `message` slot. Source-owned
localized-format declarations, value types, placeholder
definitions, nonselected device/width branches, dictionary order, XML
declaration, trusted public DOCTYPE, comments, attributes, typed metadata, and
every other untouched byte remain unchanged.

Escaped and CDATA-spelled keys resolve to Foundation's actual message identity
without rewriting their original spelling. A self-closing plural branch expands
only its `/>` suffix, retaining all original element attributes and whitespace;
literal CDATA values stay CDATA, and a translated `]]>` is safely split into
adjacent CDATA sections. Existing independent Java/Rust writers restore native
printf placeholders from canonical ICU values. Original neutral fixtures cover
supplementary message keys, protected mac/iPhone branches, leading-zero width
keys, independent positioned plural selectors, integer/boolean/array metadata,
UTF-8 BOM, both UTF-16 byte orders, and CRLF layout.

Real `plutil` verifies the complete original and translated Foundation
dictionaries, while Swift Foundation loads both resources into actual bundles
and executes original and translated plural samples. The native oracle also
records an otherwise unobvious platform interaction: when one entry combines
`NSStringDeviceSpecificRuleType` with a plural
`NSStringLocalizedFormatKey`, macOS selects its matching device value and does
not execute the plural branches. Those branches remain safely editable, while a
separate pure-plural entry proves translated category selection at runtime.
Two independent, positioned selectors are exercised through all four
singular/plural combinations before and after reinjection, proving that neither
selector collides with or borrows the other's Foundation argument.

### Binary Foundation object-table source ownership

The same version-one source sidecar also accepts binary Foundation `.strings`
and `.stringsdict` with `encoding: "BINARY_PLIST"`; `source` holds the complete
original binary as lowercase hexadecimal rather than incorrectly treating it as
Unicode text. Slots own whole binary string objects, including their marker and
extended-length integer. The independently implemented Java and Rust walkers
resolve actual object references back to canonical message IDs, independent
plural selectors, and selected device/width values while retaining every
protected dictionary key, formatter declaration, inactive branch, boolean,
integer, array, Unicode metadata value, and object reference.

Translated string objects may change from ASCII to standards-compliant UTF-16BE,
grow into extended lengths, and contain supplementary characters. Foundation's
native binary writer also deduplicates equal values and can reuse a shorthand
value as its dictionary key. For those objects the sidecar adds the optional
`appleObjectIndex`; slot `start`/`end` own the selected parent value reference,
not the shared object. Each translated owner receives a separately appended
object, and only its value reference points at that private clone. The original
shared string, protected aliases, dictionary key, inactive category, and
untranslated sibling remain unchanged. Existing nonshared sidecars preserve
their exact prior shape.

When appended clones increase the object count from 254 to 256, both independent
writers widen every existing dictionary/array object reference from one byte to
two, preserve all original reference identities, and recompute every original
and cloned object offset. Reserved trailer flags, root object, container order,
nonreference object bytes, and typed metadata remain untouched; the trailer's
object count, reference width, offset width, and offset-table position are
updated explicitly. An empty translation set returns the exact original bytes,
and unknown or forged object/reference ownership fails closed.

The original neutral fixtures are generated with Apple's actual
`plutil -convert binary1`, and their expected object replacements are produced
by a third independent Python reconstruction. The shared verifier independently
checks every unowned object byte and rebuilt reference-table offset. Actual
Foundation `plutil` snapshots and Swift bundles verify original/localized binary
string values, positioned placeholders, selected device branches, width rules,
and independent plural selectors. Apple's open-source
[CoreFoundation binary property-list implementation](https://github.com/apple-oss-distributions/CF/blob/main/CFBinaryPList.c)
is the primary source for object markers, integer widths, the 32-byte trailer,
and offset/reference-table ownership.

CoreFoundation's actual formatter explicitly classifies `%n` as a disabled
dummy-pointer conversion rather than Java's newline conversion:
[Apple's CoreFoundation format parser](https://github.com/swiftlang/swift-corelibs-foundation/blob/main/Sources/CoreFoundation/CFString.c#L6686-L6689)
documents the deliberate behavior. Real Swift Foundation confirms that
`North%nSouth` renders `NorthSouth`, `%2$n` is likewise zero-width, repeated
conversions emit nothing, physical newlines remain physical, and `%%n` emits a
literal `%n`. Java and Rust canonical descriptors expose this actual visible
text to FormatJS while retaining every ordered native occurrence in
`appleDisabledPrintfConversions` as its Unicode-scalar insertion position and
original spelling. Native normalized writers and OpenStep, XML, and binary
source skeletons reinsert those conversions without changing an unrelated
newline. Translated occurrence positions scale deterministically with the
visible Unicode-scalar length; original messages preserve their exact positions.
Forged spellings and out-of-range locations fail safely. A dedicated neutral
fixture verifies six Foundation/FormatJS runtime selections and the real Okapi
differential proves five legacy source mismatches and a comment mismatch.
Plural dictionaries extend the same contract through
`applePluralDisabledPrintfConversions`, indexed first by the independent
selector and then by plural category. Original neutral singular/other branches
prove `%d%n`, repeated conversions, `%%n`, supplementary IDs, and real physical
newlines with Foundation and FormatJS. Both Java and Rust normalized writers
preserve the original source variant unchanged, while translated XML and binary
source slots reconstruct category-owned native `%n`/`%%n` without touching
another selector. Mojito's real Okapi stringsdict extraction produces 24 wrong
source projections, including flattening a real newline to a space; its
snapshot and migration-shadow report are executable.

Standalone Foundation device and presentation-width dictionaries apply the
same formatter contract to the selected scalar value. The independent Java and
Rust parsers remove zero-width `%n` occurrences from the canonical selected
device or widest width while retaining complete native variation maps, ordered
occurrences, escaped literal `%%n`, physical newlines, and padded numeric width
keys. Default byte-preserving source skeletons own only the iPhone or
widest-width source value and leave every Mac/Apple Watch/narrow-width branch
untouched. An explicit backward-compatible opt-in independently owns **every**
standalone variation instead: Java
`LocalizationFileConverters.extractSkeletonWithAppleVariations(bytes)` and Rust
`extract_skeleton_with_apple_variations(bytes)` emit version-one slots with
reserved `@device` or `@width` selectors and exact native branch names. The
translation identities are therefore `message#@device#mac`,
`message#@device#iphone`, and `message#@width#040`; padded numeric spellings
remain protected source identity rather than being normalized to `40`. Each
branch reconstructs its own canonical descriptor, placeholder spellings,
escaped `%%n`, physical newline, and ordered disabled `%n` conversions before
injecting only its own value into the untouched original XML. Existing
selected-only templates, binary sidecars, canonical catalog descriptors, and
schema version remain unchanged. The shared native Swift oracle invokes
`variantFittingPresentationWidth(_:)` on original and localized bundles,
proving below-minimum fallback, exact and intermediate thresholds, padded
`040` selection at width 40, independently translated narrow, middle, widest,
and current-Mac alternatives, genuine zero-width `%n`, escaped `%%n`, and
actual physical newlines.
Apple’s primary documentation distinguishes device and width selection:
[Creating width and device variants of strings](https://developer.apple.com/documentation/xcode/creating-width-and-device-variants-of-strings).
Actual Foundation running on macOS chooses the protected `mac` value while the
portable canonical descriptor owns `iphone`; this deliberate platform-context
difference is verified through separate native source-template selections.

Xcode String Catalogs inherit exactly the same Foundation formatter behavior.
The actual `xcstringstool compile` preserves genuine `%n`/`%2$n`, escaped
`%%n`, and physical newline spellings independently in generated scalar
`.strings`, selected/untouched device branches, `.stringsdict` plural
categories, and root-owned `%#@selector@` substitution definitions shared by
independent device branches; actual Swift bundle lookup confirms their visible
output. Java and Rust project source scalar/default-device values with
`appleDisabledPrintfConversions` and top-level or substitution branches with
selector/category-scoped `applePluralDisabledPrintfConversions`. Both
normalized JSON writers preserve the original native source spelling and fail
safely for forged occurrence positions/conversions. Byte-preserving source
sidecars independently restore translated roots and selector-owned categories
while protecting existing target locales, Mac/Apple Watch branches, review
states, and unrelated JSON spelling. Translated occurrence positions scale
deterministically with visible text while retaining their original ordering
around the owning native argument. Moving `%n` before an integer conversion can
make Foundation consume the wrong variadic argument, so source regeneration
anchors category-owned conversions to the correct argument side instead of
blindly scaling across it. FormatJS, the real Xcode compiler, and the real
Foundation runtime verify original and localized resources without executing
unsafe conversion/argument combinations.
Okapi's existing extension mapper has no `.xcstrings` route, so the executable
legacy comparison intentionally records unsupported behavior rather than
pretending that the old implementation handles this format. Standalone
non-selected Foundation device and width branches are now independently
translatable. Genuine device-specific plural dictionaries are also parsed and
normalized independently in Java and Rust: the selected iPhone branch remains
the canonical FormatJS plural, full device-owned plural trees stay in
`devicePluralVariants`, and opt-in source templates expose
`message#@device=iphone#one` and `message#@device=mac#other` category slots.
Real Swift Foundation selects the Mac singular/plural alternatives from both
original and byte-preserving translated resources. Apple's WWDC 2019 session
explicitly confirms that `NSStringDeviceSpecificRuleType` combines with plural
and variable-width rules. Separate UTF-8/UTF-16LE source skeletons preserve
exact byte offsets and actual native original/localized Mac selections.
Remaining deeper three-axis combinations and disabled conversions followed by
additional visible native arguments still need safe separately documented
dummy-argument contracts.

Direct Foundation probes distinguish safe axis ordering from merely valid XML:
`NSStringDeviceSpecificRuleType` may own a full
`NSStringVariableWidthRuleType` dictionary, and the actual Mac
`variantFittingPresentationWidth(_:)` selector chooses the correct padded
threshold at below-minimum, exact, intermediate, and widest widths. Java and
Rust independently preserve the selected iPhone default, all native device
trees, deterministic normalized XML, independently translatable
`@device=iphone#040`/`@device=mac#5` slots, branch-owned genuine `%n`, escaped
`%%n`, physical newlines, and UTF-8/UTF-16 source offsets. The inverse nesting
is parseable by `plutil`, but actual Foundation aborts with
`NSInvalidArgumentException` because it treats the nested device dictionary as
a string. Both parsers deliberately reject that Foundation-unsafe shape with
`INVALID_APPLE_STRINGSDICT`; do not infer runtime validity from plist parsing.

Device branches do not need to agree on their native value shape. Real Swift
Foundation successfully selects a Mac plural dictionary next to an iPhone
scalar, a Mac scalar next to an iPhone plural, a Mac width dictionary next to
an iPhone scalar, and a Mac scalar next to an iPhone width dictionary. Both
Foundation also safely combines an iPhone plural dictionary with a Mac width
dictionary, reverses those dictionary shapes, or includes all three shapes in
one iPhone/Mac/other device tree. Both independent readers classify the native
branch kinds and preserve every heterogeneous tree as `deviceMixedVariants`;
the selected iPhone branch still determines whether the canonical FormatJS
descriptor is scalar, plural, or width-selected. Their normalized writers
reproduce every mixed branch, and existing opt-in version-one source sidecars
independently combine `message#@device#iphone`, `message#@device=mac#one`, and
`message#@device=mac#040` ownership without a schema bump. Twenty-five original
neutral source values retain XML entities, padded thresholds, genuine `%n`,
escaped `%%n`, physical newlines, supplementary identifiers, exact UTF-8 and
UTF-16 offsets, and translated Mac scalar/plural/width execution. This is a
documented improvement over the previous homogeneous-branch restriction, not
permission to accept reversed Foundation-crashing width-owned device rules.

## Normalized Apple strings regeneration

Java and Rust also independently regenerate deterministic UTF-8 Apple
`.strings` files. Entries are sorted by Unicode scalar rather than Java's UTF-16
code-unit order, which guarantees byte-for-byte parity for supplementary-plane
and private-use keys. All keys/values use double-quoted Foundation syntax;
translator descriptions are safe `//` comments even when they contain block
comment delimiters. Original Foundation object/integer positional arguments,
escaped literal percentages, quotes, backslashes, newlines, tabs, bell,
vertical-tab, NUL, other C0 controls, and supplementary Unicode round-trip into
the identical canonical catalog.

`appleDisabledPrintfConversions` preserves each disabled native `%n` without
inventing a visible newline; actual newline characters retain their own native
escape spelling. Zero-width does not mean argument-free: actual Swift Foundation
consumes one implicit native argument for `%n`, while explicitly positioned
`%2$n` owns native argument 2 without advancing the implicit cursor. Thus
`%n %@` renders visible argument 2, `%2$n %@` renders argument 1, and
`%@ %n %@` renders arguments 1 and 3. When a scalar also contains visible
arguments, each disabled conversion retains an optional positive
`argumentPosition`; descriptors reserve the correct visible native positions
without exposing dummy values as FormatJS placeholders. Genuine `%n`, repeated
conversions, explicit-position overlap, object/integer placeholders, escaped
`%%n`, and supplementary keys are checked independently in Java and Rust
against real Foundation/FormatJS execution and actual Okapi extraction. OpenStep,
XML property-list, and Xcode resources preserve original and translated UTF-8 or
UTF-16 source bytes, and normalized regeneration remains compiler/runtime
equivalent.

The same argument ownership now applies independently inside XML/binary
`.stringsdict` plural categories and Xcode substitution-owned plural branches.
[Apple's Xcode 16.3 release notes](https://developer.apple.com/documentation/xcode-release-notes/xcode-16_3-release-notes)
explain that substitution plural branches start implicit argument numbering at
the substitution's assigned position; its
[String Catalog presentation](https://developer.apple.com/videos/play/wwdc2023/10155/)
also documents the selector's explicit argument ownership. Native Swift probes
confirm `%d%n %@` requires `[count, dummy, object]`, repeated `%n` reserves
separate dummy positions, explicit `%3$n` can coexist with `%2$@`, and escaped
`%%n` reserves nothing. Omitting the dummy from an otherwise valid Foundation
plural resource caused an isolated native `SIGSEGV`; crash-triggering input is
therefore diagnostic evidence, not a fixture executed by the shared suite.
`applePluralDisabledPrintfConversions` preserves each category-owned optional
`argumentPosition`, while Java and Rust independently retain selector-relative
visible placeholder positions and exact native source spellings. Original,
normalized, translated UTF-8/UTF-16, binary Foundation, compiled Xcode, Swift
runtime, and FormatJS runtime snapshots exercise only native-safe combinations.
Source-template writers additionally preserve positioned Xcode substitution
markers such as `%2$#@count@` without incorrectly escaping their leading `%`.
Actual Okapi flattens or changes all 42 source projections from the neutral
seven-message plural fixture; Xcode String Catalogs still have no legacy route.
The rule extends one level deeper into independently translated iPhone/Mac
device-owned plural dictionaries in both Foundation and Xcode. Actual Mac
Foundation selection again requires `[count, dummy, object]`, and omitting the
dummy from `%lld%n %@` segfaults in an isolated probe. Original and translated
device/category source templates retain repeated hidden positions, explicit
`%1$lld %3$n %2$@` ownership, escaped `%%n`, original UTF-8/UTF-16 bytes, and
real XML/binary/compiled runtime selections. Disabled conversions between two
visible arguments retain their nearest original placeholder anchor, including
the exact whitespace on either side; neither Java nor Rust may shift a dummy
onto the following visible argument. The actual legacy filter silently extracts
zero units from the four valid device-owned dictionaries, losing all 24
projected plural units.

[Foundation's presentation-width selection](https://developer.apple.com/documentation/foundation/nsstring/variantfittingpresentationwidth(_:))
also owns native arguments after selecting the exact threshold or the nearest
smaller available width. Original neutral standalone and iPhone/Mac-owned
`NSStringVariableWidthRuleType` fixtures independently cover thresholds `5`
and padded `040`, `%n %@`, `%@ %n %@`, repeated `%n%n %@`, explicitly positioned
`%2$n %1$@`, and escaped `%%n %@`. Both Java and Rust reserve hidden native
argument positions before normalizing the selected width and each independently
translated `@width#040` or `@device=mac#5` source slot. Unlike the plural
category probe, omitting a required width dummy did not crash real Foundation:
it silently rendered `(null)` in the visible object position. Original,
normalized, translated UTF-8/UTF-16, binary, and real Foundation
`variantFittingPresentationWidth` snapshots therefore include the required
dummies and preserve nearest-placeholder anchor placement. The measured Okapi
filter invents ten threshold-qualified standalone identities instead of the five
actual Foundation message identities and silently drops all five device-owned
width dictionaries; its ten extracted units therefore match none of the ten
canonical messages. Deeper three-axis, mutable-review, and source-less locale
insertion contracts remain separate open work.
UTF-8 BOM and either
UTF-16 byte order are accepted on input but intentionally normalized to Apple's
recommended UTF-8 output; native `plutil -convert json` independently verifies
identical Foundation dictionaries before and after every write. Single-quoted
source entries and key-only shorthand are normalized into explicit, quoted
key/value pairs. Writing is idempotent and does not claim byte-identical source
comments, original encoding, or source-skeleton preservation.

## Source-preserving Java properties skeletons

The same version-one sidecar separately preserves JDK `.properties` source
files. Java and Rust independently validate the original semantic catalog,
reconstruct its physical and logical lines, and identify each value's exact
half-open source-byte range. Comments, blank lines, declaration ordering,
escaped and continued key spellings, Unicode key escapes, delimiter choices,
surrounding ASCII whitespace, and every byte outside translated values remain
unchanged. Unicode spaces such as NBSP remain part of their original property
keys rather than becoming separators.

Translator-comment trimming follows Java `String.strip()` and
`Character.isWhitespace`, not Rust's broader Unicode `White_Space` property.
`U+001C..U+001F` are stripped, while NEL `U+0085`, NBSP `U+00A0`, figure space
`U+2007`, and narrow no-break space `U+202F` remain visible translator context,
even when they are the entire comment. Interior whitespace remains unchanged.
Rust independently uses the same explicit Java whitespace table for both
property comments and the canonical descriptor's blank-description decision.
A 270-case Java/Rust boundary differential now has zero disagreements; ten
JDK-backed fixtures cover 106 original hash/bang descriptions, and four
UTF-8/ISO-8859-1/CRLF source templates preserve every comment byte while
injecting translated values.

An odd trailing backslash joins LF, CR, and CRLF natural lines while trimming
only ASCII space, tab, and form-feed from the continued line. A comment never
continues, even when its final byte is a backslash. Value slots own their full
original physical extent, and translated values retain the exact original
continuation backslashes, natural-line spelling, and indentation. Empty values
have zero-width slots; key-only declarations receive a safe `=` only when a
translation is inserted, while an existing `:`/`=` separator and its spacing
stay untouched. Empty replacement maps reproduce the complete original file
byte for byte.

The actual JDK also discards an odd unpaired backslash at end of input, even
when it occurs in a key or immediately after an empty value. Native
`harbor.route\\` is therefore the empty-valued key `harbor.route`, while three
terminal slashes leave one literal key backslash. Java and Rust independently
preserve that exact semantic identity, assign the discarded final backslash to
the last source-value slot, and replace it with a real `=` only when a
translation requires one. This also works for physically continued keys,
empty explicit values, and ISO-8859-1 templates whose translated euro and
supplementary compass require native surrogate-pair escapes. Final slots may
reach end-of-file without inventing a lexical boundary; actual JDK dictionaries
verify both untouched and translated sources. The existing Okapi filter instead
retains the consumed slash in the message identity, producing one missing and
one invented native key in the measured shadow comparison.

Continuation indentation is discarded by the JDK before determining whether a
key has a value separator. Thus `harbor\\` followed by a whitespace-only next
line still means the empty-valued key `harbor`; blindly examining the last
physical space during reinjection turns a translation into a different key.
Both source-template implementations now inspect the complete logical
declaration before deciding whether to inject `=`. Original LF, CR, and CRLF
bytes remain unchanged through single and repeated continuation tails,
tab/form-feed indentation, escaped `=` inside keys, comment-looking `#` data,
and genuinely implicit or explicit whitespace/colon/equals separators. Real
JDK original/localized dictionaries verify every variant, while a second
actual Okapi differential proves it again retains the nonexistent continuation
backslash as part of the extracted key.

Java's `String.isBlank` is narrower than Rust's default Unicode whitespace
predicate: NBSP, FIGURE SPACE, NARROW NO-BREAK SPACE, and U+0085 remain valid
nonempty message IDs even when each occupies an entire escaped properties key.
Both implementations now use the same Java-compatible blank-identity boundary.
JDK `Properties.load` can create genuinely empty IDs from `=`, `:`, an escaped
ASCII space, or a continuation consisting only of a final backslash; those
files are deliberately rejected because empty or blank FormatJS message
identities cannot be represented safely.

UTF-8 source remains UTF-8; explicitly selected ISO-8859-1 source retains its
legacy byte encoding. Characters that cannot be represented in ISO-8859-1
become JDK-native `\uXXXX` escapes, including paired surrogate escapes for
supplementary Unicode. Existing property metadata restores positional printf
placeholders, source `%n` newlines, and literal `%%` spellings before target
escaping. Unknown, duplicated, overlapping, or out-of-range slots fail closed.

Original neutral fixtures cover comments that appear to continue, escaped
separator-bearing keys, Unicode-escaped and physically continued keys, NBSP,
whitespace/colon/implicit separators, empty values, supplementary Unicode before
source slots, all three natural-line styles, preserved continuations, legacy
accented source bytes, and unrepresentable target euro/emoji characters.
The actual JDK `Properties.load(Reader)` independently verifies both original
and translated dictionaries for every case. Java and Rust must produce the
same sidecar and exact localized source bytes.

XML property-list variants, persistent production filter skeleton transport,
cross-file source ownership, and Okapi integration remain separate contracts.

## Normalized Java properties regeneration

Both runtimes independently regenerate deterministic UTF-8 `.properties` files
without JDK timestamp headers. Keys use Unicode scalar ordering rather than
Java UTF-16 code-unit ordering; spaces, separators, comment introducers,
backslashes, tabs, newlines, carriage returns, and form feeds follow the
escaping behavior of `Properties.store(Writer)`. Other C0 controls and DEL are
additionally normalized as safe `\uXXXX` escapes even though JDK
`store(Writer)` emits them raw; `Properties.load` confirms both spellings have
the same native value. Unicode scalars are emitted directly instead of forcing
legacy ISO-8859-1 escapes. Single-line translator comments remain attached to
their descriptors, and unsupported plural variants or multiline comments fail
with stable portable diagnostics.

Original printf placeholder spellings, positional/repeated arguments, literal
raw percent versus `%%`, and source `%n` survive round trips. Scalar-indexed
`javaPropertiesEscapedPercents`, `javaPropertiesPrintfLineSeparator`, and
position/source-aware `javaPropertiesPrintfLineSeparators` metadata preserve
native distinctions that would otherwise collapse into the same FormatJS
message, including mixed literal newlines and indexed `%2$n` conversions. The
actual JDK independently loads both the original and the
normalized UTF-8 snapshot and must produce the same dictionary, including for
ISO-8859-1 inputs, Unicode whitespace keys, CR-only inputs, comment
continuation-looking lines, supplementary/private-use sorting, and U+2028/U+2029
values.

## Source-preserving GNU gettext skeletons

The version-one sidecar independently preserves native GNU gettext PO source
files in Java and Rust. Both implementations first validate the complete file
against their existing charset-aware parser, then scan original native
directives and own only the quoted `msgstr`/`msgstr[n]` translation ranges.
The header entry, original charset/locale/plural formula, project fields,
translator/extracted/reference/flag comments, previous-message history,
obsolete `#~` entries, contexts, untranslated entries, source IDs/plural IDs,
ordering, spacing, and every other original byte remain untouched.

GNU accepts Unicode whitespace and C0 separator characters inside every PO
metadata channel, so portable normalization must distinguish Java whitespace
from Unicode `White_Space`. Extracted `#.` notes, translator `#` notes, flags,
`Language`, custom/folded headers, and quoted directive envelopes use Java
`Character.isWhitespace`; source-reference `#:` lists additionally split only
Java's default ASCII regex whitespace. NEL, NBSP, figure space, and narrow
no-break space therefore remain meaningful source text, while `U+001C..U+001F`
trim at the edges but not inside a reference. Real GNU `msgfmt` accepts all
200 original differential samples; Java/Rust now agree on all of them after
176 prior metadata mismatches. Twenty-four native MO-backed fixtures cover 72
independent messages, locales, and custom headers; three UTF-8/ISO-8859-1
templates preserve all original note/reference/flag bytes through translation.

Normalized PO writing and domain safety must use that same exact Java
`Character.isWhitespace` boundary rather than Rust's Unicode `White_Space`
property. GNU accepts NEL, NBSP, figure space, and narrow no-break space as
literal source-reference, format-flag, output-domain, and header-locale
characters; all 48
neutral normalized resources compile to the same original GNU MO catalog, and
`msgcat` confirms 20 distinct Unicode-bearing domain names. Conversely, GNU
also tolerates `U+001C..U+001F` in domains and references, but Mojito
deliberately rejects these unsafe output-path characters and refuses to
serialize control-bearing references. Twenty native-accepted unsafe domain
inputs, four safe writer rejections, and 24 independent unsafe metadata
mutations document this stricter policy. Four
additional UTF-8/ISO-8859-1/CRLF source templates preserve the complete
Unicode domain, reference, flag, charset header, and original translation
slots byte for byte. Independent Rust parsing/writing now mirrors Java for
domain validation, references, flags, domain-header fields, and qualified
identities; a 360-case differential has zero remaining discrepancies.

Native plural indexes map to their exact canonical primary CLDR category, so
translation keys use `<message-id>#<category>` without inventing new FormatJS
metadata. Source context and effective domain resolve native entries to their
actual canonical identities. Existing singular/plural placeholder metadata
restores original native printf spellings before target C-string escaping.
The target retains each original quote group, physical wrapping, and line-ending
spelling, including an empty leading `msgstr ""` followed by continuation
strings. Empty translations become active inside their existing quoted range.
An empty translation map reproduces the complete original source byte for byte.

UTF-8, explicitly declared ISO-8859-1, Windows CP1252, and strict US-ASCII PO
sources retain their exact original encoding. CP1252 preserves one-byte euros,
smart single/double quotation marks, dashes, escaped `\\x80` source bytes,
wrapped translated values, French `one`/`many` native plural indexes, and
original CRLF offsets. ASCII preserves its native charset header and
English plural indexes without silently upgrading to UTF-8. Latin-1/CP1252
targets outside the original code page and every non-ASCII target fail with
`INVALID_GETTEXT_ENCODING` instead of replacing characters or changing the
header.

A single native source template can also own an implicit `messages` domain
plus multiple named domains with colliding source IDs, colliding contexts,
different project headers, independent English/French/Russian locales and
plural formulas, and a percent-bearing native domain name. Java and Rust each
resolve every original quoted target against its effective source domain;
canonical slots use reversible `@domain=` identities and restore each
domain's own native plural indexes. Editing one collision never modifies the
other domains, headers, obsolete records, comments, physical wrapping, source
ordering, UTF-8 text, or LF/CRLF spellings.

GNU `msgfmt --use-fuzzy --check-format` independently compiles exact original
and localized single-domain PO files into one MO, and source-owned
multi-domain files without `-o` into independent `messages.mo`, `north.fr.mo`,
and `stock%ru.mo` catalogs. Real `msgcat` verifies original/localized domain
directives, decoded MO snapshots preserve each domain's project-owned header
fields, and Python's actual GNU runtime executes both original and translated
English/French/Russian plural selections. Attempting to force the multi-domain
source through one output is verified to fail instead of silently merging
colliding native records.

Multi-file production deployment/runtime loading, obsolete-entry editing,
previous-message editing, production filter transport, and Okapi integration
remain separate explicit contracts.

## Normalized GNU gettext regeneration

Java and Rust independently regenerate deterministic UTF-8 PO catalogs with a
portable charset/language header and the exact native `Plural-Forms` expression
when one was present. Entries group first by their effective domain, then sort
by Unicode scalar; implicit/default domains precede explicitly empty and named
domains. Writers preserve translator comments, extracted descriptions, source
references, ordered flags, contexts, previous context/`msgid`/`msgid_plural`,
source `msgid`/`msgid_plural`, named/positional printf spelling, original native
plural indexes, source formulas, and their CLDR/exact-selector mapping.
Each effective domain receives its own native header, locale, and plural
formula; named-only catalogs do not acquire an artificial default domain, while
mixed default/named catalogs retain the actual implicit domain header. Domain
IDs are dequalified back to their original native source spellings. GNU
`msgfmt` compiles the result without `-o` into one isolated MO file per domain,
and each decoded dictionary, ordered duplicate/folded native header field,
last-effective locale, and real plural selection must agree with the original
source. Reserved charset/language/formula fields normalize first, followed by
the original order, casing, and multiplicity of every custom or standard
project/translator field. Per-domain header disagreement, invalid qualified
IDs, header injection, and unsafe domain paths fail before output is written.
Modern `#=` flag lines normalize to interoperable `#,` lines without dropping
unknown project flags. Source line wrapping and obsolete entries are normalized
away because neither belongs to the canonical extraction contract. Invalid
domain/history metadata and embedded NULs fail before lossy native output.

`gettextUntranslated` and `gettextUntranslatedIndexes` distinguish empty source
translations from canonical source fallbacks. Fuzzy entries keep their flag and
are explicitly compiled with `msgfmt --use-fuzzy` so they remain visible during
native verification. Position-aware escaped-percent and printf-line-separator
metadata independently preserves literal `%%`, positional `%2$n`, and real
newline characters across singular and plural messages. `no-c-format` and
`no-python-format` remain uninterpreted native text. C0 controls use explicit C
escapes; encoded hex/octal UTF-8 source is normalized into direct Unicode.
Unsafe comments, references, flags, missing plural metadata, and conflicting
catalog-wide formulas fail with stable writer diagnostics.

Every normalized PO snapshot is byte-identical across both writers, reparses to
the original canonical catalog, remains idempotent, and compiles with GNU
`msgfmt --use-fuzzy --check-format`. Formula-bearing catalogs also retain
`--check-header` validation. The independently decoded binary MO entries,
contexts, empty indexed translations, and plural values must equal the source
snapshot.

## Normalized Apple stringsdict regeneration

Both runtimes independently regenerate deterministic UTF-8 XML property lists
from Apple `.stringsdict` catalogs. Top-level message keys sort by Unicode
scalar; plural branches use CLDR category order, widths sort numerically while
retaining their exact original spelling, and device identifiers sort
deterministically. Explicit `NSStringLocalizedFormatKey` patterns, multiple
independent plural variables, each optional/distinct
`NSStringFormatValueTypeKey`, named/positional printf spellings, literal
percentages, width dictionaries, and device dictionaries all survive Foundation
dictionary comparison.

Source-native `applePluralRules` and `appleLocalizedFormat` metadata distinguish
otherwise identical canonical ICU messages while allowing modified canonical
plural variants to be rendered back into native format. `defaultWidthKey`
records noncanonical numeric spellings such as `048` without losing
`defaultWidth=48`. Recursive `applePlistExtras` metadata retains unknown
message fields and separately owned plural-rule fields without confusing them
with CLDR categories. Native arrays remain JSON arrays, while
`{"$applePlistType":"data","base64":...}`,
`{"$applePlistType":"date","value":...}`, and
`{"$applePlistType":"real","bits":...}` preserve bytes, UTC seconds, and
all 64 IEEE floating-point bits. A fourth `dictionary` tag safely escapes
genuine dictionaries containing the reserved marker, including nested marker
collisions. Real formatting is independently reconstructed with eighteen
significant digits in both languages; negative zero, NaN, both infinities, and
normal/subnormal magnitudes remain exact. Platform `plutil -convert json`
cannot serialize native data, dates, or nonfinite values, and XML conversion
silently erases negative-zero sign, so `appleTypedPlist` instead invokes actual
Foundation binary conversion before independently decoding typed snapshots.
Foundation property-list integers from `-9223372036854775808` through
`18446744073709551615` remain their exact native types, including 16-byte binary
integer objects, future `NSString...` rule annotations, empty nested objects,
and Unicode-scalar metadata-key ordering. Integer spellings such as `+007`,
`-0x10`, and `0XfF` regenerate as canonical decimal XML; malformed tag payloads,
reserved-field collisions, nulls, duplicate escaped dictionary keys,
noncanonical NaN payloads, fractional binary dates, and ambiguous invented
string categories fail instead of generating a lossy property list.
XML text safely escapes ampersands, angle brackets, and
carriage returns; forbidden control characters fail with
`INVALID_APPLE_PLIST_TEXT`. Unsupported translator descriptions and missing
plural fallbacks fail with stable portable diagnostics. Neither writer emits or
resolves a DTD, even when the original accepted Apple's standard trusted plist
declaration.

Every normalized plist is byte-identical across Java and Rust, reparses into the
original canonical catalog, remains idempotent, and converts through Apple's
actual `plutil -convert json`, or type-preserving Foundation binary conversion,
into precisely the original native dictionary.
The native Swift Foundation oracle additionally loads original and regenerated
resource bundles and formats every shared positioned/reordered/repeated,
mixed-argument, and spelled-out sample. Independent ICU4J and FormatJS runs
must reproduce the same selected text; structurally accepted Unicode markers,
invented categories, invalid positions, missing/unused definitions, and
nonnumeric selector formats are explicitly rejected before migration.

## Source-preserving Xcode String Catalog skeletons

The version-one source sidecar also supports Xcode `.xcstrings` JSON without
changing its FormatJS catalog schema. Java validates the complete native
catalog and finds source values with Jackson's token locations; Rust validates
the same semantics and independently walks original JSON strings, escaped keys,
object/array boundaries, and precise UTF-8/UTF-16 source offsets. Ordinary
translation slots own only the interior of an existing source-locale
`stringUnit.value`. An explicit opt-in can instead own the complete four-byte
`null` token when Xcode records an existing source locale without a value, or
a zero-width boundary inside an active localization object when the source
language key is genuinely absent.

[Apple's String Catalog presentation](https://developer.apple.com/videos/play/wwdc2023/10155/)
distinguishes new, needs-review, and translated workflow states. Real
`xcstringstool` testing additionally shows that an explicit-null or genuinely
absent source locale produces no source-language compiled resource, while
existing translated/review-state target locales still compile. A materialized
source value whose state is `new` is also omitted. Native Xcode additionally
accepts completely empty or absent localization objects, but those inactive
descriptors remain intentionally rejected because they produce no canonical
resource. Java
`LocalizationFileConverters.extractSkeletonWithXcodeSourceInsertion(bytes)` and
Rust `extract_skeleton_with_xcode_source_insertion(bytes)` therefore expose an
existing source-language `null`, or the zero-width position immediately after
an existing target localization, as an ordinary version-one message-ID slot.
Injecting a translation replaces only the exact null token with a compact
`{"stringUnit":{"state":"translated","value":"..."}}` object, or adds only
`,"en":{"stringUnit":{"state":"translated","value":"..."}}` at the
recorded missing-key boundary. An empty translation map preserves every
original byte and the existing default extractor continues rejecting both
fallback shapes. Original neutral
UTF-8/UTF-16 fixtures preserve escaped/supplementary IDs, translated quotes,
hidden and explicit `%n` arguments, ordinary positional placeholders,
previously materialized source review states, every existing target-locale
value/state, `shouldTranslate=false` records, descriptor metadata, and all
surrounding JSON formatting. Apple's real compiler snapshots prove source
resources appear only after translated-state insertion, and actual Foundation
renders each newly materialized value with the correct dummy arguments. Both
renderers independently revalidate the exact original source-locale token or
zero-width object boundary; a forged sidecar cannot redirect a valid message
identity onto a protected target-locale, a `shouldTranslate=false` null, or a
protected target-only object's insertion boundary.

[Apple's locale guidance](https://developer.apple.com/documentation/xcode/choosing-localization-regions-and-scripts)
recommends hyphenated BCP-47 language-region identifiers, but the real Xcode
compiler treats historical `fr_CA` and canonical `fr-CA` as separate physical
`.lproj` directories. The opt-in Java
`extractSkeletonWithXcodeTargetInsertion(bytes, "fr-CA")` and Rust
`extract_skeleton_with_xcode_target_insertion(bytes, "fr-CA")` therefore
resolve one consistent, catalog-wide original spelling and persist it in the
version-one sidecar's optional `appleTargetLocale` field. A missing target
property owns a zero-width boundary, an explicit target null owns its complete
token, and an existing scalar target owns only its original JSON value body.
Inserted/null targets become `translated`, while updates preserve an existing
`needs_review`, `new`, or future state exactly. Actual compiler snapshots show
that `new` **target** values remain present in compiled resources even though
`new` **source** values disappear. Original source-language values, unrelated
localizations, protected descriptors, metadata, comments, spacing, locale
spelling, and UTF-8/UTF-16 bytes stay untouched. Both implementations reject
invalid/source-owned locale requests, conflicting underscore/hyphen aliases,
protected null/object-boundary forgeries, and uncontracted target device or
substitution insertion; Swift Foundation executes every translated scalar and
hidden `%n` argument from the actual target-language bundle.

[Apple's String Catalog guidance](https://developer.apple.com/documentation/xcode/localizing-and-varying-text-with-a-string-catalog)
also explains that a target language may own plural categories absent from its
source language. The same opt-in target API now independently owns every
existing target plural branch: an English `one`/`other` source can expose
Russian `one`, `few`, `many`, and `other` through ordinary
`id#one`/`id#few`/`id#many`/`id#other` slots. Native target-specific argument
positions and zero-width `%3$n` dummy conversions are restored using their
target branch's original placeholder positions, preserving existing
`needs_review`, `new`, `future_review`, and `translated` states without
changing source, unrelated German, or protected entries. Original/translated
compiler snapshots and actual Russian-locale Swift Foundation execution verify
0, 1, 2, 5, 21, 22, and 25 across UTF-8 and UTF-16 sources. Real Xcode accepts
target plural maps missing `other` and even invented category names; both
portable implementations intentionally reject those unsafe shapes with stable
errors. Forged missing-category and protected-category slots also fail closed.

An existing plural sibling in the target locale also provides reliable,
catalog-owned evidence of that language's complete category set. A source
message whose target locale is genuinely missing owns one zero-width `id`
slot; an explicit-null target owns its complete original `null` token. The
translation for either atomic slot is one complete canonical ICU plural
message rather than overlapping `id#category` byte ranges. Both independent
renderers require the exact native sibling category set, create every target
`stringUnit` with state `translated`, preserve source placeholders and hidden
`%3$n` argument ownership, sort newly inserted JSON categories deterministically,
and leave unrelated/protected JSON bytes untouched. Native Xcode snapshots and
Russian Foundation execution verify both inserted trees for 0, 1, 2, 5, 21,
22, and 25 across UTF-8 and UTF-16 sources. Missing `other`, omitted language
categories, invented/duplicate categories, malformed ICU messages, unknown
placeholders, guessed categories without a native sibling, and protected
null/zero-width ownership all fail closed. Target plural creation for an
entirely new locale without trustworthy CLDR category evidence remains
separate work.

[Apple also documents per-language device variations](https://developer.apple.com/documentation/xcode/localizing-and-varying-text-with-a-string-catalog),
including iPhone versus Mac text. The same target-locale API independently
owns each **existing** target-language device branch: scalar Russian iPhone/Mac
values use the sidecar's existing `@device` selector, while nested Russian
`one`, `few`, `many`, and `other` categories use `@device=iphone` and
`@device=mac`. No schema version or fields change. Each target device branch
restores its own original positional arguments and placeholder-anchored
`%2$n`/`%3$n` dummy conversion, preserving all original review states,
source-language devices, `shouldTranslate=false` records, JSON spacing, and
UTF-8/UTF-16 byte boundaries. The actual Xcode compiler emits independent
device dictionaries for both scalar and nested-plural targets; real Russian
Swift Foundation chooses the Mac branch and correct plural category for 0, 1,
2, 5, 21, 22, and 25. Mismatched scalar/plural branch shapes, missing `other`,
nonexistent-device slots, and forged protected device ownership fail closed.

One existing version-one `id` slot now atomically owns an **absent or null
target-language device tree**. Its translation is a complete FormatJS-valid
`{device, select, iphone {...} mac {...} other {...}}`; nested device plurals
contain complete `{count, plural, one {...} few {...} many {...} other {...}}`
branches. The source-owned device set must match exactly, and plural categories
must match existing target-language evidence. Real `xcstringstool` accepts an
`other` device but silently removes it from the compiled resource, so the ICU
fallback must equal the canonical source default device and is never emitted
as a physical device. Each inserted native `stringUnit` becomes `translated`,
devices/categories have deterministic JSON ordering, and hidden `%2$n`/`%3$n`
remain anchored to their source placeholder even when Russian reverses
arguments. Exact null/zero-width ownership preserves source formatting,
protected records, existing target review states, and UTF-8/UTF-16 bytes.
Actual Xcode snapshots and Russian Foundation verify missing/null scalar-device
and device-plural trees; unknown/omitted devices, unequal fallback, incomplete
plural categories, unknown arguments, missing target category evidence, and
forged protected-null slots fail closed.

Existing **target-language substitution definitions** reuse the same
version-one selector/category identity, independently of their source-language
definitions. An English `one`/`other` source can therefore own Russian `one`,
`few`, `many`, and `other` target branches under `id#lanes#few` and
`id#lights#many`, while a scalar target root owns `id` and target iPhone/Mac
sentences own `id#@device#iphone`/`id#@device#mac`. Each root restores its
original target-native `%#@lanes@` and positioned `%2$#@lights@` spelling even
when translated arguments reorder; each target category restores its own
`argNum`/`formatSpecifier` and placeholder-anchored hidden `%4$n`. Existing
root/category `needs_review`, `new`, `future_review`, and `translated` states,
protected records, source branches, and UTF-8/UTF-16 bytes remain unchanged.
Apple documents native substitution markers in its
[plural-localization documentation](https://developer.apple.com/documentation/xcode/localizing-strings-that-contain-plurals);
original neutral scalar/device catalogs were additionally compiled with the
actual `xcstringstool`, and Russian Swift Foundation verifies target category
and Mac-root selection. Missing target selectors/source-owned devices/`other`,
invented categories, missing/duplicated root markers, and forged target selector
ownership fail closed.

An **absent or explicit-null target substitution tree** now owns one atomic
version-one `id` slot at its missing locale boundary or complete JSON `null`.
A scalar translation is one complete ICU sentence containing every
`{selector, plural, ...}` definition; a device translation is one complete
`{device, select, ...}` whose iPhone/Mac sentences contain equivalent shared
selector definitions and whose `other` exactly matches the canonical source
default device. An existing target-language substitution proves each selector's
complete category set and compatible native `argNum`/`formatSpecifier`; its
original target category value also supplies correctly anchored hidden `%4$n`.
Inserted roots/categories become `translated`, retain source-native positioned
`%#@name@` markers, sort deterministic JSON fields, and leave protected records
plus every original UTF-8/UTF-16 byte unchanged. Native Xcode snapshots and
Russian Swift Foundation verify missing/null scalar and device branches,
including reordered markers, independent `one`/`few`/`many`/`other` selection,
and actual Mac execution. Missing `other`/selectors/devices, invented
categories/placeholders, inconsistent shared device definitions, divergent
fallback, and protected-null slot forgery fail closed.

A **genuinely first target-language plural locale** derives its complete native
cardinal categories directly from existing plural runtimes:
existing ICU4J in Java and Mojito's existing complete MF2 plural rules in Rust.
Neither converter maintains a copied Unicode locale table. The independent
FormatJS oracle checks representative Arabic, Russian, Hebrew, Portuguese,
Serbian, Chinese, Japanese, Norwegian, and deprecated-language locales against
actual Node ICU and rejects unsupported tags before silent fallback. Existing
actual same-language plural evidence still wins, and a present target locale
without usable plural evidence cannot bypass its existing ownership boundary.
Original missing/null Russian fixtures have no other Russian entries; both
portable implementations nevertheless insert exact `one`/`few`/`many`/`other`,
anchored source-owned `%3$n`, deterministic `translated` category states, and
untouched protected/German/source records in both UTF-8 and UTF-16. Real
`xcstringstool` compilation and Swift Foundation execute every Russian
category. Unknown locales, the undefined root, and incomplete category
translations fail closed. Apple also documents that Xcode automatically adds
the language-specific categories, including Russian's four forms, in
[Localizing and varying text with a string catalog](https://developer.apple.com/documentation/xcode/localizing-and-varying-text-with-a-string-catalog).

The existing Java ICU and Rust MF2 rule versions can evolve independently of
the installed Node version, so portable conversion does not restrict newer
supported locales to an older test-runtime snapshot. Representative cross-runtime
conformance fixtures continue to verify aliases, regional identity, complete
native categories, unsupported-language rejection, and source-preserving
Xcode/Foundation output.

Regional identity cannot be inferred from cardinal category names alone.
Apple explicitly treats Brazilian `pt-BR` and European `pt-PT` as distinct
String Catalog localizations in the same
[String Catalog localization documentation](https://developer.apple.com/documentation/xcode/localizing-and-varying-text-with-a-string-catalog).
ICU gives both `one`/`many`/`other`, but actual Node ICU, Java ICU4J,
and Swift Foundation select Brazilian `one` versus European `other` for zero;
Foundation also formats one million as Brazilian `1.000.000` versus European
`1\u00a0000\u00a0000`. Original first-locale records deliberately retain catalog-owned
`pt_BR` versus `pt-PT` spelling, compile to distinct physical locale bundles,
and inject complete missing/null regional plural trees without changing
protected German entries, positioned hidden `%3$n`, unrelated regional nulls,
review states, or any untouched UTF-8/UTF-16 byte. Java accepts safe
region/script fallback only when its version-pinned base exists and ICU4J
confirms the exact category set; `pt-BR`, `zh-Hans`, `sr-Latn`, and `ru-RU`
therefore work without accepting the undefined root or unsupported languages.
The requested region always remains intact for native selection and numeric
formatting; category fallback never authorizes cross-regional replacement.

Script identity has a separate native bundle-canonicalization boundary. Apple
models script independently of language and region in
[Foundation Locale.Script](https://developer.apple.com/documentation/foundation/locale/script),
but real `xcstringstool` minimizes Serbian Cyrillic `sr-Cyrl` to `sr.lproj`
and `sr-Cyrl-RS` to `sr-RS.lproj` while preserving Latin `sr-Latn.lproj`;
Chinese `zh-Hans.lproj` and `zh-Hant.lproj` remain distinct. Original Serbian
Latin `sr_Latn` and Cyrillic `sr-Cyrl` first-locale fixtures independently
preserve catalog-owned spelling, distinct Foundation formatting identities,
complete `few`/`one`/`other`, hidden `%3$n`, protected German/null entries,
and exact UTF-8/UTF-16 source templates. Foundation executes both scripts
through their actual, differently named bundles. Native snapshots additionally
prove that `sr` plus `sr-Cyrl`, `sr-RS` plus `sr-Cyrl-RS`, `az` plus
`az-Latn`, and `uz` plus `uz-Latn` each compile successfully into one `.lproj`
and silently discard one competing translation; repeated compiles of identical
source can select either value. Independent Java and Rust
therefore intentionally reject all four observed default-script collisions as
`DUPLICATE_LOCALE`; they never assume that Chinese or unsupported future
`kok-Latn` share those mappings. Wider platform canonicalization requires
additional native evidence before extending this small, fail-closed table.

Apple additionally warns that localization identifiers combine independent
language, region, and script subtags in
[Choosing localization regions and scripts](https://developer.apple.com/documentation/xcode/choosing-localization-regions-and-scripts),
while Foundation's
[minimalIdentifier](https://developer.apple.com/documentation/foundation/locale/language/minimalidentifier)
and Unicode's
[likely-subtag minimization](https://unicode.org/reports/tr35/#Likely_Subtags)
can remove fields that Xcode deliberately keeps. An 82-pair original compiler
matrix therefore derives platform behavior directly instead of treating ICU
minimization as bundle identity: deprecated `iw`/`he`, `in`/`id`, `ji`/`yi`,
`no`/`nb`, `tl`/`fil`, and `jw`/`jv`; obsolete `en-UK`/`en-GB` and
`cs-CS`/`cs-CZ`; case-only language/region/script spellings; regional
Azerbaijani and Uzbek; Mongolian/Kazakh Cyrillic, Bosnian/Croatian/Hausa
Latin, and Punjabi Gurmukhi; grandfathered `i-klingon`/`tlh`,
`i-ami`/`ami`, `i-bnn`/`bnn`, `i-hak`/`hak`, `i-lux`/`lb`,
`i-navajo`/`nv`, `i-pwn`/`pwn`, `i-tao`/`tao`, `i-tay`/`tay`, and
`i-tsu`/`tsu`; regional Klingon; Belgian/Flemish/Swiss sign languages;
grandfathered Bokmål `no-bok`/`nb` and Nynorsk `no-nyn`/`nn`; Mandarin,
Hakka, Xiang, and Cantonese extlangs; three-letter `cmn`/`zh`,
`hbs`/`sr-Latn`, and `mol`/`mo`; case-insensitive variants/private-use
subtags; and truncated English Unicode-numbering extensions all collide in
actual Xcode output. Chinese language/script/region pairs remain distinct even
when Foundation minimizes them, and literal underscore-region spellings can
produce directories distinct from hyphens. Twenty additional positive native
snapshots guard against unsafe overgeneralization: standalone `sh` and
explicit `sh-Latn` remain distinct from `sr-Latn`; `en-BU`/`en-MM`,
`de-DD`/`de-DE`, `hy-SU`/`hy-AM`, `ar-001`/`ar`,
`zh-HK`/`zh-Hant-HK`, calendar-bearing `en-US-u-ca-gregory`/`en-US`,
legacy `no-BOKMAL`/`nb`, `no-NYNORSK`/`nn`, `sv-AALAND`/`sv-AX`,
`el-POLYTONI`/`el-polyton`, and `aa-SAAHO`/`ssy`, POSIX/Oxford English,
reordered Slovenian variants, deprecated Gregorian/phonebook Unicode keyword
spellings, longer private-use payloads, and unmapped `aam`/`aas` all retain
separate directories despite broader Unicode/ICU aliases. Unicode's documented
`sh`→`sr-Latn` expansion is therefore **not** Xcode's rule: bare `sh` stays
independent, while region-bearing `sh-RS` unexpectedly aliases Cyrillic
`sr-RS`, and `sh-Latn-RS` aliases `sr-Latn-RS`. Foundation
`minimalIdentifier`, Node ICU, Unicode aliases, and Xcode disagree on these
boundaries, so compiler snapshots alone own physical bundle identity.
Independent Java/Rust reject every demonstrated collision and resolve a modern
`he`/`he-IL` request to its source-owned deprecated `iw`/`iw-IL` null slot.
ICU lookup canonicalizes deprecated language aliases before selecting
Hebrew's complete `one`/`two`/`other`; original UTF-8/UTF-16 fixtures preserve
the legacy catalog spelling, canonical `he.lproj`/`he-IL.lproj`, source-owned
hidden `%3$n`, unrelated German/protected entries, and translated review
states. Real Swift Foundation also proves that an English argument rendered
inside Hebrew output gains native U+2068/U+2069 bidirectional isolate markers.
Unsupported `kok-Latn` remains excluded, and a source-owned language alias can
never be inserted as a distinct translation target.

The declared catalog `sourceLanguage` need not use the same spelling as its
real development localization. An original compiler matrix confirms that
`sourceLanguage: "he"` owns an `"iw"` value, `"nb"` owns `"no-bok"`,
`"nn"` owns `"no-nyn"`, `"en-GB"` owns `"en-UK"`, `"sr"` owns
`"sr-Cyrl"`, `"zh"` owns `"cmn"`, `"zh-Hans"` owns `"zh-cmn-Hans"`,
and case-only regional spellings own their actual compiler bundle. Previously
both independent portable parsers substituted the message ID as source text
and classified the genuine source localization as a translation. They now
select the exact catalog-owned source spelling through native-proven bundle
identity, retain it as `appleSourceLocalizationIdentifier`, preserve scalar
and plural values/review states/placeholders, and exclude that value from
translated-locale metadata. Normalized Java/Rust writers retain the original
development key; source-preserving UTF-8 and UTF-16 skeletons independently own
that key's scalar and plural bytes without changing unrelated German or
protected entries. Actual `xcstringstool` snapshots and Swift Foundation
execute every original and translated source value. The existing
underscore-versus-hyphen source fallback remains compatible but is not
misrepresented as physical bundle equivalence: `fr_CA.lproj` and
`fr-CA.lproj` remain independently addressable.

Source ownership must still be unique. Actual `xcstringstool` accepts both
`he` and `iw` for a Hebrew development message, multiple undeclared Mandarin
aliases, and the same collision on `shouldTranslate=false` records, then
nondeterministically chooses which value survives in the single development
bundle. More dangerously, a declared `he: null` together with active `iw`
nondeterministically emits either the real alias value or no Hebrew resource
at all. Original native snapshots explicitly admit both observed compiler
outcomes; independent Java and Rust semantic parsers and UTF-8/UTF-16
source-template extractors intentionally reject every competing active/null/
protected owner with stable `DUPLICATE_LOCALE`. This deliberate incompatibility
prevents silent loss and nondeterministic reinjection; exact-format
preservation cannot safely choose one owner without explicit user intent.

Aliased development ownership also applies when the source localization owns
shared native substitution definitions and independent iPhone/Mac sentences.
Target editing previously selected source device branches through the literal
`sourceLanguage` even after scalar/plural extraction was corrected, so native
catalogs with `he`/`iw`, `nb`/`no-bok`, `sr`/`sr-Cyrl`, `en-GB`/`en-UK`, and
`zh-Hans`/`zh-cmn-Hans` sources rejected valid Russian target substitution
trees. Independent Java and Rust validators now resolve the actual source key
before matching source-owned device roots. Original UTF-8/UTF-16 fixtures
retain exact root aliases, `lanes`/`lights` argument numbers and format
specifiers, target-only Russian `one`/`few`/`many`/`other` categories,
selector-owned `%4$n`, independent iPhone/Mac root markers and review states,
protected source/target trees, and original untouched bytes. Real Xcode
compilation and Russian Swift Foundation independently execute both
substitutions on the selected Mac target; requesting the development alias as
a target still fails safely. The same five development aliases also own atomic
insertion when Russian scalar/device substitutions are completely absent or
explicitly null: independent UTF-8/UTF-16 source skeletons preserve the actual
source key, materialize both `lanes`/`lights` definitions with all four Russian
categories, retain source-owned argument positions and target `%4$n`, update
both iPhone/Mac sentences, and leave protected missing/null records untouched.
Untouched explicit-null targets disappear from normalized catalogs because
canonical translated-locale metadata excludes nulls; byte-preserving source
skeletons retain those exact null records until an authorized target edit.

Device identities themselves are open-ended in Xcode's compiler. Original
probes accept unknown `futurecar`, uppercase `IPHONE`, supplementary `🧭raft`,
private-use `\uE000raft`, `applevision`, and `appletv`; this does not mean the
current macOS Foundation runtime can select them. Foundation chooses an actual
`mac` branch first, an explicit `other` scalar fallback when no Mac branch
exists, and the caller's fallback value for unknown-only/iPhone-only/Vision-only
or TV-only resources. Both independent converters retain opaque future device
names, deterministic source-device priority, original protected records, and
individually editable version-one `@device` slots for every unknown/private/
supplementary branch across UTF-8 and UTF-16. Actual original, normalized, and
translated compiler snapshots plus explicit native fallback samples prevent
compiler acceptance from being mistaken for Foundation runtime availability.

First-target insertion has a stricter, independently verified fallback boundary.
An original scalar `device.other` is a real source-owned, independently
translatable fallback: `xcstringstool` emits it into `.strings` while placing
the iPhone, Mac, unknown `futurecar`, private-use `\uE000raft`, and
supplementary `🧭raft` branches into `.stringsdict`. Its Russian translation
therefore need not match the canonical iPhone branch and must remain present
in the inserted target. Conversely a nested `device.other` plural is invalid;
the real compiler rejects it with `Fallback value cannot be further varied`.
Plural device selects still need a synthetic ICU `other` equal to the selected
default device, but that synthetic fallback must never become a physical
device branch. Real FormatJS accepts both private-use and supplementary
select identifiers. Independent Java/Rust therefore parse complete Unicode
device identities, retain native hidden `%2$n`/`%3$n`, generate every pinned
Russian category, preserve actual scalar `other`, reject divergent synthetic
plural fallbacks, and sort device keys by Unicode scalar value for identical
UTF-8/UTF-16 source-template bytes. Native compiler snapshots and Mac
Foundation selections validate both scalar and plural first-target trees.

Obsolete territories and numeric regions have the same source-ownership
boundary without sharing one generic alias table. A modern British `en-GB`
request resolves the original catalog's `en-UK` null slot, writes translated
English `one`/`other` trees under that exact original spelling, and compiles to
the real `en-GB.lproj` bundle; numeric-world `en-001` remains its own
`en-001.lproj` and never aliases plain English. Original UTF-8/UTF-16 source
templates independently preserve both protected/null locales, unrelated
German review state, source-owned hidden `%3$n`, deterministic translated
states, and native `1,000,000` grouping. This rule is intentionally narrower
than CLDR: obsolete Myanmar, East German, and Soviet regions remain physically
distinct because actual Xcode preserves them.

Grandfathered language identifiers require the same original-source ownership
as territories, but **must not** inherit Unicode's unrelated legacy-variant
conversion table. Modern `nb` and `nn` requests resolve only Xcode's original
`no-bok` and `no-nyn` null slots and compile to independent `nb.lproj` and
`nn.lproj`; complete Norwegian `one`/`other` categories and native
nonbreaking-space `1\u00a0000\u00a0000` grouping are verified through Foundation.
Both original UTF-8 and UTF-16 templates preserve legacy spelling, untouched
protected/null German entries, source-owned hidden `%3$n`, and translated
states. Crucially `no-BOKMAL` and `no-NYNORSK` remain **distinct** native
bundles, although Unicode documents those legacy variants as `nb` and `nn`;
bare/region-only `no` aliases Bokmål, but script/variant-qualified `no-Latn`
and `no-BOKMAL` do not. Safe conversion therefore follows observed compiler
context rather than indiscriminately canonicalizing language or variant tags.

Unicode says underscore and hyphen separators are equivalent for locale
semantics in
[Unicode locale identifiers](https://unicode.org/reports/tr35/#Unicode_Locale_Identifier),
but Apple's archived
[Language and Locale IDs](https://developer.apple.com/library/archive/documentation/MacOSX/Conceptual/BPInternational/LanguageandLocaleIDs/LanguageandLocaleIDs.html)
distinguishes language IDs from underscore-style locale IDs and requires
localized resource directories to match. An original compiler/runtime matrix
therefore keeps **physical resource identity separate from normalized CLDR
identity**: `pt_BR.lproj` and `pt-BR.lproj`, `en_US.lproj` and `en-US.lproj`,
and `sr_RS.lproj` and `sr-RS.lproj` coexist with independent values. Swift
Foundation reports both bundle localizations and explicit resource lookup
selects either original directory, even though preferred-localization matching
can return both. Java/Rust preserve separate metadata keys, source-owned
regional spelling, protected/German records, UTF-8/UTF-16 offsets, and the
untouched sibling while editing one explicitly requested Portuguese target.
The earlier `fr_CA`/`fr-CA` fixture was corrected from an invalid duplicate
to a native-verified accepted catalog. Conversely, underscore script/region
spelling can produce real collisions: `zh_Hans_CN`/`zh_CN`,
`zh_Hant_TW`/`zh_TW`, `sr_Cyrl_RS`/`sr_RS`, `az_Latn_AZ`/`az_AZ`,
`iw_IL`/`he_IL`, and region-case aliases each collapse into one bundle and
remain explicitly rejected. CLDR category lookup alone may normalize the
separators; physical source-slot ownership never does.

First-locale **device-owned scalar and nested plural trees** extend the same
contract without borrowing target-language evidence. Original English iPhone
and Mac scalar branches carry source-owned hidden `%2$n`; each English device
plural category carries hidden `%3$n`. Missing/null Russian trees insert one
complete ICU device select, require the source's exact device set, preserve
unknown/private-use/supplementary names, distinguish a real scalar `other`
from a default-device-identical synthetic plural `other`, and derive every
device's
`one`/`few`/`many`/`other` from ICU. Each scalar/category is
`translated`, restores its own original positioned hidden argument, preserves
unrelated German and protected null/missing records, and changes no untouched
UTF-8/UTF-16 byte. Real `xcstringstool` compilation and Swift Foundation
verify Mac selection and every Russian plural boundary. Invented devices,
divergent synthetic default-device fallback, invented/missing categories, unsupported
locales, and protected-slot forgery fail closed even where Apple's compiler
would otherwise accept incomplete categories and silently select `other`.

The same first-locale contract also owns **complete absent/null scalar and
iPhone/Mac target substitution trees**. Their original English source owns
two independently positioned selectors, `lanes` (`argNum=1`, `lld`) and
`lights` (`argNum=2`, `d`), but has no Russian entries and no hidden fourth
argument. One complete ICU sentence/select inserts both selectors with all four
pinned Russian categories; the iPhone/Mac `other` fallback must equal the
source-owned default device, and both roots restore their exact positioned
`%#@lanes@`/`%2$#@lights@` markers. Categories use source category `one` when
available and source `other` for target-only `few`/`many`, preserving only
hidden arguments that the selected source category actually owns. Therefore
no target-only `%4$n` is invented from unrelated locales. Every inserted
root/category receives `translated`, source and unrelated German records stay
byte-identical, protected null/missing records remain untouched, and UTF-8/
UTF-16 source skeletons produce the exact expected JSON. Actual Xcode
compilation and Swift Foundation run scalar and Mac plural combinations;
incomplete/invented categories, missing selectors, unknown arguments/devices,
inconsistent shared definitions, unequal default-device fallbacks, and
unsupported/undefined locales fail closed.

The first-locale contract also composes with real compiler-equivalent
development owners: Hebrew `he`/`iw`, Norwegian `nb`/`no-bok`, Serbian
`sr`/`sr-Cyrl`, British `en-GB`/`en-UK`, and Mandarin
`zh-Hans`/`zh-cmn-Hans` each own the source substitution/device tree even
when no Russian message or category exemplar exists anywhere in the catalog.
Both independent implementations preserve the actual aliased source key and
unrelated German localization, derive all four Russian selector categories
only from ICU, never invent source-absent `%4$n`, retain protected
missing/null records, and reject the source alias as a target. Original
UTF-8/UTF-16 skeletons, normalized compiler snapshots, and real Russian Swift
Foundation selections independently verify every scalar and Mac branch.

Review states are a distinct ownership boundary. Apple describes `New`,
`Needs Review`, and reviewed/translated states plus stale extracted entries in
[Discover String Catalogs](https://developer.apple.com/videos/play/wwdc2023/10155/),
and describes the distinct machine-generated state in
[Localizing your app using agents](https://developer.apple.com/documentation/xcode/localizing-your-app-using-agents).
An original 320-combination `xcstringstool` probe accepts every tested known,
unknown, and future source/target/extraction state. Existing target units
compile regardless of their review state; a source `new` unit is excluded
unless its entry has `extractionState: "manual"`. The canonical Java/Rust
parsers therefore preserve review and extraction states as opaque strings,
including future values, and source-preserving UTF-8/UTF-16 target writers
modify only owned value bytes. Source states, target states, protected
records, unknown extraction states, and unrelated locales remain unchanged;
manual versus automatic source compilation and every target state are verified
against original/localized native snapshots and real Foundation. Existing
version-one slots own value/null/insertion bytes, not state tokens, so a
future approved review-state transition must define independent
source/target/selector/category state ownership, authorized transitions,
versioning, protected-entry rejection, unchanged-state defaults, and native
compiler/runtime snapshots before it can mutate any original state. An audited
future CLDR release still requires all upgrade-audit blockers to be resolved.

Ordinary scalar messages use their message ID. Top-level plural categories and
selected-device nested plurals use `id#category`, while scalar device variants
use the canonical default-device ID. Referenced `%#@name@` substitution trees
reuse the sidecar's existing optional `selector` field, making each nested
plural branch independently editable as `id#selector#category` without changing
the schema version. The root message separately owns its structural sentence
template; each `{selector}` reference restores its exact source-owned native
marker, including repeated `%#@name@` and positioned `%2$#@name@` spellings.
Selectors may reorder, but missing or duplicated references fail with stable
`INVALID_SKELETON_SUBSTITUTION`. Unicode selector names restore their original
numeric placeholder spelling in independently translated plural branches.
Apple's compiler accepts device-specific substitution references only when
their definitions live on the parent localization: definitions inside an
individual device branch are ignored and fail with undefined substitutions.
Both independent semantic parsers and normalized writers therefore inherit
root-owned definitions for the selected default-device message. Default source
templates own that selected sentence plus shared root-level plural branches;
unselected iPhone/iPad/Mac/watch sentences remain untouched. Explicit opt-in
Java `LocalizationFileConverters.extractSkeletonWithXcodeDevices(bytes)` and
Rust `extract_skeleton_with_xcode_devices(bytes)` instead expose every
source-language device sentence as a separate version-one
`message#@device#iphone`, `message#@device#mac`, or
`message#@device#applewatch` slot while retaining shared
`message#selector#category` plural-definition ownership. Each device branch
restores its own positioned/repeated native `%#@selector@` markers, ordinary
printf placeholders, disabled `%n`, escaped `%%n`, physical newlines, and review
state without editing protected target-localization trees or surrounding JSON
syntax. Missing or duplicated shared selector references fail closed for the
specific translated device branch. Real `xcstringstool` resources and Swift
Foundation runtime confirm the actual Mac sentence is independently translated
alongside shared singular/plural branches; selected-only legacy sidecars remain
valid and unchanged.
When a device itself contains its own nested plural axis, the same opt-in API
independently owns each combined condition through
`message#@device=iphone#one`, `message#@device=iphone#other`,
`message#@device=mac#one`, and `message#@device=mac#other`. Category-local
native numeric placeholders and disabled conversions are reconstructed from
their own source branch; no default-device formatting leaks into the Mac
variants. The sidecar remains schema version one because its existing
`selector` and `variant` fields already represent this chained native identity.
Original neutral UTF-8 and UTF-16 source fixtures compile through real
`xcstringstool`, preserve existing French device translations and review
states, and run Mac singular/plural selections through actual Swift Foundation.
Apple explicitly documents chained variation conditions in
[Discover String Catalogs](https://developer.apple.com/videos/play/wwdc2023/10155/).
Apple's Xcode 16.3 release notes specify that implicit argument numbering
inside a substitution branch starts at the substitution's own assigned
argument. Both implementations therefore distinguish its numeric selector from
later independent string/numeric placeholders, preserve explicitly reversed
native positions, and retain canonical argument names/positions through
normalized and byte-preserving writers. Compiler-accepted branches that reuse
the selector as a string, or assign incompatible types to the same argument
across plural categories, fail safely with `INVALID_PLACEHOLDER`.
Existing native Foundation placeholders are restored from their canonical names
and translated values are JSON-escaped without changing their original
surrounding quotes. Source-language spelling, all translated locales,
review/extraction states, root/descriptor metadata, escaped JSON key spelling,
whitespace and object ordering, non-owned device variants, substitution
definitions/argument numbers/format specifiers, and `shouldTranslate=false`
records remain byte-identical.

Independent neutral fixtures cover intentionally irregular JSON layout,
protected locale and device branches, escaped/supplementary message IDs,
positional placeholders, both plural categories, source review states,
UTF-8 BOM, both UTF-16 byte orders, and CRLF. Dedicated substitution fixtures
cover two independent reordered selectors, repeated and explicitly positioned
native markers, Unicode selectors, implicit argument positions, independently
editable plural branches, protected French substitution trees, preserved
source/target review states, localization-root definitions shared by iPhone,
iPad, Mac, and watch branches, rejected device-local definitions, UTF-16 source
offsets, mixed implicit string/numeric substitution arguments, explicitly
reversed argument positions, unsafe positional type collisions, and CRLF.
Apple's actual
`xcstringstool` compiles every original and translated encoding variant;
Foundation compares every generated localized `.strings`/`.stringsdict`
resource and runs 40 original/translated Swift Foundation formatting samples
across independent singular/plural combinations, reordered positional
arguments, repeated selectors, compiler-sanitized Unicode selectors, implicit
arguments, mixed/reversed native branch arguments, and protected Mac device
branches. An empty translation map reproduces the entire original byte stream.

The ordinary extraction API still rejects explicit-null source-localization
fallback messages with `UNSUPPORTED_SKELETON_SOURCE`; shared
`sourceSkeletonErrors` entries keep that preexisting default behavior
implementation-independent. The explicit source-insertion API handles both
JSON `null` source-language keys and genuinely absent source-language keys
through protected zero-width ownership; the target-insertion API separately
owns existing, null, and absent scalar locales, existing target plural
branches, atomic missing/null target plural descriptors with an existing
same-language category exemplar, and independently owned existing target
scalar-device/device-plural branches, independently owned target substitution
roots/category definitions, atomic null/missing target scalar-device/
device-plural trees, and atomic null/missing scalar/device substitution trees.
Genuinely new target plural locales derive their exact category sets from the
independent plural runtimes described above; unsupported/undefined locale tags
and existing locales lacking usable evidence still fail closed. Review-state
changes for existing translated values remain a separate future contract.
Top-level or
device-nested plural references to substitutions are invalid to Xcode itself
and fail earlier with `INVALID_XCSTRINGS`; scalar device alternatives and
device-owned plural categories already have independent source-slot ownership.

## Normalized Xcode String Catalog regeneration

Java and Rust independently regenerate deterministic UTF-8 `.xcstrings` JSON.
All objects use Unicode-scalar key ordering and two-space JSON indentation;
source language spelling, catalog versions, unknown root/descriptor properties,
review/extraction states, positional placeholders, escaped literal percentages,
substitution trees, source-device variations, translated locales, locale
spellings, translated plural states, and source nested plural/device branches
remain intact. Canonical edits update the selected source string/plural branch
without discarding its otherwise unknown Xcode metadata.

Unlike earlier assumptions based on `xcode-select`, this host has a complete
Xcode installation. Invoking
`/Applications/Xcode.app/Contents/Developer/usr/bin/xcstringstool` directly
works without changing developer-directory settings or accepting additional
licenses. The native oracle compiles original and normalized catalogs into
real per-locale `.strings`/`.stringsdict` resources, then uses Foundation
`plutil` to compare their decoded dictionaries. Catalogs whose intentionally
excluded `shouldTranslate=false` source entries still produce a source-language
resource use a separately declared normalized compiler snapshot rather than
claiming impossible source-skeleton preservation.
Referenced substitution markers and independently selected plural branches
remain real native Xcode syntax rather than raw ICU fragments; source and
target substitution review states survive the same normalized native checks.

## Native platform truth

Platform documentation is useful but insufficient: Android's actual compiler
preserves some XML-provided Unicode whitespace despite published collapse
guidance, and Apple's Foundation parser accepts uppercase Unicode escapes with
zero to four ASCII digits, narrow grammar whitespace and bare identifiers,
comments between all syntax tokens, and exact escaped physical LF/CR/CRLF.
These behaviors were measured with original neutral fixtures rather than copied
source files.

The shared manifest can attach platform-native output snapshots:

- Official standalone Google AAPT2 compiles Android resources and checks real
  strings, styled UTF-16 span attributes/offsets, plural values, array entries,
  aliases, resource-directory configuration normalization, exact unresolved
  feature-flag rejection diagnostics, read-only stripping, mutable conditional
  string/plural/array APK entries, simultaneous fallback and positive/negated
  runtime alternatives, condition-specific source-set ownership and first-wins
  equal-priority mutable collisions, product-independent mutable branches,
  build-time macro declarations/chains/cross-file precedence and fully expanded
  styled/plural/typed-array APK values,
  ordered last-definition-wins flag modes, SDK-10000 runtime-array link
  boundaries, actual product/overlay selection, and genuine `--pseudo-localize`
  output
  across filename-level translation boundaries.
- macOS `plutil` invokes Foundation's Apple strings parser and checks parsed
  dictionaries across UTF-8, both UTF-16 byte orders, and original bounded
  binary strings/stringsdict property-list object tables, plus native plist
  dictionaries containing adaptive-width and device-specific rules. A Swift
  Foundation formatter independently executes original and normalized
  positioned legacy-plural resource bundles; this catches runtime failures
  that structural `plutil` validation cannot detect.
- The installed Xcode `xcstringstool compile` creates actual localized
  `.strings`/`.stringsdict` products for original and normalized modern String
  Catalogs; Foundation independently decodes every generated resource.
- GNU `msgfmt` creates binary MO files, which are decoded independently to
  validate contexts, fuzzy/untranslated state, named placeholders, plural
  indexes, encoded UTF-8 escapes, and normalized writer output; strict header
  checks verify declared plural expressions and translation counts.
- A tiny independently compiled helper runs JDK `Properties.load(Reader)` to
  check escaping, natural/logical lines, comments, delimiters, Unicode
  boundaries, selected legacy encodings, and normalized-writer dictionaries.
- Installed frontend `intl-messageformat` and ICU4J independently execute
  canonical plural messages for all integers in GNU's validation range,
  proving exact selectors and merged CLDR categories preserve runtime behavior.
- Android strings, arrays, plural branches, styles, and protected XLIFF spans
  treat native `#`/`##` as literal text. Generic `TYPE_ANY` resources and
  untyped arrays compile leading `#abc` into an opaque color primitive, while
  `\\#abc`, `\\u0023abc`, `"#abc"`, and `format="string"` remain genuine
  translatable strings. Canonical Java/Rust plural builders quote each
  contiguous hash run once; real FormatJS and ICU4J independently verify styled,
  protected, escaped, and default/tablet product selections without replacing
  native hashes with counts. Optional version-one
  `androidPluralPlaceholderExamples` metadata distinguishes omitted,
  explicitly empty, differing, and occurrence-reordered native `example`
  attributes for the same protected placeholder ID in each plural category;
  both normalized writers and exact source templates reproduce the correct
  original attributes, and forged examples/categories fail closed.
  A conventional protected `<xliff:g id="arg0">%1$s</xliff:g>` with no
  `example` otherwise has the same FormatJS placeholder as an ordinary raw
  `%1$s`. Optional `androidProtectedPlaceholderOccurrences` and per-category
  `androidPluralProtectedPlaceholderOccurrences` therefore retain each native
  occurrence as either an ordinary `null` or a protected object carrying its
  optional `example`. Scalars, nested styles, string-array entries, mixed
  wrapped/plain occurrences, and independently reordered plural examples retain
  exact protection through both normalized writers and original-byte source
  templates. Both implementations reject forged ownership, invalid occurrence
  counts, unavailable examples, and unrelated plural categories.
  Source-template compiler/linker snapshots preserve primitive ownership,
  exact inline-attribute entity lexemes, and both selected products while
  replacing only authorized translated values.

Explicit manifest policies identify deliberate differences such as safer XML
rejection, required ICU plural fallbacks, duplicate-message protection, and
retention of untranslated gettext source strings. Native oracles are validation
tools, never dependencies of production Java or Rust parsers.

## Differential legacy Okapi extraction

The Java conformance suite boots Mojito's actual `AssetExtractor`, real
`AssetPathToFilterConfigMapper`, configured custom Okapi filters, and normal
extraction pipeline against the same original neutral fixture files. Manifest
policies explicitly distinguish plain-message equivalence, deliberate semantic
differences, valid platform resources rejected by the existing filter,
uncompilable platform resources the existing filter nevertheless extracts, and
asset paths with no existing production route. Stable JSON
snapshots include the real filter configuration, ordered native text-unit names,
source text, translator comments, plural categories/fallback names, and usages.

This exposes concrete migration hazards instead of assuming Okapi parity:

- Android arrays use `_0` legacy names rather than canonical `[0]` IDs, plural
  filters synthesize all six CLDR quantities, and platform references plus
  `translatable="false"` arrays/plurals can leak through current extraction.
- Mixed native array/plural references are incorrectly exposed as translatable
  text by the legacy filter, while AAPT2-preserved item-level `translatable`
  attributes must not suppress the individual source message.
- A package/private/type/theme reference boundary produces 43 actual legacy
  units versus 19 projected canonical units: 15 standalone aliases and nine
  reference-valued array slots leak into translation, while five referenced
  plural quantities produce source mismatches. The shared Java/Rust shadow
  snapshot captures all 29 differences without changing production routing.
- Doubled native resource aliases and recursive build-macro references produce
  17 canonical descriptors, or 22 projected comparison units, versus 30 actual
  legacy units. Existing extraction leaks nine standalone/array aliases,
  preserves six unresolved macro or plural sources, and omits one generic-array
  translation; shared Java/Rust shadow snapshots retain all 16 differences.
- Whitespace-bearing resource aliases produce 11 projected canonical units
  versus 27 real legacy units. Existing extraction leaks 17 opaque standalone
  or array references, reports five referenced plural branches as translatable
  sources, and misses one generic-array message; the shared report captures all
  23 differences while native snapshots retain CR/LF/TAB and Unicode spacing.
- XML attribute controls produce 19 projected canonical units versus 17 actual
  legacy units: two generic/array messages are missing, two array translator
  comments differ, and five protected/style source strings are malformed.
  Native Java/Rust writer round trips preserve every description/example
  TAB/LF/CR/CRLF, with byte-exact linked annotation span verification.
- Literal hash/color resources produce 69 projected canonical units versus 61
  actual Okapi units. The legacy filter misses four generic string declarations
  and four translatable untyped-array positions, loses one array translator
  comment, preserves 24 category-specific protected-placeholder XML source
  spellings, and duplicates all six default/tablet plural identities; the
  shared Java/Rust shadow snapshot captures all 45 differences.
- Native build-time macro package/visibility/namespace references produce 25
  projected canonical units versus 24 real Okapi units. Existing extraction
  misses the generic string entirely and preserves 22 unresolved private,
  application-package, `res-auto`, public/private namespace, definition-scoped,
  chained, styled, protected, array, and plural source spellings.
- Definition-scoped macro resource aliases produce ten canonical projected
  units versus 16 real Okapi units. Existing extraction leaks six standalone or
  reference-valued messages and preserves three wrong plural, protected, or
  foreign-wrapper source spellings; native original and normalized linked
  snapshots verify the portable local-reference identities.
- Theme-reference attribute dependencies produce eight canonical projected
  units versus 13 real Okapi units. Existing extraction leaks all five
  untranslated theme/macro-reference array slots and treats the referenced
  plural branch as translatable text; typed attribute skeletons preserve actual
  compiler/linker behavior without changing legacy production routing.
- Weak styleable dependencies produce seven canonical projected units versus
  12 real Okapi units. Existing extraction leaks all five untranslated theme
  references and treats the referenced plural branch as source text, while
  dropping the compiled ordered `R.styleable` build contract entirely; the
  shared shadow snapshot records five unexpected legacy units and one source
  mismatch.
- Native integer-boundary resources produce one canonical unit versus ten real
  Okapi units: the existing extractor leaks all nine nontranslatable theme
  references, while the portable catalog retains exact signed/unsigned
  32-bit symbols and default-only build declarations.
- AAPT2 trims spaces and XML-provided tabs/newlines around plural quantities;
  the existing Okapi Android filter instead throws a reproducible
  `java.lang.RuntimeException: Invalid plural form:  one ` on valid resources.
- The existing Okapi filter entirely misses generic `<item type="string">`
  resources and leaks strings marked `translatable=" FALSE "`, even though
  AAPT2 accepts the case-insensitive, whitespace-padded boolean as false.
- AAPT2's generic `TYPE_ANY`/explicit-format fixture compiles 12 nontext
  primitive resources and retains nine translatable messages. Actual legacy
  Okapi extracts only the single ordinary string and misses all eight generic
  text resources; the native primitive values and exact missing IDs have
  independent compiler, differential, and Java/Rust shadow snapshots.
- Heterogeneous `<array>` resources carry eight genuine translatable string
  entries across two native arrays, plus six nontext primitive slots and three
  in-array references. The real Okapi filter extracts only the ordinary control
  string; all eight typed-array translations are absent from its legacy output
  and appear explicitly in the shared migration-shadow snapshot.
- Undocumented generic `<bag type="array|string-array|plurals">` resources
  produce 14 real canonical text units plus one ordinary control string. The
  configured Okapi filter ignores every generic bag and returns only that
  control, so its actual extraction and bounded migration-shadow snapshots
  expose all 14 otherwise silently lost translation IDs.
- AAPT2 compiles resources in exact lowercase `donottranslate*` filenames but
  marks the complete source file nonlocalizable and generates no
  pseudolocales. The configured Okapi filter ignores this file boundary and
  emits 18 forbidden strings, array items, and synthesized plural branches;
  shared Java/Rust shadow snapshots classify all 18 as `unexpected_legacy`.
- AAPT2 rejects unresolved Android resource/array `featureFlag` declarations,
  but the configured Okapi filter silently extracts ten text units from the
  same uncompilable original file: two gated strings, both conditional array
  positions, and six synthesized gated-plural quantities. A dedicated
  `compiler_rejected` manifest policy captures the actual legacy units and
  native rejection independently without pretending a canonical catalog exists.
- When the same kind of resource is compiled with explicit read-only feature
  values, AAPT2 exposes 19 projected canonical text units while real Okapi
  emits 26 units without evaluating the build configuration. Its shared
  Java/Rust shadow snapshot records 19 exact differences: one duplicate
  mutually exclusive resource, five missing enabled generic/bag/typed-array
  messages, two sources shifted by array compaction, and eleven forbidden
  disabled/resource-reference/plural units.
- Read/write AAPT2 flags preserve both runtime-conditional string branches and
  six projected plural quantities, plus a generic conditional string. The
  existing Okapi filter returns nine of those ten projected units and entirely
  misses the generic declaration; the shared cross-language shadow snapshot
  records exactly one `missing_legacy` identity.
- Product-aware AAPT2 selection reduces a mixed-device fixture to 13 projected
  runtime text units, while actual legacy Okapi still emits 26 units from every
  default, tablet, watch, disabled, and plural variant. Its shared migration
  snapshot records 14 identity-level differences: twelve duplicate legacy
  resource/plural IDs, one missing compacted generic-array message, and two
  forbidden variants for a resource whose selected tablet declaration is false.
- A disabled `flag(condition)` resource-path directory removes its entire file
  from the real linked APK. The existing Okapi filter ignores that build gate
  and emits ten forbidden units, including duplicated product identities and
  six synthesized plural quantities; the shared shadow report classifies every
  leakage without enabling the new routing path.
- An original namespace/control/identifier fixture produces 13 projected
  canonical units but 15 real legacy units. Okapi descends into skipped control
  elements and foreign wrappers, preserves unnormalized padded resource names,
  assigns wrong array positions around ignored controls, accumulates comments,
  and fails to retain product identity. Its implementation-neutral migration
  snapshot records 25 differences: three comment mismatches, ten missing
  canonical units, and twelve unexpected legacy units.
- Anonymous, empty-ID, numeric, accented, CJK, emoji, combining-mark, array,
  and plural protected sections all retain their native `<x:g>` XML wrappers in
  actual Okapi extraction. The original boundary fixture has 21 canonical units
  and 21 legacy units, but all 21 source strings mismatch their normalized
  FormatJS protected-placeholder representations.
- Android product-specific strings produce repeated identical legacy IDs while
  the portable catalog preserves `@product=...` identity.
- The existing Apple regex filter misparses punctuation-bearing keys and
  Foundation-supported single-quoted/key-only entries; comments retain native
  whitespace that canonical translator descriptions intentionally normalize.
- Legacy Java-properties extraction keeps escaped native keys/values and
  leading-space comments rather than the JDK-normalized canonical dictionary.
- Generic JSON extraction treats `description` as a translatable child and
  flattens FormatJS descriptor paths; plain JSON string maps remain equivalent.
- The existing extension mapper accepts `.pot` but rejects ordinary `.po`, and
  it has no `.xcstrings` route.

These differential checks observe existing behavior only. They do not add a
production route, alter filter behavior, claim the Rust parser wraps Okapi, or
silently substitute portable extraction for existing workflows.

## Standalone observational comparison

The manifest's separate `shadowComparisons` map canonical fixtures and actual
Okapi snapshots to implementation-neutral JSON reports. Java and Rust
independently restore native printf spellings, Android array/product IDs, all
six synthesized plural categories, gettext context/source identities, comment
and usage shapes, and duplicate identities before classifying differences.
Stable categories are `legacy_projection_collision`, `duplicate_legacy`,
`missing_legacy`, `unexpected_legacy`, `source_mismatch`, `comment_mismatch`,
`plural_mismatch`, and `usage_mismatch`. A legacy projection collision retains
the sorted, source-free native `canonicalIds` for every product- or
feature-qualified scalar, array entry, or plural category collapsed into the
same old Okapi identity. Genuine duplicated legacy text units remain separately
classified, and distinct portable resources are never described as canonical
duplicates. Three real extraction snapshots currently preserve 35 qualified
native identities across 16 projection-collision groups. Fixture reports retain
message IDs for local diagnosis; they do not constitute production telemetry.
The extraction observer hook is installed but disabled by default behind
`l10n.file-formats.portable.shadow.enabled=true`; it preserves legacy results,
bounds sampling and payload size, isolates failures, and avoids message IDs,
asset paths, source text, translations, or dynamic errors in metric dimensions.

Existing default Okapi routing still rejects ordinary `.po` and `.xcstrings`,
and existing parser failures occur before any post-extraction observation could
run. Durable source-template transport, route enrollment beyond already
discovered CLI file types, operational alerting, and staged cutover remain
separate future controls.

## Conformance and rollout

`file-formats/conformance/manifest.json` and real source fixtures are the only
behavior oracle. The integrated Java implementation runs in `common`; the
standalone Rust crate runs the identical fixture cases. Neither implementation
wraps Okapi or calls the other language. `file-formats/conformance/run.py`
coordinates the shared fixture validator, all available native oracles,
independent Java/Rust overlay suites, real legacy Okapi differential snapshots,
shared Java/Rust shadow reports, and both standalone implementations.

Workflow-aware APIs apply Mojito's intentional custom-filter
policies: configured Android comments and
output options; real FormatJS, Chrome, and Evolve JSON extraction/context rules;
Apple multiline usage/comment ownership; lossless configured JSON protected
inline-code extraction/reversal and byte-preserving changed JSON source
templates; stable translation-memory MD5 inputs;
untranslated-entry removal across OpenStep and XML Apple property lists;
native LF/CRLF gettext source-template and multiline-context cleanup;
UTF-16/BOM preservation; and explicit import-only
target comments plus ICU-backed Android/Apple/PO plural completion, including
native stringsdict category ownership and legacy gettext copy mappings. The
complete option inventory, configured-filter and import-filter differentials,
and deliberate compiler-correct legacy-escaping difference are
documented in `026-portable-localization-custom-filter-parity.md`.

Configured JSON inline-code rules are validated, compiled, and ordered once per
option set in each implementation. A representative warmed Java workload with
20,000 messages and three real Okapi placeholder rules improved from 77–81 ms
to 59–66 ms after removing repeated rule-count scans and per-message list
allocation. The temporary measurement harness was not retained in production
or test code.

The existing `push`, `pull`, localized-asset `import`, and client-side `extract`
commands now accept
`--converter portable`; the default remains `--converter okapi`. Selection
travels through the existing
filter-option list, including asynchronous and parallel requests, so no new
commands, REST payload fields, persisted jobs, or duplicate CLI datasets are
required. Server extraction projects canonical IDs, source text, descriptions,
plural forms, and usages into the existing translation-memory model. Translated
output uses the original source content as its template, preserves current
status filtering, parent inheritance, removal, and pull-run bookkeeping, and
resolves canonical source-slot identities independently from legacy TM names.
Native localized-asset import matches existing used source units, inherited
translations, equal-target status policy, target comments, integrity checks,
and translation-memory identities without entering the Okapi pipeline.
GNU gettext output also expands each independent English two-slot plural into
the target locale's native plural-slot count, retains the exact configured
plural formula, and records only the TM variants actually written; Russian
three-form output and multiple plural messages are covered by the existing CLI
dataset. Localized gettext imports resolve blank or absent `Language` headers against
their actual target locale, retain every category owned by each native plural
index, and emit all required French, Japanese, Russian, and Croatian forms.
Configured Android, Apple `.strings`/`.stringsdict`, gettext `.pot`, Java
properties, generic/Chrome JSON, FormatJS, ordinary CSV, Adobe Magento CSV,
Microsoft `.resx`/`.resw`, Google `.xtb`, JavaScript, TypeScript, HTML, and YAML
reuse the existing real CLI push/pull datasets. Format-specific implementations
preserve customized extraction identities, translator comments, protected
content, source-owned markup, configured key selection, indexed YAML sequence
ownership, exact customized Java-properties comment whitespace, correctly
decoded JSON translator notes, Apple escaped source identities and owner
comments/usages, and exact original
source templates instead of treating upstream Okapi defaults as the compatibility
target. The legacy Android dataset has compiler-invalid numeric
resource names, so its portable integration test repairs only those identifiers
in a temporary copy. Existing source skeletons remain request-scoped rather
than durably persisted. Apple `.stringsdict` output removes source categories
the target locale does not own and completes missing locale-owned categories
by copying translated `other` values, preserving both Russian `few`/`many`
and Japanese `other` without rewriting the surrounding source template.
Ordinary `.po` and `.xcstrings` still require CLI file
discovery enrollment; bilingual XLIFF remains unsupported in portable mode.

XLIFF is deferred intentionally. It requires separate contracts for bilingual
segments, inline code equivalence, notes, target states, skeletons, and XLIFF
1.2 versus 2.x before either runtime should claim support.
