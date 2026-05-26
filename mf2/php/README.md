# Mojito MF2 PHP

This is a native PHP parser/formatter package for the shared MF2 foundation work.
It uses Composer metadata with `Mojito\MessageFormat2` PSR-4 autoloading and
PHP's built-in `intl` extension for NFC selection-key checks required by the
Unicode MessageFormat 2 suite. CLDR plural rules are generated into
`src/CldrPluralRules.php`; the formatter does not read the shared plural JSON
data at format time.
Parser/formatter helper functions, locale-key logic, and generated CLDR helpers
live under `Mojito\MessageFormat2\Internal` and are not part of the stable
consumer API.

`src/bootstrap.php` remains as a zero-setup local fallback for repository tools,
but Composer autoloading is the package boundary for consumers.

The stable public API uses package functions and PHP class names that match the
other runtimes: `parse_to_model`, `format_message`,
`format_message_to_parts`, `FunctionRegistry::defaults()`,
`FunctionRegistry::portable()`, and `MF2Error`.
`format_message` returns `value`, `errors`, `ok`, and `hasErrors`;
`format_message_to_parts` returns `parts`, `errors`, `ok`, and `hasErrors`.
Formatting uses Unicode MF2 visible fallback values by default. The options
array accepts `onMissingArgument` and `onFormatError` callables to replace local
recoverable values.

`FunctionRegistry::defaults()` is the normal PHP app registry and currently
matches `FunctionRegistry::portable()`: dependency-free handlers for `:string`,
`:offset`, unlocalized numeric formatting for `:number`, `:integer`, and
`:percent`, plus numeric selectors and CLDR plural matching. Unsupported
functions recover with visible MF2 fallback output and collected diagnostics. A
future Intl-backed adapter can provide locale-pretty platform formatting without
changing the core registry boundary.

Regenerate the vendored plural rules:

```sh
sh ../cldr/update_generated.sh
```

Run checks:

```sh
php tests/conformance.php
php tests/unicode_tests.php
php examples/demo.php
php bench.php
```
