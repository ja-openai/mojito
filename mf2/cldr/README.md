# MF2 CLDR Data Generation

This subproject owns generated locale data used by MF2 runtimes. It starts with
plural rules because plural data is small enough to ship selectively and is
updated independently from parser/runtime code.

Generate plural rule implementations for every locale present in CLDR
supplemental plural data:

```sh
python3 generator/generate_plural_rules.py
```

Generate a custom locale subset for an embedded or product-specific build:

```sh
python3 generator/generate_plural_rules.py \
  --locales en,fr,ru,ar,ja \
  --targets java \
  --java-source-root \
  --out /tmp/mf2-plurals-custom
```

The checked-in runtime data is:

- `generated/all`: every locale present in CLDR supplemental plural data. This
  is the default runtime data used by Rust, Swift, Python, and Java.

Current size smoke results from CLDR `main` on 2026-05-19:

- all CLDR plural locales: JSON ~125 KB, Python ~116 KB, Rust ~62 KB, Swift
  ~81 KB, Java ~71 KB

Locale filtering remains a generator capability, not a first-class checked-in
artifact. For embedded clients, generate a product locale allowlist in the
client build and validate it against the same conformance/ICU comparison tools.

The generator emits:

- `plural_rules.json`: compact shared rule data and metadata
- `python/plural_rules.py`: Python evaluator and generated data
- `rust/plural_rules.rs`: Rust evaluator and generated data
- `swift/PluralRules.swift`: Swift evaluator and generated data
- `java/com/box/l10n/mojito/mf2/GeneratedPluralRules.java`: Java evaluator
  and generated data

Use `--targets` to emit only the files a language build needs. For example,
Java's Maven build runs the shared generator with
`--targets java --java-source-root --quiet` into `target/generated-sources`, and
`--java-package` controls the generated package name.

The generated evaluators intentionally depend on each runtime's tiny
`LocaleKey` helper for canonicalization and structural lookup. The generated
part owns plural rule data and plural-specific parent maps; `LocaleKey` owns the
shared string algorithm and remains independent of parser/runtime formatting.

Locale IDs in generated data are canonical BCP47-style keys such as `pt-PT`,
not CLDR underscore keys. The generated evaluators accept underscore input for
compatibility, strip extensions such as `u-nu-latn` for plural lookup, and walk
the structural fallback chain. The `parents` maps are generated only from
plural-specific parent locale data. CLDR's general resource `parentLocales`
rules are intentionally not applied to plural selection; ICU4J comparison probes
cover cases like `pt-AO`, `sr-Latn`, `az-Arab`, and Unicode extensions.

The current Rust runtime compiles the generated all-locale file by path. The
Java Maven project runs the generator into `target/generated-sources` during
`generate-sources`. The Python and Swift runtime starters vendor the all-locale
generated file into their package trees so they remain installable without
reaching outside the package.

Validate generated all-locale cardinal and ordinal category selection against
ICU4J `PluralRules`:

```sh
sh validate_plural_rules.sh
```

This compares category keywords only, not formatted message output. Number
formatting and localized decimal separators belong in later number-formatting
tests.

Keep full number/date/calendar CLDR data out of this subproject until needed.
Plural rules are small; full locale formatting data is not.

The generator currently sets compact decimal operands `c`/`e` to zero. That is
correct for ordinary numeric arguments but not enough for compact-decimal
selection semantics; wire runtime number formatting into operands before relying
on compact exponent rules.
