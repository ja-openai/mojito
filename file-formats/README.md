# Portable localization file formats

This directory owns Mojito's implementation-independent localization resource
contract. Java and Rust convert native resource files into the same JSON catalog
and are checked against the exact same fixtures.

The canonical catalog deliberately keeps FormatJS-compatible message
descriptors:

```json
{
  "schemaVersion": 1,
  "sourceFormat": "android",
  "messages": {
    "welcome": {
      "defaultMessage": "Welcome, {name}!",
      "description": "Greeting on the home screen",
      "placeholders": [
        { "name": "name", "source": "%1$s", "kind": "string", "position": 1 }
      ]
    }
  }
}
```

`messages` can be consumed directly as a FormatJS descriptor map. The optional
`variants`, `placeholders`, and `metadata` fields retain information that would
otherwise disappear during normalization. Catalog `schemaVersion` makes future
contract changes explicit rather than coupling the Java and Rust parsers.

Android's actual AAPT2 currently collapses ASCII whitespace only. Contrary to
the Android string-resource documentation, XML-decoded and `\\u`-escaped
nonbreaking, em, figure, punctuation, line/paragraph, ideographic, and other
Unicode separator characters remain visible. Original neutral compiler
snapshots cover 49 combinations, and UTF-8/UTF-16 source templates preserve
all original spellings while independently injecting translated values.

Foundation disables its native printf `%n` conversion: `North%nSouth` renders
as `NorthSouth`, unlike Java/Android's actual newline conversion. Apple
descriptors therefore expose the real Foundation-visible text to FormatJS and
retain ordered `appleDisabledPrintfConversions` metadata containing each
Unicode-scalar position and original `%n`/`%2$n` spelling. Independent native
writers and OpenStep, XML, and binary source templates restore those exact
zero-width conversions without converting real newlines or escaped `%%n`.
Foundation plural dictionaries additionally keep
`applePluralDisabledPrintfConversions` per independent selector and plural
category. XML and real binary `.stringsdict` templates restore each branch's
original repeated `%n` or escaped `%%n` independently while FormatJS and real
Foundation continue to select identical visible plural text.
Standalone `.stringsdict` device and width rules own the selected scalar's
ordered conversions directly. Existing source templates preserve every
non-selected branch untouched. Opt-in Java
`LocalizationFileConverters.extractSkeletonWithAppleVariations(bytes)` and Rust
`extract_skeleton_with_apple_variations(bytes)` additionally expose **every**
device and width branch as an independently translatable version-one slot:
`message#@device#mac`, `message#@device#iphone`, and
`message#@width#040`. Each branch retains its own native placeholders,
zero-width conversions, escaped literal percentages, physical newlines, and
original padded width-key spelling. The actual Foundation device and
presentation-width APIs verify independently translated Mac values,
below-minimum fallback, exact/intermediate thresholds, and translated narrow,
middle, and widest alternatives. Existing selected-only skeletons and canonical
FormatJS catalogs remain backward compatible.
Foundation device branches may themselves contain full plural dictionaries.
Both independent parsers preserve those Xcode-generated native trees, both
normalized writers regenerate them, and opt-in templates independently own
`message#@device=iphone#one` and `message#@device=mac#other` without modifying
any other source byte. Actual Mac Foundation singular/plural selections verify
original and localized UTF-8 and UTF-16 resources.
Device branches can also own nested width rules. Opt-in sidecars expose
`message#@device=mac#040`, preserve padded thresholds and genuine/escaped `%n`,
and pass real translated Mac presentation-width selection. Reversed width-owned
device dictionaries are rejected because they crash Foundation despite passing
`plutil` validation.
A single device rule can safely mix all three native branch shapes: independent
devices may own a plain fallback string, a full plural dictionary, or a full
width dictionary; plural and width dictionaries can coexist without a scalar.
Java and Rust retain the complete tree under `deviceMixedVariants`, preserve the
selected device's canonical FormatJS descriptor, and independently translate
scalar `message#@device#other`, plural `message#@device=iphone#one`, and width
`message#@device=mac#040` slots. Actual Foundation validates all original and
translated Mac selections in both source encodings.
Modern Xcode `.xcstrings` resources use the same scalar and category-scoped
ownership, including root-owned `%#@selector@` substitution definitions shared
across device branches. Opt-in Java
`LocalizationFileConverters.extractSkeletonWithXcodeDevices(bytes)` and Rust
`extract_skeleton_with_xcode_devices(bytes)` additionally expose every
source-language iPhone/Mac/watch/other device branch as its own
`message#@device#mac` slot. Nested device-plus-plural branches additionally own
`message#@device=mac#one` and `message#@device=iphone#other` independently;
shared plural substitutions retain `message#selector#category` identities.
Missing or repeated references still fail closed separately for each device.
Apple's real compiler and Foundation runtime verify translated current-Mac
scalar and singular/plural branches, genuine `%n` versus escaped `%%n`,
physical newlines, root-owned plural selections, supplementary Unicode,
protected target locales, review states, UTF-16 offsets, and original JSON
spelling. Existing selected-device templates remain unchanged unless the
all-device API is used.
Apple's compiler rejects substitution references inside top-level or
device-nested plural categories in every locale; original neutral fixtures
assert the actual native diagnostic and both implementations fail closed.

YAML source templates retain safe plain scalar spelling, but quote translated
values whenever YAML would reinterpret them as booleans, nulls, numbers,
timestamps, collections, comments, directives, aliases, or tags. The
`useFullKeyPath=false` workflow uses the customized Okapi leaf identity for
both extraction and rendering; duplicate leaf names fail explicitly because a
single catalog key cannot identify both values. This also rejects repeated
sequence leaves for which Okapi invents order-dependent `tuN` names. Block
translations normalize every YAML line-break form before indentation and fall
back to escaped double-quoted scalars for forbidden control characters. Both
runtimes reject nesting beyond 64 containers, and structural cleanup preserves
indexed translations when earlier sequence entries are removed.
JavaScript and TypeScript templates encode literal backslashes and allow only
exact source-authored identifier/member-reference `${...}` expressions to
remain executable in translated backtick strings. Their exact source escape
spellings stay distinct in the catalog, so translations can safely reorder
otherwise similar bracket-member expressions. More complex code is rendered as
literal text.
HTML attribute translations escape both source quote delimiters. PyYAML, an
HTML parser, and Node runtime checks verify the resulting values independently
of Java and Rust.

Supported initial formats are Android XML, Apple `.strings`, Apple
`.stringsdict`, modern Apple `.xcstrings`, gettext PO/POT, Java properties,
and existing FormatJS JSON.
XLIFF is intentionally deferred: it is a bilingual interchange and skeleton
format, not merely a source-resource syntax, so implementing it before defining
inline-code, segmentation, target-state, and round-trip contracts would give a
misleading parity claim.

Android resource overlays have their own version-one source-preserving sidecar:
`conformance/android-overlay-source-skeleton.schema.json`. It keeps every
original Gradle source-set file in input order, assigns translation slots only
to winning native declarations, and leaves overridden scalar, product, array,
plural, and nontranslatable source bytes untouched. Original and localized
`default`/`tablet` APKs and explicitly selected `tablet`, `default`, `watch`,
and `default,tablet` builds are independently linked by Google's actual AAPT2.
Selected builds record complete runtime-to-original-source slot ownership,
including product-qualified array and plural declarations; unknown/shadowed
slots, incomplete ownership, and invalid product lists fail closed.
Cross-file build macros additionally retain exact winning definition provenance
and package context while preserving every definition file byte-for-byte;
styled/protected expansions, definition-scoped aliases, transitive references,
arrays, plurals, and selected products are linked in their original and
translated APKs.

Binary Foundation `.strings` and `.stringsdict` resources use the same
version-one source sidecar with `encoding: "BINARY_PLIST"` and lowercase
hex-encoded original bytes. Ordinary slots own complete binary string objects;
shared slots instead own their parent value-reference bytes and carry the
original `appleObjectIndex`. Independent Java and Rust writers replace unique
objects or append private copy-on-write clones while preserving every protected
alias, dictionary key, original object, and typed metadata value. Offset tables
and, when the clone count reaches 256 objects, every dictionary/array reference
width are rebuilt safely. Foundation snapshots and actual bundle runtime
selections verify original and independently localized plural/value aliases.

See `conformance/README.md` for the cross-language fixture contract and
`dev-docs/design/024-portable-localization-file-formats.md` for parser policy,
security boundaries, and migration sequencing.

Run the contract, official Android compiler, Apple Foundation parser, GNU
gettext compiler, JDK properties parser, Java suite, and Rust suite together:

```sh
python3 file-formats/conformance/run.py --offline
```
