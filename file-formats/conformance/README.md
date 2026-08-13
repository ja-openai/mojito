# File-format conformance fixtures

`manifest.json` is the language-neutral executable contract. Each case names a
real source resource under `fixtures/` and either an expected canonical catalog
or a stable error code. Java and Rust runners read this same manifest; no
language-specific expected output is allowed.

`dev-docs/design/025-portable-localization-compatibility-ledger.md` is the
generated evidence-backed inventory of every observed Okapi failure, deliberate
platform-correct incompatibility, safety boundary, and unfinished replacement
capability. Refresh it with
`python3 file-formats/conformance/compatibility_ledger.py --write` after
changing manifest comparisons or shadow snapshots; the shared verifier rejects
stale generated evidence.

`catalog.schema.json` is the version-one wire contract, including the typed,
optional per-message `androidAttributeDependencies` and ordered
`androidStyleableDependencies` source-dependency skeletons.
Its extension remains inside ordinary FormatJS-compatible descriptor metadata;
existing catalogs retain their original version and shape. Run
`python3 file-formats/conformance/verify.py` to check fixture IDs, source files,
expected descriptor fields, stable diagnostics, plural fallback, and format
coverage without installing any language-specific tooling. The manifest also
contains language-neutral multi-file Android overlay contracts, explicit legacy
Okapi comparison policies, and cross-language migration-shadow report snapshots;
none changes the canonical catalog schema.

`android-overlay-source-skeleton.schema.json` owns a separate version-one
multi-file Android source template. Shared `androidOverlaySourceSkeletons`
fixtures preserve every original Gradle source-set file and assign editable
scalar, array, plural, default-product, and device-product slots only to the
actual winning native declaration. Overridden lower-priority bodies and values
masked by nontranslatable upper declarations remain byte-identical. Google's
real AAPT2 links both original and translated source sets for every declared
product. Optional `androidSelectedProducts` and complete
`androidRuntimeSlotOwners` map unqualified selected-runtime string, array,
and plural identities back to their exact product-qualified source slots,
including explicit defaults, absent-product fallback, and `default,tablet`
precedence. Unknown/shadowed slots, missing/duplicated ownership, and invalid
product lists fail closed in both implementations.
Optional `androidMacroOwners` and `androidApplicationPackage` additionally
retain winning build-macro definition provenance and local namespace context.
Definitions never become editable slots and remain byte-identical, while
expanded cross-file aliases, higher-priority overrides, transitive
definition-site namespaces, styles, protected placeholders, arrays, plurals,
and selected products receive native-verified translated values. Forged macro
ownership or changed expanded inline structure is rejected.

`source-skeleton.schema.json` is a distinct version-one source-preserving
sidecar; it never changes the semantic FormatJS catalog. Shared
`sourceSkeletons` fixtures retain exact original Android XML, Apple
Foundation/OpenStep and XML property-list strings, XML Foundation plural
stringsdicts, Xcode String Catalog JSON, GNU gettext PO, and Java properties,
byte encoding/BOM, ordered source-byte
ownership, comments, escaped keys, quote
delimiters, zero-width key-only shorthand, ignored resources, namespace aliases,
inline-tag attribute spellings, scalar/array/plural identities, native
default/tablet/watch product ownership, ASCII-padded resource/product names,
protected foreign-namespace lookalikes, optional original Android resource
paths and ordered read-only/read-write AAPT2 flag context, directory-selected
locale/night configuration, directory-gated product/scalar/generic/array/plural
runtime variants, fixed-false array-item compaction, positive/inverse mutable
item conditions, preserved native reference/primitive positions,
compiler-ignored root/plural flags, SDK-10000 selected-product arrays,
positive/inverse/product-qualified runtime variants,
self-closing
Android declaration expansion, compiler-ignored inline comments, preserved
literal/mixed/safely split CDATA, trusted Apple public DOCTYPEs, direct/wrapped
Foundation dictionaries, escaped/CDATA property-list keys, self-closing XML
value expansion, selected stringsdict plural/width/device values, untouched
formatter declarations/typed metadata/nonselected device branches,
backward-compatible selector-qualified independent plural identities,
protected
Xcode root/descriptor metadata, translated locales,
review states, selected-device branches, JSON key/value escapes, supplementary
Unicode offsets, physical
property continuations/indentation, escaped separators, preserved gettext
headers/history/obsolete entries, native plural indexes, multiline C-string
wrapping, ISO-8859-1, native Windows CP1252 euros/smart punctuation/byte
escapes, strict US-ASCII, fail-closed unmappable targets, safe supplementary
surrogate escapes, and LF/CR/CRLF
natural lines. Both runtimes inject only requested translations; an empty
replacement set must reproduce every original byte. Google AAPT2, Apple
`plutil`/`xcstringstool`, GNU `msgfmt`, and the actual JDK independently
compile/parse original and translated output for all encodings.

Standalone Foundation device and presentation-width alternatives can also be
independently translated without changing existing sidecars. An explicit
opt-in extraction API emits reserved `@device` and `@width` source-slot
selectors, preserving exact branch identities such as `message#@device#mac`
and `message#@width#040`. Each branch owns its own native placeholder and
disabled-printf metadata; escaped `%%n`, repeated genuine `%n`, physical
newlines, padded numeric keys, and untouched source syntax stay exact. Real
Swift Foundation selects the translated current-device branch and every
translated below-minimum, exact, intermediate, and widest presentation-width
alternative. Ordinary extraction continues to expose only the original
selected branch, so every previous schema-version-one skeleton remains valid.

Foundation device rules can also own complete nested plural dictionaries, not
only scalar strings. Both standalone `.stringsdict` readers preserve the full
Xcode-generated native structure and expose independent
`message#@device=iphone#one`/`message#@device=mac#other` category slots through
the same opt-in API. Deterministic normalized writers retain every device rule;
source-template injection preserves untouched bytes while real Swift Foundation
selects original and translated Mac singular/plural values. The same complete
device-category ownership is separately verified against UTF-16LE source-byte
offsets and actual UTF-16 Foundation resources.

Native device branches may also own complete presentation-width dictionaries.
Independent Java/Rust templates use identities such as
`message#@device=iphone#040` and `message#@device=mac#5`, retaining padded
threshold spellings, genuine `%n`, literal `%%n`, physical newlines, and every
untouched UTF-8/UTF-16 source byte. Actual Mac Foundation lookup and
`variantFittingPresentationWidth(_:)` exercise below-minimum, exact,
intermediate, and widest original/localized branches. The reversed width → device
dictionary is a deliberate safety rejection: `plutil` accepts the property
list, but real Foundation aborts with `NSInvalidArgumentException`.

The native device dictionary need not give every device the same value shape.
Original neutral fixtures combine scalar iPhone/Mac strings with device-owned
plural and presentation-width dictionaries in both orders, heterogeneous
plural/width dictionary pairs, and a three-device plural/width/scalar tree.
A single opt-in
version-one sidecar therefore contains ordinary `@device` scalar slots beside
`@device=<name>` plural categories and padded width thresholds; genuine `%n`,
escaped `%%n`, XML entities, physical newlines, supplementary keys, and all
25 source-owned values remain independent in UTF-8 and UTF-16. Both
implementations preserve the complete native tree as `deviceMixedVariants`,
select the iPhone's correct scalar/plural/width canonical descriptor, and
reinject translations without touching other bytes. Swift Foundation verifies
original and translated Mac scalar, singular, plural, and width selection.
Plain `other` fallback strings remain independently owned even when the iPhone
uses plurals and the Mac uses presentation widths.

The same explicit `@device` identity now exposes every existing
source-language Xcode `.xcstrings` device branch. Java
`extractSkeletonWithXcodeDevices` and Rust
`extract_skeleton_with_xcode_devices` preserve source/target review states,
untranslated locale trees, native JSON whitespace/escapes, and root-owned
plural-substitution definitions while independently translating iPhone, Mac,
watch, and fallback text. The actual `xcstringstool` and Swift Foundation
verify genuine `%n`, literal `%%n`, physical newlines, and translated Mac
sentences whose shared plural branches are independently injected. Missing or
duplicated native substitution markers fail closed per device branch.
Combined device-plus-plural source trees additionally use
`message#@device=mac#one` and `message#@device=iphone#other` to independently
translate every native category on every device. UTF-8/UTF-16 fixtures, native
compiler snapshots, and actual Mac singular/plural selection protect each
review state, translated target locale, original formatting, and source byte.

`appleBinarySourceSkeletons` extends that same sidecar with
`encoding: "BINARY_PLIST"`: `source` holds the exact original bytes as
lowercase hexadecimal. Ordinary slots own an entire Foundation ASCII or UTF-16BE
string object. A shared slot instead owns its parent value-reference bytes and
its optional `appleObjectIndex` identifies the protected shared original.
Copy-on-write appends independent translated objects without mutating another
translation, a protected shorthand key, inactive plural categories, metadata,
or any untranslated value. If new objects cross the one-byte reference limit,
both implementations rebuild every dictionary/array reference at two-byte
width while preserving reference identities, dictionary order, typed values,
original object bytes, and the top object. Offset-table widths also promote as
needed. Non-ASCII targets always use standards-compliant UTF-16BE;
supplementary characters are counted in UTF-16 code units.
The original fixtures and expected localized bytes are regenerated by
`python3 file-formats/conformance/generate_apple_binary_source_skeleton_fixtures.py`
using genuine Apple `plutil` output and a third independent Python structural
reconstruction.
Android product templates additionally link original and translated resources
for every declared selected product; their native APK snapshots prove actual
product selection, default fallback, retained conditional values, and
cross-product mutable-condition collapse. Path-qualified fixtures additionally
verify compiler-implied `fr-night-v8`, the original gated intermediate
filename, ignored conflicting `tools:locale`, UTF-16 ownership, and native
conditional strings, arrays, and plurals.
Item-qualified templates additionally prove fixed-flag compaction, protected
disabled bytes, mutable empty-item expansion, ignored root/plural conditions,
default/tablet ownership, and exact reference/primitive positions in original
and translated SDK-qualified APKs.
Xcode source templates also own independently editable `%#@name@` substitution
trees through `id#selector#category` slots. Original positioned/repeated marker
lexemes, Unicode selector names, source and target review states, protected
translated locales, UTF-16 offsets, and CRLF remain native-Xcode verified.
Localization-root substitution definitions may also be shared by multiple
device sentences: only the selected source sentence and shared plural branches
are editable, while other device text remains untouched. Real Mac Foundation
execution confirms protected desktop sentences use the updated plural values;
device-local definitions and missing/duplicated root selectors fail closed.
Mixed string/numeric substitution branches start implicit argument numbering
at their native selector position, retain independently editable later
arguments and reversed explicit positions, and reject same-position type
collisions even when Xcode's structural compiler accepts them.
`sourceSkeletonErrors` separately requires cross-language fail-closed
diagnostics for source-less Xcode fallback IDs. Ambiguous inline reordering,
forged binary object/reference ownership, Android processing instructions,
binary Apple dictionaries, and unknown/out-of-range slots also fail closed.

Android annotation spans also have typed, optional
`androidRuntimeAnnotations` and per-plural-category
`androidPluralRuntimeAnnotations` metadata. AAPT2 encodes span attributes
without escaping semicolons, and Android's runtime interprets those semicolons
as annotation boundaries. The metadata therefore retains native injected keys,
truncated values, malformed keys, and repeated keys in their exact runtime
order whenever they differ from the original XML attributes. Shared writer
mutations require independent Java and Rust implementations to reject missing
or inconsistent projections with `INVALID_ANDROID_ANNOTATION`; native snapshots
record both the encoded span tag and its ordered decoded annotations.

Native Android snapshots parse AAPT2's diagnostic output as its own grammar.
The compiler does not escape literal quotation marks inside strings, plural
branches, arrays, styled visible text, or style attributes, so the verifier
uses validated span suffixes and declared array entry counts rather than
splitting at the first quote or interpreting the output as CSV. Shared neutral
fixtures cover XML-entity quotes, quote-bearing/comma-bearing array entries,
protected placeholder examples, scalar/plural/style source slots, preserved
comments, untouched resources, normalized writers, linked APKs, and the
previously hidden multiline styled-array entry.

Android treats `#` as ordinary text in strings, plural branches, protected
placeholders, style attributes, and product-specific resources. Generic
`TYPE_ANY` strings and untyped arrays additionally compile an unescaped leading
`#abc` into an opaque native color primitive; backslash/Unicode escaping,
quoting, and explicit `format="string"` preserve a translatable string instead.
Both independent plural builders quote each contiguous hash run once so real
FormatJS and ICU4J retain `#` and `##` literally instead of substituting the
selected plural count. Compiler snapshots and exact source templates preserve
color slots, original style-attribute entity spellings, category-specific
examples on the same protected placeholder ID, and independently linked
default/tablet variants. Optional typed `androidPluralPlaceholderExamples`
metadata distinguishes omitted `null`, explicit empty `""`, differing values,
and ordered repeated native examples per category and occurrence; forged
example values and categories fail safely. Conventional protected IDs such as
`arg0` cannot otherwise be distinguished from an ordinary `%1$s` when their
optional `example` is omitted. Typed `androidProtectedPlaceholderOccurrences`
and per-category `androidPluralProtectedPlaceholderOccurrences` retain an
ordered `null` for every unprotected occurrence and an object for every
protected occurrence, including an explicitly empty `example`. Independent
writers preserve the exact wrapped/plain ordering across scalars, nested
styles, arrays, plurals, normalized XML, and byte-preserving source templates;
forged ownership, examples, occurrence counts, and categories fail closed.
Actual Okapi extraction and cross-language shadow reports record missing
generic/array units, 41 protected-source mismatches, and duplicate product
identities.

Typed `androidRuntimeStyles` and per-category `androidPluralRuntimeStyles` also
retain font-size/height/color/typeface and hyperlink effects injected through
otherwise ignored attributes. Android's actual span decoder applies a fixed
first-match attribute order. Shared fixtures cover injected and truncated URLs,
repeated attributes, nested/array/plural spans, Arabic-Indic and fullwidth
decimal digits, and native-accepted invalid/overflowing/supplementary font
numbers that crash Android at runtime. Both implementations reject unsafe or
inconsistent values with `INVALID_ANDROID_STYLE`; native snapshots explicitly
capture the accepted AAPT2 output and `NumberFormatException` boundary.

Every runtime foreground/background effect additionally exposes a typed
effective color: normalized `#AARRGGBB` for platform literals, opaque-black
fallback for invalid values or foreign-package references, and explicit system
reference/stateful/default-fallback provenance. Neutral fixtures cover all 23
Android color names, foreground/background ordering, Unicode hexadecimal,
signed-hex surprises, invalid CSS-style colors, and nested/plural spans.

Typed `androidRuntimeParagraphSpans` and per-category
`androidPluralRuntimeParagraphSpans` also retain original and expanded UTF-16
half-open offsets for Android bullet and font-height paragraph spans. Original
neutral/native fixtures cover empty bullets, attributed non-bullets, injected
heights, nested spans, terminating newline inclusion, emoji offsets, non-LF
Unicode separators, arrays, and plural branches. Incorrect span boundaries or
ownership fail with `INVALID_ANDROID_PARAGRAPH`.

Android behavior is additionally grounded in the open-source AAPT2 compiler.
See `android-syntax-inventory.md` and run
`python3 file-formats/conformance/android_aapt2_oracle.py` to check the original
fixture corpus against the official compiler and its compiled-resource snapshots.
Apple, gettext, and properties have matching independent native oracles:

```sh
python3 file-formats/conformance/apple_plutil_oracle.py
python3 file-formats/conformance/apple_xcstringstool_oracle.py
python3 file-formats/conformance/gettext_msgfmt_oracle.py
python3 file-formats/conformance/java_properties_oracle.py
```

When frontend dependencies are installed in this checkout or its parent Git
worktree, `run.py` also loads the actual `intl-messageformat` and
`@formatjs/icu-messageformat-parser` packages. Their real runtime validates every
canonical ICU message, proves Android attribute-rich markup renders unchanged,
and checks gettext plural expressions against the canonical ICU message for
GNU's complete `0..1000` integer window plus bounded probes through
`1,000,000,000`. Manifest `gettextRuntimeSamples` can add explicit large-integer
cases, while `gettextFractionalSamples` records the canonical ICU variant for
fractional inputs. Fractions verify FormatJS/ICU semantics, not GNU gettext,
whose plural expressions accept integer counts only. The integrated Java suite
independently repeats both integer and fractional checks with ICU4J.

Run everything, including the integrated Java and independent Rust suites,
with `python3 file-formats/conformance/run.py --offline`. On a new machine,
`--download-aapt2` downloads and SHA-256 verifies Google's official compiler.
Optional Apple/gettext/Xcode tools are skipped when unavailable. Full Xcode can
be discovered at its installed application path even when `xcode-select` still
points to Command Line Tools.

Documented policy differences use format-specific `androidOracle`,
`appleOracle`, `xcstringsOracle`, `gettextOracle`, or `propertiesOracle` overrides. Unsafe
external-entity fixtures are never handed to the Android compiler or Foundation
property-list parser.

Fixture paths are relative to this directory. `encoding` changes how the UTF-8
fixture text is re-encoded before it reaches the parser, making UTF-8 BOM,
little-/big-endian UTF-16, ISO-8859-1 properties, malformed UTF-8, truncated
UTF-16, and unpaired UTF-16 surrogates testable without checking binary files
into Git. Apple binary property lists instead specify `encoding: "BINARY_PLIST"`
and a readable lowercase-hex `binaryFixture`; Java, Rust, `plutil`, and real
Foundation bundles receive the exact same original bytes. Reproduce every
native-generated dictionary and deliberate structural mutation with
`python3 file-formats/conformance/generate_apple_binary_fixtures.py`.
Optional bounded `binaryPaddingBytes` tests the 16-MiB maximum without storing
a large generated binary fixture in Git.
Optional `lineEndings` overrides (`CR` or `CRLF`) independently
exercise real platform natural-line grammars while retaining readable LF source
fixtures. Both implementations reject invalid encodings instead of silently
inserting replacement characters.

Apple `.strings` additionally follows Foundation's exact old-style plist lexer:
comments are allowed between every grammar token but only leading comments
become translator context; separator whitespace is limited to ASCII U+0009
through U+000D, space, and U+2028/U+2029; bare keys/values accept only
`A-Z`, `a-z`, `0-9`, `_`, `$`, `/`, `:`, `.`, and `-`; uppercase Unicode
escapes recognize ASCII hexadecimal digits only; and escaped physical LF, CR,
and CRLF preserve their exact native characters. Original positive/negative
fixtures check all these boundaries against Foundation, both implementations,
normalized writers, actual Okapi extraction, and shared shadow metrics.

Apple CoreFoundation explicitly disables printf `%n` as a dummy-pointer
conversion, so unlike Java/Android it consumes no visible output. The canonical
descriptor therefore projects `North%nSouth` as `NorthSouth`, keeps physical
newlines unchanged, and treats `%%n` as literal `%n`. Ordered
`appleDisabledPrintfConversions` metadata retains exact `%n`/`%2$n` spellings
and Unicode-scalar insertion positions, including repeated conversions at the
same position. Java and Rust normalized writers and source-preserving OpenStep,
XML, and binary templates restore the native spelling; forged source spellings
and out-of-range positions fail with `INVALID_APPLE_PRINTF_CONVERSION`.
Real Swift Foundation and FormatJS independently verify the same six neutral
messages, and executable Okapi extraction/shadow snapshots record five source
mismatches plus a changed translator comment.

For `.stringsdict`, the same behavior is owned by selector and plural category
through `applePluralDisabledPrintfConversions`. Original neutral XML and real
`plutil` binary property lists cover singular/plural `%d%n`, repeated disabled
conversions, escaped literal `%d%%n`, supplementary message IDs, and a real
category newline. Independent Java/Rust normalized writers and XML/binary
source-preserving templates reinsert each exact native spelling. Real Swift
Foundation and FormatJS verify every selected category; the actual legacy
stringsdict filter flattens all 24 projected branches, preserves disabled
conversions as visible source, and changes the real newline into a space.
Regenerate the complete native corpus and independently computed source-sidecar
snapshots with
`python3 file-formats/conformance/generate_apple_disabled_plural_fixtures.py`.

Standalone Foundation `NSStringDeviceSpecificRuleType` and
`NSStringVariableWidthRuleType` branches use scalar
`appleDisabledPrintfConversions` ownership. Neutral fixtures cover repeated
`%n`, escaped `%%n`, physical newlines, supplementary message IDs, protected
Mac/Apple Watch alternatives, and numerically selected padded-width keys.
Independent Java/Rust source writers retain each conversion on its original
side of an argument, preserve non-selected branches byte-for-byte, and escape
translated literal percentages correctly. The shared Swift oracle calls
`variantFittingPresentationWidth(_:)` to verify below-minimum fallback, exact
and intermediate boundaries, the padded `040` threshold, original protected
narrow branches, translated widest branches, disabled `%n`, literal `%%n`, and
physical newlines. Real Foundation runs on macOS, so it selects the protected
Mac branch even though the canonical descriptor intentionally owns the iPhone
source value; the native oracle verifies that distinction rather than
pretending both selectors match. Regenerate the original corpus with
`python3 file-formats/conformance/generate_apple_disabled_variation_fixtures.py`.

Xcode String Catalogs compile the same genuine `%n`/`%2$n` conversions into
generated `.strings` and `.stringsdict` resources without changing their
spellings; actual Foundation rendering then removes them. Neutral `.xcstrings`
fixtures cover scalar text, escaped `%%n`, physical newlines, supplementary
Unicode, the selected iPhone branch while retaining protected Mac/other/target
branches, repeated top-level plural conversions, and root-owned
`%#@selector@` substitution categories shared across independent device
branches. Java and Rust own scalar `appleDisabledPrintfConversions` and
selector/category-scoped `applePluralDisabledPrintfConversions`, validate
forged positions/spellings, regenerate native-compiler-equivalent JSON, and
inject translated root and substitution-category slots without touching
protected Mac, Apple Watch, or target-locale bytes. Zero-width occurrence
positions scale with translated visible text while preserving their original
ordering around the owning argument; moving `%n` before a visible numeric
argument can otherwise make Foundation consume the wrong runtime value. Actual
`xcstringstool`, Foundation bundle execution, and FormatJS independently verify
both original and translated semantics. Legacy Okapi has no `.xcstrings`
route, so the executable comparison records explicit unsupported behavior
instead of claiming compatibility. Regenerate the original corpora and
compiler-derived snapshots with
`python3 file-formats/conformance/generate_apple_disabled_xcstrings_fixtures.py`
and
`python3 file-formats/conformance/generate_apple_disabled_xcstrings_substitution_fixtures.py`.

Apple `.strings` may also be an XML property-list dictionary or a brace-wrapped
OpenStep dictionary. Foundation accepts standard `<plist><dict>` wrappers,
direct `<dict>` roots, optional XML declarations/public DTDs, both UTF-16 byte
orders, XML entities/CDATA, and empty dictionaries. CoreFoundation ignores XML
processing instructions before the trusted public DTD and between plist/root,
dictionary-key, plural-rule, and value elements; instructions embedded inside
scalar keys/values are rejected by the real native parser. Independent Java DOM
and Rust XML implementations preserve valid structural instructions in
source-owned templates and reject invalid embedded instructions. Public DTD
recognition accepts instruction/comment preambles without authorizing
custom/internal declarations or external entities. Only preceding XML comments
become translator notes; non-string values, malformed roots, duplicate/blank
keys, nested XML markup, and unsafe internal/external entities fail safely.
Foundation compares complete raw XML tag names instead of namespace-local names:
default namespace declarations, namespace resets, and bound metadata attributes
are ignored, while every prefixed plist/dictionary/key/string/typed-value tag
is rejected at any nesting level. Java independently checks DOM qualified tag
names while Rust checks complete quick-xml element names; exact source templates
retain every valid namespace declaration and namespaced attribute byte. Native
CoreFoundation tolerates malformed unbound attribute prefixes, but the portable
parsers deliberately reject them with `INVALID_XML` rather than weaken secure,
well-formed XML handling; explicit `appleOracle: "accept"` fixtures record this
intentional stricter boundary.
CoreFoundation accepts decimal and lowercase-`x` hexadecimal numeric entities
only when their digit sequences contain at most eight characters, including
leading zeroes. Keys, strings, and real numbers decode those references;
integer/date/data scalars reject every XML reference instead of decoding their
payload. Numeric references remain literal inside CDATA, comments, processing
instructions, escaped ampersands, and ignored attributes. Java independently
scans secure original XML with tag ownership while Rust validates streaming XML
reference events. Both reject Foundation-tolerated NUL/empty/control/noncharacter
references because they violate safe XML 1.0; native-accepted policy fixtures
record those deliberate divergences. Original translated skeletons preserve
escaped plural-category keys, supplementary identifiers, native newline/tab/CR
references, untouched typed real values, and every platform-owned source byte.
Literal `#` inside every native-derived plural branch is ICU-quoted when the
complete canonical message is assembled; otherwise FormatJS and ICU4J would
replace it with the count even though Foundation preserves the literal. Native
variants and source-owned XML values retain their original unquoted hash.
The configured Okapi stringsdict filter instead mistakes protected
`futureLiteral` metadata for an unsupported plural category; its exact
`Invalid plural form: futureLiteral` failure is recorded separately.
Foundation's scalar/container distinction is also native-verified for
`.stringsdict`: structural comments/instructions are valid between dictionary
and array entries, but unowned text and even whitespace-only structural CDATA
are not. Scalar comments/nested markup are invalid; CDATA is valid for keys,
strings, and real numbers but not integer/date/data fields. Explicit empty
boolean pairs are valid, while boolean whitespace is not. Original translated
templates preserve protected typed arrays and native real bits. The actual
legacy Okapi stringsdict filter rejects Foundation-valid CDATA plural-category
keys with the stable `Invalid plural form: <![CDATA[one]]>` diagnostic.
Empty `<data></data>` and whitespace-only explicit data are native-valid,
whereas `<data/>` is invalid even inside future metadata, plural rules, nested
arrays, or dictionaries. Empty strings/collections/booleans retain both native
spellings; empty integers/reals/dates fail. Java independently scans original
safe XML while Rust retains quick-xml self-closing event provenance.
`appleTypedPlist: true` now applies to source-preserving templates as well as
catalog fixtures: native `binary1` snapshots retain actual data bytes, dates,
and IEEE values where `plutil -convert json` cannot represent them. Existing
Okapi crashes on the same valid zero-length metadata with a stable
`NullPointerException`, captured in the cross-language migration manifest.
Literal HTML-looking text uses reversible ICU angle quoting so FormatJS and
ICU4J render the same original strings and positional arguments as Foundation.
Normalized empty catalogs emit a native-valid comment instead of a zero-byte
file. Crucially, `plutil` accepts JSON dictionaries that an actual Foundation
`Bundle.localizedString` refuses to load; `appleBundleOracle: "reject"` verifies
the real runtime fallback so JSON cannot be misclassified as a valid resource.

Binary `.strings` supports Foundation's complete relevant object-table grammar:
`bplist0?` versions, ignored reserved trailer bytes, 1–255-byte offset/reference
integers including 9/16/255-byte widths and ignored high-order bytes, nonzero
root references, shared objects, 16/32-byte extended dictionary/string lengths,
and more than 255 objects. The
nominally ASCII string marker actually decodes *all* bytes as ISO-8859-1,
including C1 controls; UTF-16 strings use big-endian code units and strict
surrogate validation, and the documented future UTF-8 marker is rejected by
real `bplist0?` decoders. Both independent readers bound total bytes, objects,
and string units; malformed trailers, widths, indexes, offsets, references,
lengths, nonstring values, and duplicate/empty keys have stable errors.
Original binary bundles and normalized UTF-8 text bundles render identical
Foundation, FormatJS, and ICU4J results.

Foundation also loads binary `.stringsdict` resources despite documenting
their XML representation. The same bounded object-table readers independently
decode nested plural, width, and device dictionaries, shared dictionary
objects, arbitrary offset widths, signed integers, and booleans. Native
original/normalized bundles verify positioned, reordered, repeated, and
multiple plural selectors against Swift Foundation, FormatJS, and ICU4J.
Cyclic object references, expansion-amplifying shared graphs, duplicate nested
keys, and nesting beyond 64 levels fail safely; malicious expansion fixtures
are deliberately never handed to Foundation. Recursive version-one
`applePlistExtras` preserves every native message-level/plural-rule value:
ordered heterogeneous arrays, nested dictionaries, strings, booleans,
signed/unsigned integers, byte data, UTC dates, and exact IEEE-754 reals.
Tagged canonical metadata protects binary payloads, negative zero, NaN,
infinities, and actual dictionaries that collide with the type-tag key; both
normalized writers retain declaration ownership and Unicode-scalar order.
Original XML decimal, hexadecimal, explicitly signed, and leading-zero integer
spellings normalize to their native value. CoreFoundation's permissive ASCII
base64 decoder and overflowing date components are reproduced exactly; native
binary dates with subsecond precision fail explicitly because XML cannot encode
them. The `appleTypedPlist` oracle uses actual Foundation `binary1` conversion
because `plutil -convert json` rejects valid data/date/nonfinite resources and
XML conversion drops negative-zero sign. Malformed tags, reserved collisions,
unsupported nulls, typed known plural fields, and cyclic arrays fail safely.

Android cases may also declare `resourcePath`, which is passed to both native
parsers and to Google's AAPT2 compiler. This tests real `values-*` configuration
qualifiers, directory-derived locales, qualifier precedence, legacy square/
stylus values, physical pixel dimensions, native numeric wrapping, implied SDK
versions, exact `donottranslate` filename prefixes, and stable diagnostics
without coupling the canonical contract to either implementation. Optional
`androidPseudolocalized` snapshots compile the original and normalized resource
with actual AAPT2 `--pseudo-localize` and compare generated `en-rXA`/`ar-rXB`
resource identities.

The Android string-resource documentation currently claims that XML-decoded
Unicode whitespace collapses, but actual AAPT2 9.3 preserves all 16 probed
non-ASCII boundary characters: U+0085, U+00A0, U+1680, U+180E, U+2000,
U+2003, U+2007, U+2008, U+200A, U+200B, U+2028, U+2029, U+202F, U+205F,
U+3000, and U+FEFF. Forty-nine original entity, `\\u` escape, ASCII-adjacent,
boundary, and explicitly quoted values are compiled against real native
snapshots. Independent Java/Rust parsers and normalized writers preserve
every Unicode separator; original/translated UTF-8 and UTF-16 source
templates additionally retain protected XML spellings and exact offsets.
Use compiler behavior over contradictory prose.

Android reference fixtures follow AAPT2's real resource-type allowlist and
package-qualified/private aliases, `@+id` creation, typed resources, dotted and
dashed entries, and qualified/private/shorthand theme attributes. Numeric,
unknown-type, invalid-creation, invalid-theme, escaped, quoted, and Unicode-
padded lookalikes remain translatable strings. The native compiler normalizes
creation IDs and theme spelling in compiled snapshots, while canonical
array/plural metadata and normalized writers preserve their exact original
source spelling. One original fixture proves 15 standalone aliases, nine
preserved array-reference slots, and five plural-reference branches. Standalone
aliases are intentionally absent from canonical extraction and the normalized
compiled snapshot; the actual configured Okapi extractor instead emits 43
legacy units versus 19 projected canonical units, producing 24 unexpected
reference-valued string/array units and five plural-source mismatches.

AAPT2 additionally passes reference names to the Android framework's
`ExtractResourceName`, which consumes a second leading `@`: `@@string/name`,
`@@color/name`, and `@@macro/name` are native aliases, not translatable text.
XML entities, CDATA, and inline comments preserve that behavior; quoted,
escaped, tripled, unknown, null, and create-ID spellings remain literal.
Independent Java/Rust parsers preserve exact doubled array/plural reference
metadata, recursively expand doubled macro aliases, and keep original alias
spelling and macro declarations byte-for-byte in source templates. Actual
AAPT2 compiled/linked snapshots verify every boundary; actual Okapi emits 30
legacy units where native extraction has only 17 canonical descriptors, or 22
projected shadow units after expanding plural comparison identities.

The framework resource-name splitter also accepts arbitrary whitespace inside
reference entries: ordinary spaces, tabs, LF, CR, CRLF, nonbreaking spaces,
and em spaces remain opaque aliases rather than translatable text. These
names compile but do not resolve at link time, so the neutral fixture checks
native compiled snapshots without claiming a valid linked resource. The AAPT2
oracle preserves actual multiline scalar/plural values and uses declared array
counts to separate printer wrapping from embedded line breaks. Java and Rust
normalized writers independently encode CR as `&#13;`; otherwise XML line-end
normalization silently changes preserved array/plural reference identities.
Source templates retain exact XML entity spelling and inline comments. Actual
legacy extraction emits 27 units against 11 projected canonical units, leaking
17 aliases, corrupting five plural sources, and omitting one generic-array text.

XML attributes apply a different whitespace rule: literal tabs, line feeds,
and carriage returns normalize to ordinary spaces, whereas numeric character
references preserve the original control. Android translator descriptions and
protected `xliff:g example` attributes depend on that distinction across
ordinary/generic strings, arrays, and plurals. Both independent normalized
writers emit `&#9;`, `&#10;`, and `&#13;`, preserving descriptions, protected
examples, CRLF, and styled annotation attributes through repeated parsing.
Source-owned templates retain original quote styles and entity spellings.
Real AAPT2 compiled and linked snapshots preserve literal span-control bytes;
the linked oracle avoids Python universal-newline conversion. Actual Okapi
returns 17 units versus 19 projected canonical units: two missing generic/array
messages, two lost translator notes, and five malformed protected/style sources.

Generic `<item type="string">` resources use AAPT2's `TYPE_ANY` parser unless
an explicit `format` narrows their value. Native booleans, decimal/hex
integers, colors, dimensions, floats, and fractions are therefore compiled
primitives rather than translatable strings; quoted numbers and escaped colors
remain source text. `format="string"` instead activates strict string-format
validation and real `formatted`/`translatable` semantics, while empty `format`
remains the default generic path. `androidPrimitives` requests native compiled
`primitiveValues` snapshots, and `androidGenericFormat="string"` preserves the
strict behavior through both normalized writers. The original fixture contains
12 typed primitives, two aliases, ten native strings, and nine genuinely
translatable messages. Existing Okapi extracts only the ordinary `<string>` and
misses all eight generic translations; the shared shadow report records every
missing ID.

AAPT2's strict formatting rule is not a conventional printf regex. Its native
scanner counts malformed percent fragments, optional `%<$s` relative reuse,
unpositioned widths, whitespace, and Python-style names; it exempts `%%` and
`%n`, rejects multiple nonpositional substitutions, and immediately accepts
unmistakable legacy `Time.format()` directives. Java and Rust independently
mirror that scanner for ordinary `<string>` and strict generic
`format="string"`, preserving positional Java `%1$tY` dates, time shortcuts,
literal percentages, newlines, and the `formatted="false"` opt-out through
byte-identical normalized XML and actual AAPT2 compiled snapshots.

Native Android also accepts terminal `%` and solitary malformed percent
fragments even though escaped `%%` compiles to a different literal resource.
`androidRawPercentOccurrences` and per-category
`androidPluralRawPercentOccurrences` retain that otherwise invisible spelling
distinction across strings, strict/default generic items, arrays, plurals,
supplementary Unicode, and styled spans while excluding opaque annotation
attribute values. Both
writers validate sorted occurrence provenance and recreate exact native APC
strings and style spans; malformed or out-of-range metadata fails with stable
`INVALID_ANDROID_PERCENT` diagnostics.

Android's escaped `\\n` and printf `%n` compile to different native strings but
collapse into the same canonical FormatJS newline. Existing
`androidPrintfLineSeparator` catalogs remain unchanged when every newline comes
from `%n`; mixed strings/arrays add typed
`androidPrintfLineSeparators`, and plurals add per-category
`androidPluralPrintfLineSeparators`, using zero-based newline occurrence
indexes. Original neutral fixtures cover alternating spellings, supplementary
Unicode, actual annotation spans, positional arguments, strict/default generic
strings, raw/escaped percent adjacency, escaped literal `%%n`, and independent
plural branches. The AAPT2 oracle retains genuine multiline array/plural values
and compares compiled strings plus UTF-16 style offsets. Both writers reject
malformed, unformatted, wrong-shape, or out-of-range provenance with
`INVALID_ANDROID_LINE_SEPARATOR`.

AAPT2 treats annotation/font attribute values as opaque native span metadata.
Formatter-looking attribute `%n`, `%%n`, `%1$s`, `%_`, and solitary percent
tokens never become visible placeholders or consume visible-text occurrence
indexes. Both parsers preserve the exact attribute spelling while normalized
writers serialize native LF, CR, and TAB attribute values as safe numeric XML
character references. Neutral fixtures exercise ordinary/generic strings,
nested spans, arrays, independent plural categories, supplementary Unicode,
and `formatted="false"`; the byte-accurate AAPT2 oracle verifies native span
attributes, UTF-16 offsets, and distinct carriage-return/newline values.

Build-time `<macro>` and generic `<item type="macro">` resources are expanded
using the same local/private/package-qualified grammar as AAPT2 references.
Optional `androidApplicationPackage` supplies explicit package ownership for
qualified names and public/private XML namespace aliases; `res-auto` aliases
resolve locally without package context, while missing or foreign definitions
fail closed. Namespace aliases declared only on the macro definition remain in
scope during recursive expansion and cross-file source-set overlays. Ordinary
`@alias:string/...`, private references, and `?alias:attr/...` embedded inside
macro values resolve against that definition-site stack even when the target
reuses the same prefix for another package. Canonical array/plural metadata
and normalized writers emit linkable local `@string/...` and `?attr/...`
identities instead of leaking definition-only aliases. Scoped XLIFF namespaces
remain protected, while unrelated namespaced wrappers retain visible text.
Native
original/normalized compiled and linked snapshots preserve styled spans,
protected placeholders, generic strings, arrays, plurals, escaped literal
references, and deterministic output. The actual Okapi differential contains 25
canonical versus 24 legacy units: one missing generic declaration plus 22
unexpanded macro sources.

Macro-expanded and ordinary `?attr/...` array/plural slots now preserve the
untranslated `<attr>` or generic `<bag type="attr">` declarations they actually
reference. Typed `androidAttributeDependencies` retain compiler-normalized
format masks, signed 32-bit integer bounds, implicit formats, sorted enum/flag
symbols, and generic declaration identity; unrelated declarations remain
outside the translation catalog. Independent Java/Rust writers validate and
deduplicate those structured dependencies before emitting XML-safe attribute
declarations, so the previous macro/theme case now has original and normalized
compiled/linked AAPT2 snapshots. Cross-file overlays also resolve the winning
attribute declaration at its native source-set priority.

Nested `<declare-styleable>` attributes are more subtle: AAPT2 creates a weak
typed `<attr>` only when the nested entry has a format or inline enum/flag
symbols; untyped local or `android:label` entries remain ordered references.
Matching weak/strong declarations merge, incompatible declarations reject,
and generated `R.styleable` arrays depend on group order and repeated entries
even though the groups disappear from linked APK resource tables. Optional
`weak: true` attribute metadata and `androidStyleableDependencies` therefore
preserve direct/generic groups, original child order, inferred symbols, bounds,
`res-auto` local aliases, and framework references. Original/normalized
compiled AAPT2 snapshots record actual `PUBLIC` styleable groups, linked
snapshots prove the resulting typed resources, and two source-set fixtures
prove cross-file weak ownership and higher-priority group replacement. The
real Okapi differential contains seven canonical versus 12 legacy units,
including five leaked theme references and one wrong plural source.

Attribute bounds and enum/flag symbols use Android framework
`ResTable::stringToInt`, not Java `Long.decode`, Rust radix inference, or
ordinary C-style integer parsing. Leading zeroes remain decimal (`010` is ten
and `08` is valid); only lowercase `0x` starts unsigned 32-bit hexadecimal,
while decimal values must fit signed int32. Leading `+`, negative hexadecimal,
uppercase `0X`, `#`, Unicode padding, and overflow reject. Empty
`enum`/`flags` masks, combined masks, repeated symbolic values, and signed
projections of unsigned hexadecimal all have independent original/normalized
AAPT2 snapshots. Private `*android:` and `*local:` styleable references
normalize to their real framework/local names. A separate `values-fr-night`
fixture verifies AAPT2's actual warning and default-only attribute/styleable
configuration while retaining the translated resource's `fr-night-v8`
configuration.

A broader reversible
source skeleton for original macros, formatting, comments, and other
nontranslatable resources remains separate work.

AAPT2 invokes its reference linker before product stripping, so multiple
product variants of one macro trigger a fatal linker assertion rather than
supporting selected-device macro values. `androidLinkCrashSignal` and optional
`androidLinkAbortContains` verify the real compiler SIGABRT/SIGSEGV for
product variants, cyclic aliases, runtime-gated directories, and conditional
macro references. Both production-independent parsers reject those same inputs
with stable errors instead of invoking unsafe native behavior.

Heterogeneous Android `<array>` resources independently exercise the same
`TYPE_ANY` behavior at individual item positions. Native-compiled snapshots
contain six arrays, nine boolean/integer/color/dimension/float/fraction slots,
and seven package/theme references. `androidArrayPrimitives` and
`androidArrayReferences` preserve original sparse slot positions and native
source spellings; `androidGenericArray` plus `androidArrayFormat` preserves
`<array>` identity and explicit string-only formats. Both normalized writers
regenerate the mixed/reference arrays byte-for-byte, while arrays containing
only primitives/references and explicitly nontranslatable arrays are
intentionally omitted. Two real AAPT2-linked source-set overlays prove that
typed arrays replace ordinary string arrays atomically and integer-only arrays
suppress lower-priority translations. Existing Okapi misses all eight actual
typed-array source strings.

The AAPT2 source additionally accepts undocumented generic
`<bag type="array">`, `<bag type="string-array">`,
`<bag type="integer-array">`, and `<bag type="plurals">` declarations. Each
dispatches through its effective direct-resource parser, including native
heterogeneous slots, strict array formats, control elements, normalized product
identity, plural rules, and integer-only overlay tombstones. Canonical
`androidBagType` preserves the original generic declaration so independent Java
and Rust writers regenerate actual `<bag type="...">` elements. The original
AAPT2 snapshot records five arrays, four primitive positions, two references,
and one plural group; two real linked overlays prove generic array/plural
replacement and integer-array tombstones. Real configured Okapi extracts only
the ordinary control string, missing all 14 bag-derived canonical units.

Modern AAPT2 applies a separate filename-level localization gate: only a
basename beginning with exact lowercase `donottranslate` disables the entire
file and all generated pseudolocales. Internal occurrences, uppercase initial
letters, and mixed-case spellings remain localizable. The gate suppresses
strings, generic declarations, typed arrays, plurals, generic bags, and even
explicit `translatable="true"`; default-hidden strings also skip non-positional
printf validation while explicit true still reenables it. Two native-linked
overlays prove hidden files replace lower visible resources as tombstones.
The configured Okapi filter ignores this filename rule and incorrectly emits
18 private text units; independent Java/Rust extraction produces none.

Original compiler-verified structural fixtures also require an unnamespaced
`<resources>` root, reject stray top-level text, and ignore foreign top-level
resource declarations together with their descendants. Only unnamespaced
`<item>`, `<skip>`, and `<eat-comment>` children are valid inside arrays and
plurals; controls never shift physical array slots. Root controls clear pending
descriptions, consecutive comments use the latest comment only, and ignored
foreign top-level elements do not consume that context. Resource/product names
trim ASCII whitespace, then resource IDs follow AAPT2's exact Unicode
`XID_Start`/`XID_Continue` rules plus `_`, `.`, and `-`; normalized names and
products retain correct collision/overlay identity. AAPT2's generated XID
tables are BMP-only: supplementary Unicode letters/CJK and join controls fail
even when current ICU/Rust Unicode identifier properties accept them. Actual
Okapi extraction of
the same original fixture emits 15 units versus 13 canonical units, yielding 25
measured differences: three comment mismatches, ten missing normalized IDs, and
twelve unexpected skipped/foreign/unnormalized units.

Original Android boolean fixtures also exercise AAPT2's exact six accepted
`true`/`false` case spellings rather than generic case-insensitive parsing.
Strings, explicit `format="string"` items, and arrays reject arbitrary mixed
case; plurals, default generic items, and individual array entries ignore those
attributes. Native source/normalized
snapshots preserve the intentional omission of explicitly nontranslatable
resources, and a real linked overlay proves ignored flags do not accidentally
turn higher-priority plural/generic replacements into tombstones. Its
manifest-declared Okapi comparison captures 20 projected canonical units versus
28 actual legacy units, including one missing generic declaration and nine
unexpected nontranslatable strings, array items, or synthesized plural forms.

Original protected-section fixtures separately prove AAPT2 recognizes every
XLIFF-namespace `g`, including missing or empty IDs. Both independent parsers
assign deterministic `_xliffN` identifiers, preserve numeric, accented, CJK,
emoji, and combining-mark names through actual FormatJS/ICU4J validation, and
regenerate identical native-compilable XML. Nested protected sections,
FormatJS-unsafe names, conflicting protected values or formatter positions, and
styled protected sections without a reversible skeleton fail with shared stable
diagnostics, including explicit policies where AAPT2 itself accepts the input.
The same 21 canonical protected units are compared with actual Okapi extraction;
all 21 legacy sources retain XML wrappers and therefore mismatch their canonical
FormatJS descriptors.

String Catalog substitution fixtures cover Xcode's actual `%#@name@` plural
reference syntax. Java and Rust expand independently varying substitutions
into nested FormatJS plurals, retain argument positions, Unicode names,
repeated selectors, translated substitution trees, and review states, then
regenerate byte-identical catalogs. Xcode compilation verifies the real
Foundation plural dictionaries; ten shared runtime examples exercise every
two-selector combination plus Unicode, repeated, and implicit cases against
both FormatJS and ICU4J. Native-policy fixtures reject unsafe argument
positions, missing `other`, unsupported identifier/format spellings, undefined
references, and malformed substitution definitions.

The real Xcode compiler forbids referencing `%#@selector@` substitutions from
inside an existing plural category. Original neutral source-locale,
device-nested, and target-locale fixtures all assert the actual compiler
diagnostic, and both independent parsers reject them as `INVALID_XCSTRINGS`.
Unused root substitution definitions remain native-valid; this is a precise
platform boundary, not a missing nested-axis feature.

Legacy `.stringsdict` cases separately cover the positioned `%1$#@name@`
markers Xcode actually emits, multiple independently selected/reordered
arguments, repeated selectors, ordinary outer `%3$@` arguments, additional
string arguments inside plural branches, and branches that spell out counts
instead of interpolating them. Eighteen shared examples, including digit-leading
and underscored identifiers, run through both FormatJS and ICU4J; Apple's real
Swift Foundation runtime also resolves every example from both original and
normalized resource bundles. Foundation
accepts Unicode plural names at the property-list layer but does not expand
them when formatting. Direct legacy Unicode/emoji names, zero/overflow/conflicting
positions, missing/unused definitions, nonnumeric selectors, and invented
categories therefore have explicit `appleOracle: "accept"` rejection fixtures.
Modern String Catalog Unicode names remain supported because Xcode sanitizes
their compiled Foundation dictionary keys.
The same native catalog is passed through Mojito's current Okapi stringsdict
filter: it emits 48 detached branch units but keeps only one variable from each
independent two-plural message. The shared Java/Rust shadow report records 38
projected canonical units, 48 legacy units, two missing complete messages, and
twelve unexpected orphaned branches.

Gettext charset fixtures are encoded from readable, original UTF-8 files
immediately before Java, Rust, and GNU tests. Header-declared UTF-8,
ISO-8859-1, US-ASCII, and CP1252 control both raw characters and hexadecimal/
octal escapes. Valid case/underscore aliases, CP1252 euros/smart punctuation,
and deliberately mislabeled UTF-8 bytes match actual compiled MO values;
nonportable charset names, invalid bytes, undefined CP1252 positions, malformed
headers, and Unicode byte-order marks fail consistently. Writers normalize all
accepted legacy inputs to UTF-8 without changing the GNU catalog.

`androidOverlays` describes complete multi-file resource sets. Each input names
its original fixture, `resourcePath`, and one explicit Gradle source-set
priority: `library`, `main`, `product_flavor`, `build_type`, or `build_variant`.
Java and Rust independently sort those priorities, merge complete native
resource identities, retain winning source paths/source sets, replace arrays and
plural groups atomically, preserve independent product variants, recognize
equivalent legacy/BCP-47 locales, density/dimension/network aliases and
compiler-implied SDK versions, and treat higher-priority
nontranslatable resources as tombstones. Same-priority duplicate declarations,
mixed resource configurations, invalid priorities, and empty input fail with
shared stable errors. Google's actual AAPT2 compiler compiles every source and
links the resources with real overlay semantics; `androidLinked` snapshots
capture the resulting strings, arrays, plural branches, typed native
array/plural references, normalized
configuration, and optional `androidProducts` selections. Same-priority
conflicts are separately rejected by AAPT2 without overlay flags.

Individual extraction cases can declare an `okapi` comparison with an original
`assetPath`, `policy`, and native legacy snapshot. `match` asserts actual
Mojito `AssetExtractor` text-unit names/source text equal plain canonical
descriptors. `different` requires an explicit migration reason and snapshots
the real configured Okapi filter, source IDs, visible text, comments, plural
forms, and usages. `rejected` records the actual exception class/message when
legacy extraction crashes on valid platform input, while `unsupported` proves
the real production extension mapper currently rejects that asset path. These
comparisons are observational only: the independent Java/Rust parsers do not
call Okapi, and existing production filter routing remains unchanged.

`shadowComparisons` references those same canonical fixtures and real legacy
snapshots. Independent Java and Rust project canonical descriptors into the
native legacy text-unit shape, then emit identical bounded reports describing
`match` or `mismatch`, unit counts, and stable `legacy_projection_collision`,
`duplicate_legacy`, `missing_legacy`, `unexpected_legacy`, `source_mismatch`,
`comment_mismatch`, `plural_mismatch`, or `usage_mismatch` categories.
`legacy_projection_collision` preserves the sorted native `canonicalIds`
for distinct product/feature-flag alternatives collapsed into the same legacy
identity; genuine `duplicate_legacy` remains separate, and canonical messages
are never mislabeled as duplicates. Report
fixtures preserve message IDs for diagnosability. Production instrumentation,
finite-cardinality metrics, sampling, payload limits, and pipeline failure
isolation remain separate future integration work; the standalone suite does
not hook or change existing extraction.

`androidNormalized` adds an original, language-neutral expected XML snapshot.
Java and Rust must emit exactly those same UTF-8 bytes, reparse them into the
original canonical catalog, and produce identical bytes on a second write.
AAPT2 separately compiles the normalized XML and compares real strings, arrays,
plural forms, typed array/plural references, style spans, product variants, and
configurations against the source's `androidCompiled` snapshot. Mixed arrays
preserve their original reference slots through `androidArrayReferences`;
referenced plural quantities remain `androidPluralReferences` and never become
translatable source text. `androidGenericString` preserves declarations that
would fail formatting validation if rewritten as ordinary strings. An optional
`androidNormalizedCompiled`
documents an intentional canonical-extraction omission, such as an original
nontranslatable resource or standalone resource alias that cannot appear in
regenerated normalized output.
`writerReject` records stable writer diagnostics independently of implementation
language, and `writerMutations` can replace descriptor variants, descriptions,
or metadata to exercise malformed reference maps and array-slot contracts.

`appleNormalized` likewise supplies a language-neutral UTF-8 Apple `.strings`
snapshot. Both writers must match it byte-for-byte, preserve the canonical
catalog after reparsing, and remain idempotent. Foundation parses both the
original resource (including UTF-8 BOM or either UTF-16 byte order) and its
normalized UTF-8 output into exactly the same `appleCompiled` dictionary.
Optional `appleStringsRuntimeSamples` additionally load original XML, direct
dictionary, OpenStep, and binary plist resources plus normalized plain-text resources through
real Swift Foundation bundles and execute their positional format arguments;
FormatJS and ICU4J independently verify the same output, including literal
markup and apostrophes.
`appleBundleOracle: "reject"` instead proves a tool-accepted resource is
rejected by the actual runtime bundle lookup.

`appleStringsdictNormalized` supplies a deterministic UTF-8 Apple XML property
list. Both runtimes preserve exact localized format patterns, independent plural
definitions, optional/per-variable value types, CLDR branches, width/device
variants, leading-zero widths, Unicode scalar ordering, and safe XML character
escaping. Java and Rust output must match byte-for-byte, reparse to the
identical canonical catalog, remain idempotent, and convert through Foundation
`plutil` to exactly the original `appleCompiled` dictionary. Optional
`appleStringsdictRuntimeSamples` additionally exercise the actual Swift
Foundation formatter on original XML or binary and normalized native bundles,
as well as the canonical FormatJS and ICU4J runtimes.

`xcstringsNormalized` supplies deterministic UTF-8 Xcode String Catalog JSON.
Both native writers preserve source language/version, extraction and review
states, source/translated localization trees, plural/device/substitution axes,
unknown metadata, and locale spellings. Native fixtures additionally distinguish
string, integral, and fractional catalog versions; nullable descriptor fields
and locale branches; `machine_translated` and unknown future review states; all
six CLDR plural categories; and unknown device names selected in Unicode scalar
order. Xcode's real `xcstringstool compile` builds both source and normalized
catalogs, while `plutil` decodes every generated `.strings`/`.stringsdict`
resource into `xcstringsCompiled` snapshots. Native-accepted but noncanonical
plural names, empty identifiers/locales, inactive localization sets, and empty
variation maps use explicit `xcstringsOracle: "accept"` rejection fixtures. An
optional `xcstringsNormalizedCompiled` documents intentional filtered-source
differences, such as `shouldTranslate=false` entries.

Opt-in `xcstringsInsertSourceLocale` source templates preserve both explicit
source-language `null` tokens and genuinely missing source keys in active
target-localization objects. Missing keys use zero-width version-one source
slots and insert only a native `translated` source unit; native Xcode omits
both absent/null sources and source units marked `new`. Original/translated
compiler and Foundation snapshots verify hidden `%n` argument ownership,
existing target review states, protected/null target locales, supplementary
identifiers, and UTF-8/UTF-16 byte boundaries. Empty or absent localization
objects remain intentionally rejected despite native compiler tolerance.

Opt-in `xcstringsTargetLocale` source templates instead preserve the original
source language while inserting or updating scalar translations in a chosen
target language. The version-one sidecar's `appleTargetLocale` retains an
existing catalog-wide underscore/hyphen spelling; mixed aliases, invalid
identifiers, and source-language requests fail closed because the real Xcode
compiler creates distinct physical resource directories for each spelling.
Existing target states remain unchanged, absent/null targets become
`translated`, and native snapshots show that `new` target entries compile even
though `new` source entries do not. Original/translated target-locale bundles,
real Foundation formatting, hidden `%n`, protected records, and UTF-8/UTF-16
zero-width/null/value ownership are all independently verified.

Review and extraction states are forward-compatible, platform-owned strings,
not portable enums or implicit approval requests. A native 320-case Xcode
matrix accepts `new`, `needs_review`, `translated`, `machine_translated`,
`stale`, `future_review`, `untranslated`, completely unknown future states,
and unknown extraction states. A source unit marked `new` is omitted from the
development-language bundle unless its entry is explicitly `manual`; every
target state still produces its localized resource. Independent Java/Rust
fixtures preserve all source/target/German/extraction states byte-for-byte,
including unknown strings and protected entries, while updating only the
authorized target value in UTF-8 and UTF-16. The version-one sidecar owns the
value/null/insertion bytes, not an existing `state` token: changing an existing
review state requires a separately authorized, versioned ownership contract.

The same target-locale API independently owns every existing target plural
category, including Russian `few` and `many` branches that have no English
source equivalent. Each native `id#category` slot restores its target-owned
positional arguments and anchors hidden `%n` conversions to the nearest
original placeholder instead of drifting when translated text grows. Existing
`needs_review`, `new`, `future_review`, and `translated` branch states, source
plural values, unrelated German localizations, protected plural trees, and
UTF-8/UTF-16 bytes remain unchanged. Actual Xcode compiler snapshots and Swift
Foundation execution verify Russian `one`/`few`/`many` selection for 0, 1, 2,
5, 21, 22, and 25. A genuinely absent or explicit-null target plural owns one
atomic `id`/zero-width/null slot rather than overlapping per-category slots;
its translation is one complete ICU plural message containing the exact
category set demonstrated by an existing target-language plural sibling.
Both implementations insert every category as `translated`, preserve the
original source's hidden `%3$n` argument, and safely reject incomplete,
invented, duplicated, malformed, unknown-placeholder, protected-forgery, or
category-evidence-free requests. Original/translated compiler snapshots and
Russian Foundation execution verify the newly inserted UTF-8/UTF-16 branches.
Xcode permissively accepts target plurals without `other` and invented
categories; the portable implementation deliberately rejects those ambiguous
shapes.

The same opt-in target-language contract independently owns existing iPhone/Mac
device branches. Scalar values use `id#@device#iphone` and
`id#@device#mac`, while each device's independent Russian plural category uses
`id#@device=iphone#few` or `id#@device=mac#many`; the version-one sidecar and
its selector fields remain unchanged. Each branch restores its own positional
arguments and placeholder-anchored hidden `%2$n`/`%3$n`, preserves original
`needs_review`, `new`, `future_review`, and `translated` states, and leaves
source-language branches, protected records, formatting, and UTF-8/UTF-16
bytes untouched. Real Xcode resources and Swift Foundation execution verify
actual Mac selection and Russian category selection; nonexistent device,
missing `other`, mismatched branch shape, and protected-slot forgery fail
closed.

Absent or explicit-null scalar-device and device-plus-plural target trees own
one atomic `id` slot at the missing locale boundary or original `null` token.
Its translation is one complete FormatJS-valid ICU `{device, select, ...}`
containing every original source device; nested branches carry one complete
`{count, plural, ...}` for the categories evidenced by an existing target
language. The required select `other` must exactly match the canonical default
device: real `xcstringstool` accepts a physical `other` device but silently
drops it, so the converter must never manufacture that branch. Every inserted
device/category is `translated`, sorted deterministically, and restores native
placeholders plus source-anchored hidden `%2$n`/`%3$n`. Compiler and Russian
Foundation snapshots verify missing/null scalar and nested-plural insertion,
actual Mac category selection, protected records, and UTF-8/UTF-16 bytes;
omitted/invented devices, divergent fallback, incomplete categories, unknown
arguments, missing language-category evidence, and forged protected-null
ownership fail closed.

Existing target-language substitution definitions now have independent
version-one `id#selector#category` ownership even when Russian adds `few` and
`many` categories absent from its English source. Scalar target sentences own
their ordinary `id` root, while iPhone/Mac sentences use
`id#@device#iphone`/`id#@device#mac`; each root reconstructs its exact
target-native `%#@lanes@` and positioned `%2$#@lights@` spellings after
translated selectors reorder. Target-specific `argNum`, `formatSpecifier`,
`needs_review`/`new`/`future_review`/`translated` states, hidden `%4$n`,
protected records, source-language definitions, and untouched UTF-8/UTF-16
bytes remain intact. Real `xcstringstool` and Russian Swift Foundation verify
independent scalar/iPhone/Mac roots and target-only plural selection. Missing
selectors/source-owned devices/`other`, invented categories, duplicated/missing
root markers, and forged selector ownership fail closed.

Absent or explicit-null target substitution trees own one atomic `id` slot.
For scalar entries, its translation is one complete ICU sentence containing
every selector's independently translated plural; device entries instead carry
one complete `{device, select, ...}` whose iPhone/Mac roots reuse identical
shared selector definitions. Existing same-language target substitutions prove
the complete native category set, compatible `argNum`/`formatSpecifier`, and
placeholder-anchored `%4$n`; every materialized root/category receives the
`translated` state. Source-owned positioned `%#@name@` spellings, exact device
sets/default fallback, protected records, and untouched UTF-8/UTF-16 bytes
remain preserved. Real Xcode and Russian Foundation verify all four missing/null
scalar/device insertions. Missing/invented selectors or categories, unknown
placeholders/devices, unequal device-shared definitions, divergent fallback,
and protected-null forgery fail closed.

A genuinely first target locale can now obtain native cardinal categories from
the version-one `cldr-cardinal-categories.v1.json` manifest, derived directly
from Unicode CLDR 46/Unicode 16. Its 219 records contain 218 supported locale
rules plus the explicitly non-routable `und` root. Java additionally checks
each selected category set against ICU4J, Rust reads the identical manifest at
compile time, and the real Node ICU oracle verifies all 218 supported locale
sets without allowing silent English fallback. Existing same-language catalog
evidence remains authoritative, and an already present locale lacking usable
plural evidence does not acquire guessed categories. Original missing/null
Russian records demonstrate first-locale `one`/`few`/`many`/`other`, preserved
source-owned hidden `%3$n`, deterministic `translated` states, protected
records, exact UTF-8/UTF-16 source templates, and actual Xcode/Foundation
category selection. Missing/null first-locale iPhone/Mac scalar and nested
device-plural trees also derive each device's complete Russian category set
from pinned CLDR, retain source-owned scalar `%2$n` and plural `%3$n`,
preserve unrelated German/protected records, enforce the exact source device
set/default fallback, and run actual Mac Foundation selections in UTF-8 and
UTF-16. Separate missing/null first-locale scalar and iPhone/Mac
substitution records insert both complete positioned `lanes`/`lights` trees
from the same pinned CLDR rules. The source's own `argNum`, `formatSpecifier`,
native `%#@lanes@`/`%2$#@lights@` markers, complete device set/default
fallback, protected German records, and translated review states survive exact
UTF-8/UTF-16 injection. A hidden `%4$n` is never fabricated when no source
category owns it; real Xcode plus Russian Foundation validate both scalar
selection and the Mac branch. Unknown languages, incomplete/invented
categories, source-unknown devices/placeholders, and the undefined `und` root
fail closed; mutable existing review states remain a separate future contract.

CLDR upgrades are explicit, fail-closed migrations, not dependency refreshes.
The immutable CLDR 46 manifest has a checked SHA-256, exact source release tag,
Unicode version, and byte-identical Java resource. Java, Rust, and Node each
reject unaudited release metadata; CLDR 48.2's `cv`, `ie`, `kok`, `kok-Latn`,
and `sgs` remain intentionally unsupported. Audit an original upstream
`plurals.json` with
`python3 file-formats/conformance/cldr_upgrade_audit.py /path/to/plurals.json --sha256 EXPECTED --require-safe`.
The audit lists added/removed locales, category changes, unsafe locale shapes,
and missing `other`, and refuses upgrade candidates needing platform review.
Actual Java ICU4J 78.3 already recognizes all five CLDR 48 additions, but the
currently installed Node ICU 76.1/CLDR 46 recognizes none; `kok-Latn` also
exceeds the current language/region-only manifest contract. Upgrade only
after updating locale-shape policy, native Xcode/Foundation/Node/Java coverage,
cross-language templates, source hashes, and reviewed version provenance.

Regional identity is more specific than the set of available categories.
Original Brazilian `pt_BR` and European `pt-PT` records each require pinned
`one`/`many`/`other`, but actual Node ICU, Java ICU4J, and Swift Foundation
select `one` for Brazilian zero and `other` for European zero. Foundation also
formats one million as `1.000.000` versus `1\u00a0000\u00a0000`. The portable Java/Rust
writers preserve exact catalog-owned underscore/hyphen spelling, original
`pt_BR.lproj`/`pt-PT.lproj` identities, absent/null regional target boundaries,
protected German records and hidden `%3$n`, deterministic translated states,
and every UTF-8/UTF-16 source byte. Safe region/script fallback to a pinned
language category set remains valid (`pt-BR`, `zh-Hans`, `sr-Latn`, `ru-RU`);
undefined or unsupported language roots still fail closed. Category matching
never collapses distinct regions or substitutes one region's runtime locale.

Script-qualified identity also differs from the physical bundle name. Actual
Xcode compiles Serbian Latin `sr_Latn` into `sr-Latn.lproj` but minimizes
Serbian Cyrillic `sr-Cyrl` into `sr.lproj`; regional `sr-Cyrl-RS` similarly
becomes `sr-RS.lproj`, whereas Chinese `zh-Hans` and `zh-Hant` stay distinct.
Original Serbian Latin/Cyrillic missing/null first-locale fixtures preserve
catalog-owned script spelling, exact native bundle identity, all three
`few`/`one`/`other` categories, source-owned `%3$n`, translated states,
protected entries, actual script-qualified Foundation selection, and untouched
UTF-8/UTF-16 source bytes. Xcode accepts `sr`/`sr-Cyrl`,
`sr-RS`/`sr-Cyrl-RS`, `az`/`az-Latn`, and `uz`/`uz-Latn` together but silently
overwrites one translation in their shared native bundles; repeated compiles of
identical input can choose either winner. Independent Java
and Rust intentionally reject these proven data-loss cases as
`DUPLICATE_LOCALE`, while preserving distinct Chinese scripts and keeping
unaudited CLDR 48-only `kok-Latn` unsupported.

Deprecated language identifiers have their own independent ownership rules.
Real Xcode aliases `iw` to `he.lproj`, `in` to `id.lproj`, `ji` to `yi.lproj`,
and `no` to `nb.lproj`; case-only locale spellings and additional native
default-script aliases also silently collapse. Foundation/CLDR minimization
is not an equivalent oracle: Chinese `zh-Hans`/`zh-Hans-CN` remain separate,
and underscore-region spellings can retain distinct physical bundles. Original
Hebrew `iw`/`iw-IL` null and missing target fixtures accept modern `he`/`he-IL`
requests while preserving the deprecated source spelling, canonical
`he.lproj`/`he-IL.lproj`, complete `one`/`two`/`other`, source-owned hidden
`%3$n`, protected/unrelated entries, translated states, and exact UTF-8/UTF-16
source bytes. Pinned CLDR resolves only native-proven deprecated language
aliases; actual Hebrew Foundation formatting adds U+2068/U+2069 directional
isolates around embedded English arguments. Every demonstrated compiler-losing
alias pair fails closed as `DUPLICATE_LOCALE`; broader Unicode minimization
and unaudited `kok-Latn` are never guessed.

Unicode-equivalent regional separators are not necessarily the same physical
Apple resource. Real Xcode creates distinct `pt_BR.lproj`/`pt-BR.lproj`,
`en_US.lproj`/`en-US.lproj`, and `sr_RS.lproj`/`sr-RS.lproj`; Swift Foundation
lists both and explicit resource lookup addresses each independently. Original
Portuguese fixtures retain both metadata identities, expose one source-owned
UTF-8/UTF-16 value slot per requested separator, preserve the opposite
regional value plus protected/German entries, and verify both original and
translated bundles with real Foundation. The previous `fr_CA`/`fr-CA`
"duplicate" case is now correctly accepted with two native snapshots. Script
separator handling is independently compiler-derived: Chinese
`zh_Hans_CN`/`zh_CN`, Serbian `sr_Cyrl_RS`/`sr_RS`, Azerbaijani
`az_Latn_AZ`/`az_AZ`, and deprecated `iw_IL`/`he_IL` genuinely collapse and
still fail closed. Normalize separators for CLDR only, never for deciding
physical bundle identity or source-slot ownership.

Regenerate the independent native snapshots and shared UTF-8/UTF-16 cases with
`python3 file-formats/conformance/generate_apple_xcstrings_target_device_fixtures.py`.
Regenerate missing/null device insertion fixtures with
`python3 file-formats/conformance/generate_apple_xcstrings_missing_target_device_fixtures.py`.
Regenerate independently owned existing target substitution fixtures with
`python3 file-formats/conformance/generate_apple_xcstrings_target_substitution_fixtures.py`.
Regenerate missing/null scalar/device substitution insertion fixtures with
`python3 file-formats/conformance/generate_apple_xcstrings_missing_target_substitution_fixtures.py`.
Regenerate first-locale missing/null Russian CLDR insertion fixtures with
`python3 file-formats/conformance/generate_apple_xcstrings_first_locale_fixtures.py`.
Regenerate first-locale missing/null scalar/plural device fixtures with
`python3 file-formats/conformance/generate_apple_xcstrings_first_locale_device_fixtures.py`.
Regenerate first-locale missing/null scalar/device substitution fixtures with
`python3 file-formats/conformance/generate_apple_xcstrings_first_locale_substitution_fixtures.py`.
Regenerate distinct Brazilian/European Portuguese first-locale fixtures with
`python3 file-formats/conformance/generate_apple_xcstrings_regional_locale_fixtures.py`.
Regenerate native script identity/collision snapshots and shared UTF-8/UTF-16 cases with
`python3 file-formats/conformance/generate_apple_xcstrings_script_locale_fixtures.py`.
Regenerate deprecated Hebrew ownership and bidirectional Foundation snapshots with
`python3 file-formats/conformance/generate_apple_xcstrings_deprecated_locale_fixtures.py`.
Regenerate independent underscore/hyphen regional bundle snapshots and source slots with
`python3 file-formats/conformance/generate_apple_xcstrings_region_separator_fixtures.py`.
Regenerate opaque known/future source and target review-state fixtures with
`python3 file-formats/conformance/generate_apple_xcstrings_review_state_fixtures.py`.

`propertiesNormalized` likewise supplies a deterministic, language-neutral
UTF-8 Java `.properties` snapshot. Java and Rust must match it byte-for-byte,
reparse it into the identical canonical catalog even when the source used
ISO-8859-1, and remain idempotent. The independent JDK oracle loads the original
and normalized sources with their actual selected encodings and requires equal
`propertiesCompiled` dictionaries. Cases cover ASCII-only whitespace, CR-only
natural lines, noncontinuable comments, literal versus escaped percentages,
printf `%n`/positional `%2$n` mixed with literal newlines, source placeholders,
Unicode line separators, supplementary scalars, comments, JDK-equivalent native
values, and explicitly safer portable control escaping.

`gettextNormalized` supplies a deterministic UTF-8 GNU PO snapshot. Both native
writers preserve locale/plural headers, translator and extracted comments,
references, both native flag spellings, project-owned flags, effective domains,
previous context/singular/plural history, original source IDs, indexed plural
formulas, fuzzy entries, untranslated singular/plural state, formatter
spelling, UTF-8 byte escapes, and Unicode scalar ordering. GNU-verified lexical
fixtures cover tabs, adjacent directives/quoted fragments, spaced indexes,
physical C continuations, and explicit rejection of silently truncated NULs.
The canonical JSON must reparse
identically; GNU `msgfmt --use-fuzzy --check-format` recompiles both source and
normalized output, and the independently decoded binary MO catalogs must
match. Plural-form headers additionally pass GNU's strict header validation.
`gettextLossyCompiled` snapshots prove exactly which source/context/translation
bytes or complete catalogs GNU silently drops; `gettextNativeDomains` runs the
actual `msgcat` lexer against original and normalized domain declarations.

`gettextDomainCompiled` separately compiles all effective GNU domains without
the lossy `msgfmt -o single.mo` override and snapshots every real native MO
dictionary, domain-owned `Language` header, and independent `Plural-Forms`
formula. `gettextSingleOutput: "reject"` proves that an otherwise valid
multi-domain fixture really does fail if its duplicate identities are forced
into a single catalog. `gettextDomainRuntimeSamples` executes actual GNU
`ngettext()` plural selection for each compiled domain, then checks the same
arguments and rendered translations in independent FormatJS and Java ICU
runtimes. Colliding canonical IDs gain a reversible UTF-8 percent-encoded
`@domain=` suffix and retain `gettextOriginalId`; unique IDs stay unchanged.
Implicit and explicitly declared `messages` are one domain, explicitly empty
domains stay distinct, and mixed-locale entries retain their own typed
`gettextDomainHeader` rather than inheriting a misleading global locale.
Each header's ordered `fields` array also preserves standard project ownership,
dates, translator/team contacts, MIME/transfer settings, duplicate custom
fields, mixed case, Unicode/empty field names, empty values, and folded native
continuations. Duplicate `Language` fields keep GNU's final effective locale.
Normalized output regenerates the correct complete domain header and original
native IDs for every domain. Unsafe whitespace/path domains, native-ID
collisions, forged reserved headers, colon/CR/NUL injection, unsafe folded
continuations, and inconsistent domain headers are stable cross-language
errors.

When a format quirk is discovered:

1. Create an original, neutral source fixture; never copy platform examples.
2. Verify its platform behavior against the corresponding native tool.
3. Add expected canonical JSON, stable diagnostics, and optional native snapshots.
4. Run Java and Rust, then implement the same observed behavior independently.
5. Rerun both implementations and every applicable platform oracle.

Source extraction fixtures cover semantic normalization. Opt-in Android and
Apple strings/stringsdict/xcstrings/properties/gettext writer fixtures
additionally require byte-for-byte normalized output; multi-file Android
overlays have their own executable contract. Standalone source-preserving
Android, Apple strings/XML plists/plural stringsdicts, Xcode String Catalog, GNU
gettext, and Java properties skeletons also have executable contracts;
production filter integration, source-set ownership, ambiguous inline
reordering, and migration remain separate. Xcode-rejected plural/substitution
combinations are explicitly documented as invalid platform input.

See `format-syntax-inventory.md` for non-Android behavior and explicit platform
policy differences.
