# MF2 CLDR Data Generation

This subproject owns generated locale data used by MF2 runtimes. It starts with
plural rules because plural data is small enough to ship selectively and is
updated independently from parser/runtime code.

Generate plural rule implementations for a target locale set:

```sh
python3 generator/generate_plural_rules.py \
  --locales en,fr,ru,ar,ja \
  --out generated/minimal
```

Generate every locale present in CLDR supplemental plural data:

```sh
python3 generator/generate_plural_rules.py --locales all --out generated/all
```

Both outputs are useful:

- `generated/minimal`: embedded/runtime starter allowlist for `en`, `fr`, `ru`,
  `ar`, and `ja`
- `generated/all`: every locale present in CLDR supplemental plural data, used
  for full coverage work and size/perf comparisons

Current size smoke results from CLDR `main` on 2026-05-19:

- `en,fr,ru,ar,ja`: JSON ~12 KB, Python ~15 KB, Rust ~9 KB, Swift ~10 KB,
  Java ~12 KB
- all CLDR plural locales: JSON ~125 KB, Python ~117 KB, Rust ~63 KB, Swift
  ~82 KB, Java ~74 KB

For embedded clients, start with the product locale allowlist. The all-locale
Rust/Swift generated implementation is still small, but the allowlist keeps app
catalogs and test matrices explicit.

The generator emits:

- `plural_rules.json`: compact shared rule data and metadata
- `python/plural_rules.py`: Python evaluator and generated data
- `rust/plural_rules.rs`: Rust evaluator and generated data
- `swift/PluralRules.swift`: Swift evaluator and generated data
- `java/com/box/l10n/mojito/mf2/GeneratedPluralRules.java`: Java evaluator
  and generated data

Locale IDs in generated data are canonical BCP47-style keys such as `pt-PT`,
not CLDR underscore keys. The generated evaluators accept underscore input for
compatibility, strip extensions such as `u-nu-latn` for plural lookup, and walk
the structural fallback chain. The `parents` maps are generated only from
plural-specific parent locale data. CLDR's general resource `parentLocales`
rules are intentionally not applied to plural selection; ICU4J comparison probes
cover cases like `pt-AO`, `sr-Latn`, `az-Arab`, and Unicode extensions.

The current Rust and Java runtimes compile the generated minimal file by path.
The Python and Swift runtime starters vendor the generated minimal file into
their package trees so they remain installable without reaching outside the
package.

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
