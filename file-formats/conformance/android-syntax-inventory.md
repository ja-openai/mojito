# Android resource syntax inventory and independent oracle

The examples in this repository are original, neutral fixtures. They describe
Android syntax behavior but are not copied from Android documentation, AOSP
tests, product resources, or another parser's fixture corpus.

## Authoritative references

- Android resource syntax, XML-versus-Android escaping, quoted whitespace,
  formatting, supported styling tags, arrays, and plural categories:
  <https://developer.android.com/guide/topics/resources/string-resource>
- Official heterogeneous `<array>`/`TypedArray` resources, mixed native value
  types, and integer-array declarations:
  <https://developer.android.com/guide/topics/resources/more-resources>
- Official resource references, package-qualified aliases, and alternative
  resource identifiers:
  <https://developer.android.com/guide/topics/resources/providing-resources>
- Nontranslatable `xliff:g` sections and translator-facing example attributes:
  <https://developer.android.com/guide/topics/resources/localization>
- XML 1.0 attribute-value normalization, including literal TAB/LF/CR becoming
  spaces while numeric character references retain their original characters:
  <https://www.w3.org/TR/xml/#AVNormalize>
- XML 1.0 well-formed document/prologue rules and namespace reserved-prefix /
  expanded-attribute uniqueness constraints:
  <https://www.w3.org/TR/xml/>
  <https://www.w3.org/TR/xml-names/>
- AOSP's actual `ResourceParser`, including undocumented generic `<bag>`
  dispatch, illegal nested `xliff:g`, unknown namespaces, span construction,
  comments, and resource parsing:
  <https://android.googlesource.com/platform/frameworks/base/+/b092c78a7a0e/tools/aapt2/ResourceParser.cpp>
- AOSP's current `ParseAttrImpl` and `ParseDeclareStyleable`, including
  implicit/composite format masks, signed integer bounds, enum/flag child
  declarations, weak versus strong declaration merging, untyped/framework
  references, ordered repeated styleable entries, duplicate-symbol rejection,
  and native generic `<bag type="attr">`/`<bag type="declare-styleable">`
  dispatch:
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/ResourceParser.cpp>
- Android framework's actual `ResTable::stringToInt` lexical grammar, decimal
  leading-zero behavior, lowercase-only unsigned hexadecimal, signed decimal
  range, and 32-bit overflow checks:
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/androidfw/ResourceTypes.cpp>
- Android's generated custom-view `R.styleable` and custom-attribute contract:
  <https://developer.android.com/develop/ui/views/layout/custom-views/create-view>
- AOSP's current compile pipeline, exact-case `donottranslate` filename-prefix
  policy, file-wide pseudolocalization gate, original directory configurations,
  inherited `flag(...)` conditions/intermediate filenames, and resource parser
  defaults:
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/cmd/Compile.cpp>
- AOSP's exact Unicode XID-start/XID-continue resource-entry validator:
  <https://android.googlesource.com/platform/frameworks/base/+/b092c78a7a0e/tools/aapt2/text/Unicode.cpp>
- AOSP's actual escape, quote, apostrophe, Unicode, and whitespace state
  machine:
  <https://android.googlesource.com/platform/frameworks/base/+/3af4465dd753/tools/aapt2/ResourceUtils.cpp>
- AOSP's current quote/escape builder and resource parser, including
  nontranslatable resource validation, style-boundary quote resets, native
  protected-section handling, and nested `xliff:g` rejection:
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/util/Util.cpp>
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/ResourceParser.cpp>
- AOSP's namespace-ordered XML attribute comparator and native inline-span
  serializer, including prefix-erased style attributes:
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/xml/XmlPullParser.h>
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/ResourceParser.cpp>
- AOSP's exact percent scanner, positional argument detection, literal `%%` /
  `%n` handling, optional relative-argument dollar marker, and early
  `Time.format()` directive bypass:
  <https://android.googlesource.com/platform/frameworks/base/+/HEAD/tools/aapt2/util/Util.cpp>
- AOSP's actual package/private/create-ID/theme reference parser and its
  resource-type allowlist:
  <https://android.googlesource.com/platform/frameworks/base/+/b092c78a7a0e/tools/aapt2/ResourceUtils.cpp>
  <https://android.googlesource.com/platform/frameworks/base/+/a3462cecdccc/tools/aapt2/Resource.cpp>
- Android framework's actual `ExtractResourceName` splitter, including its
  undocumented consumption of one additional leading `@`:
  <https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/libs/androidfw/ResourceUtils.cpp>
- AOSP's macro package/visibility/namespace resolver, one-value assertion,
  preserved definition-scoped namespace stack, and actual reference-linker /
  product-filter execution order:
  <https://android.googlesource.com/platform/frameworks/base/+/master/tools/aapt2/link/ReferenceLinker.cpp>
  <https://android.googlesource.com/platform/frameworks/base/+/master/tools/aapt2/cmd/Link.cpp>
- AOSP's documented span-boundary whitespace quirks and flattened resource
  representation:
  <https://android.googlesource.com/platform/frameworks/base/+/c4bbfd1/tools/aapt2/ResourceUtils.h>
- AAPT2's unescaped `annotation;key=value` native span construction and the
  Android framework's actual semicolon-splitting runtime annotation decoder:
  <https://android.googlesource.com/platform/frameworks/base/+/HEAD/tools/aapt2/ResourceParser.cpp>
  <https://android.googlesource.com/platform/frameworks/base/+/c3bc12c484ef/core/java/android/content/res/StringBlock.java>
- Java's exact signed font-size/height parser, including BMP Unicode decimal
  digits, supplementary UTF-16 surrogate rejection, and 32-bit overflow:
  <https://docs.oracle.com/en/java/javase/18/docs/api/java.base/java/lang/Integer.html>
- Android's exact 23-name color map, `#RRGGBB`/`#AARRGGBB` grammar,
  Unicode-capable signed hexadecimal parsing, and invalid-to-black fallback:
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/graphics/java/android/graphics/Color.java>
- Android's built-in public color resources and system-only resource lookup:
  <https://android.googlesource.com/platform/frameworks/base/+/master/core/res/res/values/colors.xml>
  <https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/content/res/StringBlock.java>
- Android's exact list-item recognition, UTF-16 paragraph expansion, LF
  boundary adjustment, zero-width bullet handling, and font-height spans:
  <https://android.googlesource.com/platform/frameworks/base/+/c3bc12c484ef/core/java/android/content/res/StringBlock.java>
  <https://developer.android.com/reference/android/text/Spanned.html>
- Official AAPT2 binary distribution, compilation, and binary-container dump:
  <https://developer.android.com/tools/aapt2>
- Android custom annotation spans and attribute-bearing styles:
  <https://developer.android.com/topic/architecture/views/resources/string-resource-views>
- Official qualifier precedence, BCP-47 resource tags, grammatical gender,
  device configurations, and alternative-resource directory rules:
  <https://developer.android.com/topic/architecture/views/resources/providing-resources-views>
- Android framework's real configuration parser, numeric-field truncation,
  deprecated square/stylus modes, physical pixel dimensions, and compatibility
  SDK inference:
  <https://android.googlesource.com/platform/frameworks/base/+/android14-qpr2-s3-release/libs/androidfw/ConfigDescription.cpp>
- AAPT2's canonical configuration fields, including square orientation,
  stylus touchscreens, physical pixel widths/heights, and grammatical gender:
  <https://android.googlesource.com/platform/frameworks/base/+/master/tools/aapt2/Configuration.proto>
- Official build-variant/build-type/flavor/main/library resource merge
  precedence and same-priority duplicate-resource errors:
  <https://developer.android.com/studio/write/add-resources>
- FormatJS apostrophe quoting and attribute-free rich-text syntax:
  <https://formatjs.github.io/docs/core-concepts/icu-syntax/>

## Oracle hierarchy

1. Run original fixtures through the pinned, official AAPT2 binary.
2. Inspect AOSP when an observed result is surprising or differs from the docs.
3. Encode the observed behavior in neutral, implementation-independent JSON.
4. Keep Java and Rust implementations independent while requiring both to pass.

An emulator is unnecessary for resource compilation. AAPT2 is the Android
compiler that determines accepted syntax and the compiled resource text.

The pinned oracle is Google Maven AAPT2 `9.3.1-15703166`, reporting
`Android Asset Packaging Tool (aapt) 2.20-15703166`. Its macOS artifact SHA-256
is `1e35bc2ce18c3aae840be2a29659ce50d6043e907a44d98ee1cf375d044fa29c`.

AAPT2's `dump apc` and `dump resources` output is diagnostic text, not escaped
JSON or valid CSV: literal quotation marks inside compiled values remain plain
`"`, including scalar strings, plural branches, styled text, and comma-bearing
array items. Snapshot decoding therefore finds the native closing delimiter
from its validated span suffix and uses the compiler-declared array entry count
instead of quote-toggling or CSV parsing. The same count-aware decoding retains
multiline styled array entries that a delimiter-based parser can silently drop.
Original neutral fixtures independently verify compiled and linked values,
style offsets, XML-character-reference quotes, escaped resource aliases,
protected `xliff:g` examples, and source-preserving translated templates.

AAPT2's `ParseReference` strips its leading marker and delegates to the Android
framework's `ExtractResourceName`, which independently consumes another leading
`@`. Consequently `@@string/name`, `@@color/name`, and `@@macro/name` are real
resource references; XML character entities, CDATA, and inline comments do not
protect them. Exactly three markers, quoted or escaped markers, unknown types,
`@@null`, and `@@+id/name` remain translatable text. Standalone aliases do not
enter canonical catalogs, while doubled array and plural aliases preserve their
exact source spelling in metadata and compile to normalized native references.
Doubled macro references expand recursively before extraction. Source templates
retain original macro declarations, aliases, entity/comment spelling, and sparse
indexes while changing only compiler-visible translatable values.

The same framework splitter does not validate characters inside a resource
entry. AAPT2 therefore accepts spaces, tabs, line feeds, carriage returns,
CRLF, nonbreaking spaces, and em spaces inside standalone, array, and plural
references; private/theme aliases and doubled markers retain the same behavior.
These unresolved names are compile-only boundaries and are not claimed to link.
Native diagnostic dumps contain literal control characters, so snapshot decoding
must retain multiline reference values, use the compiler's array-entry count,
and distinguish printer wrapping from actual newlines. Both normalized writers
must encode carriage returns as `&#13;`: literal XML carriage returns normalize
to line feeds and otherwise silently corrupt reference metadata. Source
templates preserve original entities, interleaved comments, and opaque aliases
without translating compiler-owned slots.

XML attributes have a stricter preservation boundary than element text:
literal TAB, LF, and CR normalize to spaces, but `&#9;`, `&#10;`, and `&#13;`
retain their exact characters. Android applies this distinction to translator
descriptions on scalar/generic/array/plural resources, protected `xliff:g`
examples, and styled annotation attributes. Independent Java/Rust writers
therefore entity-encode every attribute control character; protected examples,
descriptions, style spans, and CRLF sequences survive repeated canonical
round trips. The AAPT2 linked-resource oracle reads raw bytes and decodes
multiline style-span descriptors without universal-newline conversion, so
compiled and linked snapshots now preserve actual TAB/LF/CR values.

Native AAPT2 processing-instruction targets follow the older BMP XML Name
boundary: ordinary letters, underscores, accented/CJK names, and trailing
combining marks/middle dots are valid, while empty names, leading digits,
hyphens, periods, combining marks/middle dots, join controls, supplementary
emoji, and every colon-bearing target are rejected. Element and attribute
QNames likewise require one or two nonempty valid NCName components; malformed
leading, trailing, or repeated colons and invalid Unicode starts fail closed.
Original AAPT2 snapshots cover valid and rejected forms, and UTF-8/BOM-less
UTF-16 source templates preserve accepted Unicode instruction bytes exactly.

Android Expat and Java Xerces implement XML 1.0 Fourth Edition name classes,
not the broad modern Fifth Edition Unicode intervals. Valid legacy BaseChar and
Ideographic names include Greek, Hebrew, Arabic, Indic, Hangul, and CJK;
CombiningChar and Extender values are valid only after a legal initial
character. Fifth-Edition-only `U+02FF`, `U+0370`, `U+203F`, `U+3001`,
`U+9FA6`, `U+F900`, and `U+FFFD` remain invalid for Android. The empty default
namespace `xmlns=""` is valid and clears inherited namespace ownership. Native
originals and exact UTF-8/UTF-16 templates independently pin these boundaries.

The reserved `xml:` prefix is intrinsically bound to
`http://www.w3.org/XML/1998/namespace`; no explicit `xmlns:xml` declaration is
required. AAPT2 rejects `xml:resources` roots, ignores complete `xml:`-prefixed
top-level resource, macro, attribute, styleable, and control subtrees, and
rejects namespaced array/plural items or attribute/styleable children. Inside a
real resource, `xml:b`, `xml:font`, `xml:annotation`, and `xml:g` are foreign
wrappers rather than native style spans or protected XLIFF placeholders; their
visible text and any genuinely unnamespaced nested styles survive. Ignored
`xml:eat-comment` also cannot consume translator context. Thirty-three original
AAPT2-backed fixtures and four exact UTF-8/UTF-16 source templates pin these
boundaries and keep ignored declarations byte-preserved but untranslatable.

Selected build products must reuse each source file's actual XML byte encoding
when re-evaluating declaration ownership. Original native-linked source sets
cover independently mixed BOM-less UTF-16LE/BE, UTF-16LE/BE BOM, UTF-8 BOM, and
declared ISO-8859-1 files for tablet, default, watch, and combined
default/tablet builds. Multi-file selected-product templates retain every
source-set encoding, original/localized BOM, declaration, byte-level slot, and
untranslated lower-priority/product branch exactly.

Build-product identities use ASCII-only trimming, not Unicode whitespace
normalization. NEL, NBSP, Ogham/em/figure/narrow spaces, Unicode line/paragraph
separators, and ideographic space remain literal product-name characters;
AAPT2 accepts even names composed solely of these characters. Ninety-five
neutral real-linker overlays cover leading, trailing, doubled, internal, and
Unicode-only variants across 19 scalars. Four original/localized multi-file
templates additionally pin exact selected-product source slots through mixed
ISO-8859-1, UTF-8/BOM, and UTF-16/BOM encodings. Java and Rust both reject
empty, ASCII-padded, comma-bearing, and duplicated selected-product lists.

## Covered syntax

| Area | Neutral cases and policy |
| --- | --- |
| Resource structure | `<string>`, generic `<item type="string">`, `<array>`, `<integer-array>`, `<string-array>`, `<plurals>`, untranslated direct/generic `<attr>` dependencies, direct/generic `<declare-styleable>` groups, weak versus strong declarations, ordered/repeated nested entries, untyped/framework references, namespace-qualified local aliases, absent/composite attribute formats, signed integer bounds, native enum/flag symbols, undocumented generic bags for `array`, `string-array`, `integer-array`, and `plurals`, native `<skip>`/`<eat-comment>` controls, latest-comment ownership, ignored namespaced top-level declarations, strict direct bag children, XID Unicode resource identifiers, ASCII-trimmed resource/product names, descriptions, empty values, resource aliases, AAPT2's eight exact generic value formats, heterogeneous typed-array primitives/references/strings, strict generic `format="string"` boundaries, exact boolean-case validation, `formatted="false"`, parent-level `translatable="false"`, and Android's ignored plural/unformatted-generic/item attributes. |
| XML stage | Exactly one unnamespaced `<resources>` root; XML-only envelope whitespace; preserved prologue/epilogue comments and processing instructions; correctly positioned, ordered, and versioned declarations; complete encoding-label scans through the actual declaration terminator despite 480/511/512/513/4096/65536-byte SPACE/TAB/LF/CRLF preambles; native case-insensitive UTF-8, UTF-16, endian-specific UTF-16, ASCII, and ISO-8859-1 byte decoding; compiler-autodetected BOM-less UTF-16LE/BE with direct roots, XML whitespace/comments/instructions, byte-exact supplementary/CRLF source slots, and mixed-endian source-set overlays; actual Latin-1 versus UTF-8-declared-as-Latin-1 source text; rejected nonnative `UTF8`/`UTF_8`/`latin1` aliases, unsupported declarations, contradictory BOMs, opposite-endian UTF-16, native-ignored odd trailing bytes, and unpaired surrogate units; valid standalone fields; exact XML 1.0 scalar ranges across raw text, attributes, comments, CDATA, processing instructions, and XML 1.1 numeric references; rejected NUL/C0 controls and U+FFFE/U+FFFF; rejected trailing text, entities, CDATA, extra roots, malformed declarations, reserved/empty namespace bindings, and namespace-expanded duplicate attributes even when AAPT2 ignores them; XML character references, predefined entities, CDATA, nested inline elements, literal-versus-entity TAB/LF/CR attribute normalization, translator-description/XLIFF-example/style-attribute control preservation, stable attribute ordering, scoped namespace aliases, malformed documents, unbound prefixes, unknown entities, safe rejection of real document types, and safe literal document-type-looking text inside CDATA/comments. |
| Source-preserving templates | Exact source-byte slots for original styled/protected strings, self-closing scalar/generic string/array/plural declarations, native default/tablet/watch product identities, ASCII-padded resource/product names, ordered read-only/read-write flag context, positive/inverse/product-gated mutable alternatives, fixed disabled declarations, native fixed-false item compaction, runtime positive/inverse array-item slots, protected disabled/empty declarations, stable compacted reference/primitive indexes, compiler-ignored root/plural flags, SDK-10000 generic/bag/product arrays, namespace-aliased feature flags, cross-product conditional collisions, ignored foreign-namespace lookalikes and nontranslatable tags, compiler-ignored inline comments, original doubled aliases and recursively referenced macro declarations, whole/mixed CDATA literal text, safe translated `]]>` splitting, escaped markup versus real spans, literal quote/XML-entity/comment boundaries across strings, arrays, plurals, span attributes, and protected placeholders, original attributes/namespaces, supplementary byte offsets, original UTF-8/UTF-16/BOM encodings, and CRLF; real AAPT2 compiles every source and links original/localized product-selected and runtime-flagged APKs. |
| Android stage | Escaped `@`, `?`, `#`, backslash, apostrophe, quote, tab, newline, zero-to-four-digit segment-final Unicode, discarded UTF-16 surrogate escapes, trailing backslashes, and unknown named escapes; Android escaping happens after XML entity decoding. |
| Quoting | Entire quoted values, quoted interior regions, entity-encoded quotes, literal escaped quotes across scalar/array/plural/styled/protected values, quote-bearing span attributes and protected examples, comma-bearing quoted array entries, preserved internal newlines/spaces, and errors for apostrophes outside quoted or escaped text. |
| Whitespace | ASCII whitespace collapse, quoted whitespace preservation, Unicode whitespace introduced through XML entities, Unicode whitespace introduced through Android escapes, opaque resource-entry spaces/tabs/LF/CR/CRLF/NBSP/em spaces, count-aware multiline native reference snapshots, and carriage-return-safe normalized XML writing. |
| Styling | Nested supported HTML-like tags, deterministic attributes, encoded HTML that stays text, annotation spans, CDATA text, self-closing markup, attribute values containing apostrophes or angle brackets, semicolon-injected/repeated/truncated/malformed native runtime annotations, injected font height/size/foreground/background/typeface/link effects, fixed effect order and first-match precedence, truncated link destinations, all 23 case-insensitive Android color names/aliases, opaque/alpha/signed/Unicode hexadecimal, invalid/foreign-package opaque-black fallback, stateful foreground versus default background system resources, Arabic-Indic/fullwidth BMP decimal sizes, rejected supplementary/invalid/overflowing runtime-crashing font sizes, preserved equals/comma values, sorted native annotation attributes, styled array/plural entries, and strict FormatJS-safe ICU angle quoting. |
| Protected sections | XLIFF namespace identity independent of prefix; locally shadowed and restored prefixes; foreign `g` tags versus unnamespaced styling tags; anonymous/empty-ID literal and formatter sections; deterministic `_xliffN` names; numeric, accented, CJK, emoji, and combining-mark IDs; repeated protected formatters; exact TAB/LF/CR/CRLF examples across scalar/array/plural branches; rejected nested sections, FormatJS-unsafe IDs, ambiguous same-name values/argument positions, and protected style spans without a reversible skeleton. |
| Formatting | Positional string/integer/decimal arguments, repeated arguments, calendar components, numeric flags/precision, literal percent, line-separator conversion, rejection of multiple implicit substitutions on plain strings and explicit `format="string"` items, styled/array/plural/default-generic exemptions, relative reuse, and true `formatted="false"` boundaries. |
| Plurals | `zero`, `one`, `two`, `few`, `many`, `other`; ASCII-trimmed quantity attributes, positional count placeholders, resource-reference quantities, duplicate/invalid categories, and canonical ICU fallback requirements. |
| Compiler resources | Generic `<item type="string">` and `<array>` TYPE_ANY values, native boolean/integer/hex/color/dimension/float/fraction primitives, explicit `reference`/`string` value formats, quoted/escaped primitive lookalikes, mixed typed-array string/reference/primitive positions, typed integer-only/reference-only arrays, `tools:locale` independent of its prefix, ignored namespaced wrappers with retained visible text, `@null`, `@empty`, package-qualified/private/typed aliases, doubled-leading aliases through XML entities/CDATA/comments, space/tab/LF/CR/CRLF/NBSP/em-space reference entries, `@+id` creation, qualified/private/shorthand theme attributes, literal tripled/numeric/unknown/invalid/escaped/quoted/Unicode-padded lookalikes, sparse mixed-reference arrays, empty array entries, and default/tablet/watch product variants across strings, plurals, and arrays. |
| Build-time macros | `<macro>` and `<item type="macro">`, plain/doubled/private/application-package/private-package references, local `res-auto` aliases, public/private package namespace aliases, definition-scoped namespace aliases across recursive/cross-file expansion, ordinary/private/theme resource aliases normalized to native local identities, target-prefix shadowing, protected-XLIFF identity, foreign-wrapper visible text, explicit package ownership and fail-closed missing/foreign definitions, preserved escaped/invalid-reference literals, protected/style/array/plural/generic expansion, normalized duplicate `(name, product)` validation, and native SIGABRT/SIGSEGV verification for product-variant, cyclic, path-gated, and runtime-conditional linker crashes. |
| Directory configurations | Default `values`, case-insensitive legacy/BCP-47 language/script/region/variant tags, left/right pseudolocales, exact lowercase `donottranslate` basename-prefix suppression, genuine `--pseudo-localize` left/right generation boundaries, grammatical gender, mobile country/network codes, square orientation, legacy stylus, physical pixel dimensions, 16-/32-bit numeric boundaries, leading zeros, density aliases, minimum SDK inference, deterministic qualifier order, duplicate/unknown qualifiers, and safe resource paths. |
| Resource overlays | Explicit library/main/flavor/build-type/build-variant priority, unordered input normalization, same-priority conflict rejection, ASCII-normalized resource/product identities, ignored namespaced declarations, globally winning cross-file direct/generic/weak attribute declarations and ordered styleable groups, atomic direct/generic-bag typed-array/string-array/integer-array/plural replacement, winning primitive/reference slots, integer-only array tombstones, higher-priority nontranslatable/file-prefix tombstones, native-ignored mixed-case generic/plural flags, separate default/product identities, winning-source provenance, equivalent locale/density/dimension/network aliases, dropped zero-value fields, and explicit versus compiler-implied SDK configurations. |
| Span state | Quote resets at span start/end, separate whitespace-collapse boundaries, empty spans, nested style spans, ignored foreign namespaces, original unescaped native tag encoding, ordered duplicate/injected runtime annotations, ordered fixed-precedence injected font/link effects, native `NumberFormatException` boundaries, exact-versus-attributed `li` recognition, whole-paragraph zero-width bullets, direct/injected height expansion, inclusive terminating LF and non-LF Unicode boundaries, UTF-16 supplementary offsets, and category-local plural span ownership. |
| Normalized writing | Sorted resources, deduplicated strong `<attr>`/`<bag type="attr">` declarations, ordered direct/generic `<declare-styleable>` groups with genuinely weak nested declarations, repeated/framework attribute references, normalized format masks, signed integer bounds, sorted enum/flag symbols, original generic `<item type="string">` identity and strict `format="string"` behavior, original `<array>` versus `<string-array>` identity, original generic bag declarations for `array`, `string-array`, and `plurals`, typed-array format and native primitive/reference slots, grouped array/product entries, preserved plural references, fixed plural order, independently quoted text spans, reversible attributed markup, literal HTML versus actual style spans, protected XLIFF examples, XML-safe attribute/element TAB/LF/CR control escapes, original printf spellings, `%n` versus Android newlines, source locale/configuration, and idempotent Java/Rust output. |

Independent compiled-resource snapshots additionally check AAPT2's actual
plain strings, styled visible text, plural values, array entries, resource
references, typed native primitive values, line separators, and protected-section
source spellings. Dedicated
span snapshots also preserve AAPT2's actual tag names, arbitrary attributes,
inclusive UTF-16 start/end offsets, supplementary-plane emoji before and inside
spans, nested spans, array-item spans, and plural-item spans. Original
semicolon-delimiter fixtures additionally preserve the exact encoded native tag
and Android's ordered runtime annotation list, including duplicated keys,
injected keys, dropped suffixes, and malformed doubled-delimiter keys.
Font/link snapshots also retain ordered effective size, height, foreground,
background, typeface, and hyperlink effects; exact normalized ARGB color values;
invalid-to-black versus system-reference provenance; and runtime numeric
failures that the compiler itself accepts. Paragraph snapshots retain original
native inclusive ranges plus runtime-expanded UTF-16 half-open bullet/height
boundaries, including empty spans and supplementary Unicode.
Normalized XML escapes literal attribute `<` as `&lt;` while reproducing the
identical compiled span. The oracle does not merely compare acceptance or
rejection.

The fixture manifest can also supply the original Android `resourcePath`.
Both parsers expose an optional path-aware API, validate `res/values-*`
directory structure and qualifier precedence, derive the effective locale, and
retain the original path plus ordered qualifiers on every extracted string,
array item, and plural message. A path locale overrides a conflicting
`tools:locale`; resources without a path locale retain the tools hint. Native
snapshots separately capture AAPT2's normalized configuration spelling,
including `mnc004` becoming `mnc4`, the sentinel `mnc000` becoming `mnc65535`,
BCP-47 `b+es+419` becoming `es-r419`, and the surprising acceptance of
`values-rUS` as the three-letter language `rus`. Android's real parser also
accepts deprecated `square` orientations and `stylus` touchscreens even though
those values no longer appear in the current public qualifier table. Physical
pixel dimensions occur after navigation qualifiers and before the SDK version;
both axes are silently narrowed to 16 bits, and the resulting width must not be
less than the resulting height. A wrapped `65536x65536` therefore becomes the
default physical configuration, while `65536x1080` is invalid.

Density fields reject zero before narrowing their original value to 16 bits:
`65534dpi` becomes `anydpi`, `65535dpi` becomes `nodpi`, and `65536dpi` drops
the density entirely, while a 32-bit-wrapped `4294967296dpi` is rejected.
Numeric widths similarly wrap, zero-valued `sw`/`w`/`h` qualifiers disappear,
and SDK versions reject values above `65535`. `mcc000` is invalid but every
zero-spelled MNC maps to native sentinel `65535`. AAPT2 automatically raises
SDK qualifiers to the feature's actual floor: ordinary density/size imply API
4, night or UI mode imply API 8, nonzero density-independent widths imply API
13, `anydpi` implies API 21, round displays imply API 23, wide color/HDR/VR
imply API 26, and grammatical gender implies API 34. An exhaustive independent
fixture exercises every currently modeled qualifier category in Android's
actual precedence order.

FormatJS rich-text tags cannot contain XML/HTML attributes. Android tags with
attributes therefore quote only their opening-angle character as `'<'`; the
matching closing tag uses the same quoting. This keeps tag names, attributes,
nested ordinary rich tags, and visible-text placeholder substitutions intact
while treating attribute values as opaque compiler-owned span data and
producing valid ICU messages. Natural ASCII apostrophes are doubled only for
messages using quoted markup, making the result compatible with both FormatJS
and ICU4J's `DOUBLE_REQUIRED` apostrophe mode. The
canonical metadata marks the reversible policy as
`androidMarkupEscaping = "icu-quoted-angle"`, and actual ICU4J/FormatJS runtimes
verify both syntax and rendered-markup parity.

Normalized Android writer fixtures compile both the original and regenerated
resources through actual AAPT2. Separate quoted XML text segments preserve exact
whitespace on either side of style tags. `androidLiteralMarkup = true` prevents
CDATA/entity-provided HTML-looking text from unexpectedly becoming an Android
span, while `androidPrintfLineSeparator = true` preserves the compiler-visible
source spelling `%n`. Mixed native `\\n` and printf `%n` spellings additionally
use `androidPrintfLineSeparators` or per-category
`androidPluralPrintfLineSeparators` to preserve each zero-based newline
occurrence independently; escaped `%%n` remains literal text. Styled snapshots
prove that nested tags, arbitrary attributes, supplementary Unicode, actual
multiline arrays/plurals, and inclusive UTF-16 offsets survive regeneration.
The one intentionally different normalized snapshot omits a
`translatable="false"` resource that the canonical extraction never retained;
source-preserving templates retain excluded resources and every untouched
declaration in the independently owned original file.

Multi-file overlay fixtures run through AAPT2's actual `compile`, `link -R`,
`--auto-add-overlay`, optional `--product`, and `dump resources` commands.
Linked snapshots confirm that array and plural overlays replace entire native
resources rather than merging individual items or quantities, while typed
reference slots/quantities survive the winning source-set replacement.
Same-priority
sources are linked without `-R` to prove conflicting resource declarations
really fail. Higher-priority `translatable="false"` declarations remain present
in native Android output but correctly suppress their lower-priority
translatable canonical messages. `--no-resource-removal` keeps locale-only
resource configurations visible in the native linker snapshot.

## Explicit differences and research queue

- The current AAPT2 binary accepts a plural resource without `other` during the
  compile stage. Mojito rejects it because the canonical ICU message requires an
  `other` fallback.
- AAPT2 accepts the existing XML document-type fixture, but Mojito rejects all
  document types to prevent external-entity attacks. The oracle runner never
  executes that security fixture.
- Published Android guidance says Unicode whitespace is collapsed after XML
  parsing. The pinned AAPT2 binary preserves U+2003/U+2008 when introduced by XML
  numeric character references; the fixture follows the measured compiler.
- Contrary to documentation describing exactly four hexadecimal Unicode digits,
  AAPT2 accepts zero through four digits when a text segment ends, including a
  style-element boundary. `\u` becomes NUL, `\u41` becomes `A`, and a fifth
  hexadecimal digit remains ordinary text; a nonhexadecimal character before
  the segment ends still rejects the resource.
- Escaped high surrogates, low surrogates, and complete UTF-16 surrogate pairs
  are all silently discarded rather than decoded into supplementary scalars.
  Style offsets reflect their absence. Literal supplementary UTF-8 remains
  intact; normalized writers never regenerate escaped surrogate halves.
- A trailing backslash disappears, even at an inline-style boundary. Only `\n`
  and `\t` are named controls: `\r` means literal `r`, not carriage return.
  Real NUL, carriage return, and other XML-forbidden C0 controls regenerate as
  valid Android `\uXXXX` escapes rather than invalid literal XML characters.
- `<array>`, `<string-array>`, and `<plurals>` entries can be resource/theme
  references;
  native compiler snapshots distinguish references from quoted, identical-
  looking literal text. Canonical array IDs retain their original sparse indexes
  and carry `androidArrayReferences`; plural references carry
  `androidPluralReferences`. An `other` reference has no canonical ICU source
  fallback and therefore fails with a stable, explicit diagnostic.
- Native reference parsing uses AAPT2's resource-type allowlist, not a small
  string/plural prefix list. Package-qualified aliases, private `@*` references,
  any real resource type, valid `@+id/...` creation, dotted/dashed entry names,
  `?attr/name`, shorthand `?name`, and qualified/private theme attributes are
  references. Quoted/escaped lookalikes, numeric strings, unknown resource
  types, creation of non-ID/private IDs, invalid theme types, and Unicode-padded
  values remain translatable source. Compiled AAPT2 normalizes `@+id/name` to
  `@id/name`, shorthand theme attributes to `?attr/name`, and private theme
  syntax to its effective public spelling; canonical reference metadata and
  regenerated XML deliberately retain the original source spellings. An
  original neutral fixture contains 15 standalone aliases, nine array slots,
  and five plural branches; its normalized native snapshot explicitly omits
  the untranslatable standalone aliases.
- AOSP's `ParseDeclareStyleable` treats nested attributes as weak declarations:
  a child without format or enum/flag symbols creates only an ordered reference,
  whereas a typed child creates a weak local `<attr>`. Matching weak
  declarations merge, compatible direct declarations become strong, and
  conflicting weak/strong declarations reject. Direct and generic styleable
  groups preserve repeated attribute positions, inferred symbols, signed
  bounds, `res-auto` aliases, and qualified framework references. Their actual
  compiled APC entries are `PUBLIC` and define generated `R.styleable` arrays,
  but disappear from linked APK tables. Native source/normalized snapshots
  verify exact group order separately from linked typed-attribute behavior;
  cross-file overlays verify weak declarations and higher-priority groups.
- Attribute bounds and inline enum/flag symbol values use framework
  `ResTable::stringToInt`: leading zeroes remain decimal (`010` is ten and
  `08` is valid), negative decimal values reach `-2147483648`, positive
  decimal values stop at `2147483647`, and only lowercase `0x` permits the
  complete unsigned 32-bit range. Java `Long.decode` and implicit Rust/C-style
  octal would disagree. Leading `+`, negative/uppercase-prefixed hexadecimal,
  decimal unsigned overflow, 33-bit hexadecimal, and Unicode whitespace reject.
  Empty enum/flag formats, mixed integer masks, repeated symbolic values, and
  unsigned-high-bit signed canonical projection all survive normalized writers.
  Private `*android:`/`*local:` styleable references normalize without their
  private marker. A real `values-fr-night` fixture captures AAPT2's warning and
  proves attributes/styleables are forced to default configuration while the
  translated array remains `fr-night-v8`.
- Generic `<item type="string">` defaults to AAPT2's `TYPE_ANY`, despite its
  string resource name. Unquoted booleans, decimal/hex integers, colors,
  dimensions, floats, and fractions therefore compile as native primitives and
  must not enter translation. Quoted numbers and escaped colors remain real
  translatable strings. Explicit generic `format` accepts exactly `reference`,
  `string`, `integer`, `boolean`, `color`, `float`, `dimension`, and `fraction`;
  unknown names, uppercase names, and pipe unions fail. Empty `format` behaves
  like no attribute, while ASCII-padded `" string "` activates the native
  string parser: unlike default generic items, it validates `formatted` and
  `translatable`, honors false values, and rejects multiple implicit printf
  substitutions. Canonical metadata records `androidGenericFormat="string"`
  so both normalized writers retain strict native behavior. An original AAPT2
  fixture proves 12 compiled primitive values, two aliases, one nontranslatable
  strict string, and nine extracted messages; the actual legacy filter keeps
  only the ordinary string and misses all eight generic translations.
- Android's separate `<array>` declaration also defaults to `TYPE_ANY`, and a
  single resource can mix visible strings with booleans, decimal/hex integers,
  colors, dimensions, floats, fractions, and resource/theme references. Both
  parsers preserve every nontext physical position in
  `androidArrayPrimitives`/`androidArrayReferences`, keep
  `androidGenericArray=true`, and retain explicit `androidArrayFormat="string"`
  when a typed array narrows all nonreference entries to strings. Array-level
  `formatted` and item-level `formatted`/`translatable` remain ignored even
  under `format="string"`; parent-level `translatable` is validated and
  honored. `integer-array`, integer-only generic arrays, and reference-only
  arrays have no canonical messages, but they still atomically replace lower
  `<string-array>` values during source-set overlays. Original snapshots cover
  six native arrays, nine primitive positions, seven reference positions, and
  two normalized translatable arrays. Real Okapi misses all eight typed-array
  messages, and native-linked overlays independently prove mixed-array
  replacement and integer-only tombstones.
- AAPT2 also accepts an undocumented generic `<bag>` declaration: its
  ASCII-trimmed `type` dispatches `array`, `string-array`, `integer-array`, or
  `plurals` through exactly the corresponding native direct-resource parser.
  Heterogeneous primitives/references, strict `format="string"`, descriptions,
  controls, product normalization, array translatability, and plural
  author-intent suppression therefore follow the effective type, not the
  literal tag. Canonical `androidBagType` preserves generic spelling through
  both normalized writers; invalid metadata fails with `INVALID_ANDROID_BAG`.
  Missing/blank/unknown/scalar bag types, unknown top-level declarations,
  missing/unknown generic-item types, and namespaced bag children fail with
  stable structural diagnostics; missing bag names fail as resource names.
  The original native snapshot contains five arrays, four primitive positions,
  two references, and one plural group. Two native-linked overlays prove
  generic bags atomically replace direct arrays/plurals and integer-only bags
  tombstone lower string arrays. Actual configured Okapi retains only the
  ordinary control string: 14 of 15 projected canonical units are missing.
- Modern AAPT2 checks whether the values filename starts with the exact,
  lowercase bytes `donottranslate`; a suffix, `Donottranslate`, and
  `donotTranslate` remain fully localizable. The prefix sets the file's default
  string/array translatability to false and disables `--pseudo-localize` for
  the entire file, including explicit `translatable="true"`, generic strings,
  plural groups, and generic bags. Both portable parsers therefore suppress
  the complete file while retaining its native declarations as source-set
  tombstones. Ordinary non-positional printf strings become valid under the
  false default, whereas explicit true reenables native format checking and
  malformed booleans still reject. Five actual AAPT2 pseudolocale snapshots
  prove prefix versus case/suffix boundaries; two native-linked overlays prove
  suppressed files replace lower visible strings/arrays/plurals rather than
  resurrecting stale translations. Actual Okapi leaks 18 forbidden text units.
- Explicit `translatable="false"` and the `donottranslate*` filename prefix
  suppress translation but do not bypass AAPT2's native content validator.
  Unescaped apostrophes and malformed Unicode escapes fail inside direct and
  generic strings, arrays, plural bags, and private filenames; ordinary style
  boundaries reset quote state and nested native `xliff:g` tags remain illegal.
  Punctuation-bearing protected IDs, styled content inside protected sections,
  and non-positional `%s`/`%d` combinations remain valid because protected
  values must not inherit stricter translator-only FormatJS policy. Both
  portable parsers preserve that distinction while UTF-8/UTF-16 source
  skeletons leave all protected runtime strings, array positions, plural
  categories, and spans untouched. An actual legacy differential exposes ten
  forbidden protected-array/plural Okapi units beside one visible translation.
- AAPT2 represents foreign inline namespaces and unknown XLIFF tags as
  transparent nodes rather than styled spans. Quoted apostrophes therefore
  remain valid across foreign wrappers, unknown `xliff:*` elements, sibling
  wrappers, native protected sections, and nested foreign/protected wrappers.
  An actual unnamespaced `<b>` inside any of those wrappers still resets quote
  state, and nested `xliff:g` remains illegal even through a transparent
  wrapper. Real compiler warnings distinguish foreign namespaces from silently
  ignored unknown XLIFF tags; native string/array/plural/style snapshots,
  `donottranslate*` filename controls, UTF-8/UTF-16 source preservation, and an
  actual forbidden-translation Okapi shadow independently verify the boundary.
- AAPT2 ignores `xml:space` on resource roots and bodies, including values that
  real Okapi's ITS parser rejects. On genuine unnamespaced style spans, all
  bound XML, Android, and foreign attribute prefixes disappear; native
  attributes are ordered by `(namespace URI, local name)` before font,
  foreground-color, and annotation runtime effects are applied. Original,
  normalized, and localized scalar/generic/array/plural compiler snapshots
  prove those effects, while UTF-8/UTF-16 templates retain original prefixes,
  scoped namespace declarations, and attribute entity spellings. Different
  namespaces with the same local attribute name compile natively but fail
  closed until a lossless duplicate-attribute contract exists.
- The root `<resources>` element must have no namespace, and nonwhitespace
  root-level text is invalid. Namespaced top-level declarations are ignored
  together with their complete descendant trees; inside arrays and plurals,
  only unnamespaced `<item>`, `<skip>`, and `<eat-comment>` are accepted.
  Controls never create array slots, and root controls clear pending translator
  context. Consecutive comments replace rather than accumulate; a comment
  survives an ignored foreign top-level element. Resource/product names trim
  ASCII whitespace only, and real resource IDs use AAPT2's generated BMP-only
  `XID_Start`/`XID_Continue` tables plus `_`, `.`, and `-`. Accented, combining,
  middle-dot, CJK, uppercase, and underscored names are valid; supplementary
  XID letters/CJK, supplementary continuations, join controls, emoji,
  digit-leading names, slashes, missing names, and Unicode-space padding are
  rejected. Modern ICU/Rust XID data alone incorrectly accepts several of those
  native-rejected identifiers. Trimmed names/products collide with
  their unpadded equivalents in both single files and native-linked overlays.
  Dedicated source templates normalize those spellings only for ownership,
  preserve explicit `product="default"` without inventing a product suffix,
  reject namespace-lookalike slot collisions, and run original/localized
  AAPT2 linking independently for default, tablet, and watch selections.
  Runtime-aware templates carry the original ordered read-only/read-write flag
  declarations through both extraction and reinjection, own positive/inverse
  and product-qualified alternatives independently, and retain disabled fixed
  values untouched. Native-linked snapshots show that two product alternatives
  with the same mutable condition collapse to the last conditional value even
  when another product supplies the ordinary fallback.
  A real configured Okapi extraction produces 15 legacy units versus 13
  canonical units and 25 distinct differences: three stale-comment mismatches,
  ten missing normalized messages, and twelve unexpected leaked/control or
  unnormalized legacy units.
- AAPT2 ignores genuine XML processing instructions between text segments,
  inside real style spans, protected `xliff:g` placeholders, generic strings,
  array entries, plural branches, and the root resource container. Comments,
  instructions, and CDATA do not interrupt Android quote state or a split
  `\\u0041` escape; style spans retain their exact native UTF-16 offsets and
  attributes. Independent UTF-8/UTF-16 source templates preserve all root and
  inline instructions verbatim, keep their ownership relative to comments,
  CDATA, formatter tokens, and tags, and compile to real original/translated
  AAPT2 snapshots. Reserved embedded `<?xml ...?>` targets and unfinished
  instructions are rejected by both portable XML readers and the native
  compiler; Rust explicitly rejects XML declarations after the document start.
- `translatable="false"`, `"False"`, and `"FALSE"` suppress an entire declared
  string or array. Portable extraction also honors those exact false spellings
  on plural groups as an explicit author-intent policy, even though AAPT2 itself
  ignores the plural attribute and still emits the native plural resource.
  Arbitrary mixed-case `"fAlSe"` is not a valid Android boolean: AAPT2 rejects
  it on strings/arrays and explicit `format="string"` items but ignores it
  entirely on plural groups, default generic `<item type="string">`
  declarations, and array items. Both parsers retain
  those ignored plural/generic/item values and the overlay linker proves they
  replace lower-priority resources rather than becoming tombstones.
- AAPT2 trims ASCII spaces, tabs, and line breaks around plural `quantity`
  values. Mojito's current Okapi Android filter throws `Invalid plural form` on
  the same valid resources; the shared differential manifest records the exact
  legacy exception without requiring a production cutover.
- Unicode EM SPACE and NO-BREAK SPACE are not trimmed from plural quantities;
  AAPT2 rejects both. The same Unicode padding around reference-looking string
  content remains visible literal text rather than turning it into a resource
  alias, so generic Java `strip()` and Rust `trim()` would both be wrong.
- AAPT2 validates multiple implicit printf arguments only on plain `<string>`
  values and generic `<item type="string" format="string">` declarations.
  Styled strings, default generic items, string-array items, and plural branches
  accept the same formats, including `%<s` relative reuse. Normalized writers
  retain generic-item identity and explicit string-format metadata so reparsing
  neither makes a valid default generic item invalid nor drops strict generic
  formatting semantics.
- AAPT2 does not validate those resources by matching a conventional printf
  regular expression. Its actual scanner counts every nonterminal percent
  fragment except `%%` and `%n`, treats bare widths, relative `%<s` / `%<$s`,
  Python-style names, whitespace, and even unknown `%_` fragments as
  nonpositional arguments, then rejects them when another substitution exists.
  Conversely, unmistakable legacy `Time.format()` directives `D`, `F`, `K`, `M`,
  `W`, `Z`, `k`, `m`, `w`, `y`, or `z` immediately bypass that entire check;
  valid Java `%tY`/`%tm` still reject when both arguments are unpositioned. Java
  and Rust independently reproduce the byte/character scanner instead of
  reusing their canonical placeholder-normalization regex. Native original and
  normalized snapshots preserve positional Java dates, time shortcuts, strict
  generic strings, escaped percents, `%n`, and the `formatted="false"` opt-out.
- The same lexical rule makes a lone trailing `%` and a solitary malformed `%_`
  valid compiled strings. Their native spelling differs from escaped `%%`, even
  though both normalize to the same canonical FormatJS percent character. Typed
  `androidRawPercentOccurrences` preserves raw-versus-escaped occurrence order
  across ordinary/strict-generic strings, default generic resources, array
  messages, supplementary Unicode, visible text around actual HTML/annotation
  attributes, and spans; `androidPluralRawPercentOccurrences` independently
  preserves each
  plural category. Both writers reconstruct the original native spelling and
  exact compiled annotation spans while rejecting empty, negative, duplicate,
  unsorted, out-of-range, unformatted, wrong-resource-shape, or unknown-branch
  percent metadata with `INVALID_ANDROID_PERCENT`.
- Native escaped `\\n` compiles to an actual newline while formatter `%n`
  remains a two-character native sequence until runtime; canonical FormatJS
  text collapses both spellings into the same newline. The backward-compatible
  `androidPrintfLineSeparator` flag represents all-printf resources, while
  typed `androidPrintfLineSeparators` and per-category
  `androidPluralPrintfLineSeparators` retain exact mixed occurrence ownership.
  Original neutral resources cover ordinary/default/strict generic strings,
  arrays, independent plural categories, supplementary Unicode, styled
  annotations, positional arguments, adjacent raw/escaped percent signs, the
  `formatted="false"` boundary, and escaped literal `%%n`. The AAPT2 snapshot
  parser preserves genuine multiline bag values and style-span offsets; both
  writers fail closed with `INVALID_ANDROID_LINE_SEPARATOR` for missing flags,
  empty/negative/duplicate/unsorted/out-of-range indexes, noninteger values,
  wrong resource shapes, unknown plural categories, or unformatted ownership.
- AAPT2 stores annotation/font attributes in native span names without applying
  Java Formatter semantics. Attribute `%n`, `%%n`, `%1$s`, `%_`, and lone
  percent signs must remain literal and never invent placeholders or consume
  visible-text percent/newline occurrence indexes. Independent Java/Rust
  parsers shield attribute contents during canonical normalization; writers
  preserve the original attribute spelling and re-encode actual LF, CR, and TAB
  values as `&#10;`, `&#13;`, and `&#9;`. Original neutral fixtures verify
  nested annotations, supplementary Unicode, arrays, independent plural
  categories, strict generic strings, unformatted resources, adjacent visible
  formatting, exact UTF-16 style offsets, and attribute-only tokens. The native
  APC oracle captures raw bytes and multiline span descriptors so real CR can
  no longer be silently converted to LF by Python universal-newline decoding.
- AAPT2's `ParseBool` accepts precisely six ASCII-trimmed spellings: `true`,
  `True`, `TRUE`, `false`, `False`, and `FALSE`. Other mixed-case permutations,
  numeric `0`/`1`, and arbitrary text fail. `formatted` is validated only on
  `<string>` or explicit `format="string"`, and `translatable` only on those
  string declarations plus `<string-array>`; invalid same-named attributes on
  default generic items, plural groups, and array items are ignored rather than
  rejected. Normalized native snapshots explicitly account for source
  nontranslatable resources, references, and native primitive values that
  canonical extraction omits.
- Actual legacy extraction misses generic `<item type="string">` resources and
  exposes uppercase/title-case nontranslatable strings, arrays, and all six
  synthesized plural branches. The exact-case boundary fixture projects 20
  canonical units versus 28 real Okapi units; its shared shadow snapshot records
  one missing generic declaration and nine leaked nontranslatable legacy units
  without exposing translation contents in production metric tags.
- Every namespace-true `xliff:g` starts a protected section, even when `id` is
  missing or empty; the portable catalog synthesizes deterministic `_xliffN`
  names in those cases. Numeric, accented, CJK, emoji, and combining-mark IDs
  remain valid across actual FormatJS and ICU4J runtimes. Nested protected
  sections fail regardless of either ID, while repeated references to the same
  protected formatter remain valid. AAPT2 permits whitespace/punctuation IDs,
  ambiguous same-name literal/formatter bindings, and style spans within
  protected sections; portable extraction rejects those explicitly because they
  cannot be represented safely or reversibly without a future inline skeleton.
  Real Okapi extraction retains the XLIFF XML wrappers for all 21 neutral
  protected-section units, producing 21 bounded `source_mismatch` differences
  against their FormatJS-compatible canonical descriptors.
- Android documentation disallows a standalone region qualifier, but AAPT2
  accepts `values-rUS` by interpreting it as the three-letter language `rus`.
  Both implementations follow the actual compiler.
- Public qualifier tables omit deprecated `square` and `stylus`, but AOSP and
  the pinned AAPT2 compiler still accept both.
- Source-preserving Android templates retain the exact original relative
  resource path because XML bytes cannot express directory locale/night
  qualifiers or inherited mutable `flag(...)` conditions. Neutral
  `values-fr-night` fixtures prove directory locale overrides conflicting
  `tools:locale`, the compiler infers `fr-night-v8`, and original/translated
  default/tablet links preserve conditional strings, arrays, plurals, protected
  nontranslatable values, UTF-16 slot offsets, and the precise gated
  `.arsc.flat` filename.
- Direct array-item flags compact positions only when a fixed read-only
  condition disables the item; mutable positive and inverse conditions retain
  their own native slots and are supported only from configuration SDK 10000.
  Root/plural-item conditions are ignored, while hidden string/reference/
  primitive/self-closing entries remain byte-identical source text. Original
  UTF-8/UTF-16/CRLF default/tablet fixtures independently verify Java/Rust slot
  parity and actual original/translated linked array positions.
- Effective overlay identity uses native values, not directory spelling:
  case-insensitive names, leading-zero numeric forms, density buckets, wrapped
  zero widths/densities, MNC-zero aliases, normalized physical dimensions, and
  explicit versus compiler-implied SDK versions must merge identically.
- Distinct effective SDK versions and distinct density buckets remain separate
  configurations even if other resource qualifiers match.
- A single overlay catalog intentionally rejects distinct effective resource
  configurations; separate configurations must be merged independently.
- Differential legacy Okapi extraction confirms that ignored plural/array
  resources can leak through the existing filter and product variants can
  collide under the same legacy ID.
- Future slices should measure unresolved reference-only resources, formatter
  conversion compatibility,
  source-preserving skeleton regeneration, and explicit production
  route-cutover/rollback controls.
