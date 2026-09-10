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
functions recover with visible MF2 fallback output and collected diagnostics.

`IntlFunctions::registry()` is the explicit PHP Intl adapter. It starts from the
portable registry and overrides `:number`, `:integer`, `:percent`, `:currency`,
`:date`, `:time`, and `:datetime` with locale-pretty handlers backed by
`NumberFormatter` and `IntlDateFormatter`. Date/time formatting accepts
`dateStyle`, `timeStyle`, and `timeZone`, with legacy `length`, `precision`,
`dateLength`, `timePrecision`, and shared `style` aliases retained:

```php
use Mojito\MessageFormat2\IntlFunctions;
use function Mojito\MessageFormat2\format_message;

$result = format_message($model, $arguments, [
    'locale' => 'fr-FR',
    'functions' => IntlFunctions::registry(),
]);
```

PHP currently keeps `:relativeTime` out of production registries because the
local/current Intl extension exposes `NumberFormatter` and `IntlDateFormatter`
but not `IntlRelativeTimeFormatter`. Add a dedicated CLDR or future Intl
adapter instead of faking relative-time output in portable/default registries.

Regenerate the vendored plural rules:

```sh
sh ../cldr/update_generated.sh
```

Run checks:

```sh
php tests/conformance.php
php tests/intl_functions.php
php tests/unicode_tests.php
php examples/demo.php
php examples/intl_demo.php
php bench.php
```

## Runtime bounds and model ownership

Portable numeric fraction options accept integers from 0 through 1000. A minimum
larger than an explicit maximum returns `bad-option`. Offset decimal expansion is
limited to 4096 digits and out-of-range operands return `bad-operand`. Plural
category selection rejects operands outside the supported integer/fraction range;
exact-key selection does not require a CLDR category.

Compiled models are checked for required semantic fields, discriminator types,
and literal-or-present attributes before formatting. Unknown extension fields are
ignored by formatting. Returned parts do not share mutable semantic attributes or
options with the input model. Markup options retain model references for callers
that resolve them in their rendering layer.

The package includes `LICENSE`, `NOTICE`, and `UNICODE-LICENSE.txt`; JVM JARs place
these notices in `META-INF`.

`u:dir` accepts `ltr`, `rtl`, `auto`, and `inherit`, including resolved string variables. Invalid
values report `bad-option` and preserve the formatted value. The option is hidden from formatter
callback option resolution. A plain alias retains isolation; reannotation defaults to `inherit`.
Built-in portable and Intl registries memoize literal-only numeric histories within each format call,
including exact decimal offsets and at most 64 inherited option lookups per source. Variable-option
histories and customized registries remain uncached, preserving callback and copied-source behavior.
Numeric LTR direction survives reannotation without adding inferred direction to public parts.

Portable `:integer` truncates bounded decimal text directly, including magnitudes above the native
signed integer range. Exact matching keeps that value; plural categories outside the supported
operand range return `bad-selector` and choose fallback. Native floats retain their shortest
round-trippable decimal value. Offset options must fit a native signed integer, and subtracting
its minimum value is rejected with `bad-option` because the positive delta is not representable.
