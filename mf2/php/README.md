# Mojito MF2 PHP

This is a native PHP parser/runtime starter for the shared MF2 foundation work.
It has no Composer dependency; it uses PHP's built-in `intl` extension for NFC
selection-key checks required by the Unicode MessageFormat 2 suite. CLDR plural
rules are generated into `src/GeneratedPluralRules.php`; the runtime does not
read the shared plural JSON data at format time.

Regenerate the vendored plural rules:

```sh
python3 ../cldr/generator/generate_plural_rules.py \
  --targets php \
  --out src \
  --php-source-root \
  --quiet
```

Run checks:

```sh
php tests/conformance.php
php tests/unicode_tests.php
php examples/demo.php
php bench.php
```
