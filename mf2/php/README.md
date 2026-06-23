# Mojito MF2 PHP

This is a native PHP parser/formatter package for the shared MF2 foundation work.
It uses Composer metadata with `Mojito\MessageFormat2` PSR-4 autoloading and
PHP's built-in `intl` extension for NFC selection-key checks required by the
Unicode MessageFormat 2 suite. CLDR plural, experimental number, and
experimental date/time data are generated into PHP source files under `src/`;
relative-time stays data-explicit so callers choose which generated CLDR payload
to decode and ship.
Parser/formatter helper functions, locale-key logic, and generated CLDR helpers
live under `Mojito\MessageFormat2\Internal` and are not part of the stable
consumer API.

`src/bootstrap.php` remains as a zero-setup local fallback for repository tools,
but Composer autoloading is the package boundary for consumers.

The stable public API uses package functions and PHP class names that match the
other runtimes: `parse_to_model`, `format_message`,
`format_message_to_parts`, `FunctionRegistry::defaults()`,
`FunctionRegistry::portable()`, `NumberCore`, `DateTimeCore`,
`RelativeTimeCore`, and `MF2Error`.
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

PHP keeps `:relativeTime` out of portable/default/Intl registries because the
local/current Intl extension exposes `NumberFormatter` and `IntlDateFormatter`
but not `IntlRelativeTimeFormatter`. `RelativeTimeCore` is the explicit CLDR
adapter for that gap.

`NumberCore` and `DateTimeCore` are experimental generated-data formatters for
the CLDR probe locale set. They are explicit opt-in modules, not default
registry behavior. They expose direct `format*` and `format*ToParts` helpers.
`NumberCore::registry()` overrides `:number`, `:integer`,
`:percent`, and `:currency` with generated number symbols, grouping patterns,
currency fractions, and decimal numbering-system digits. `DateTimeCore::registry()`
overrides `:date`, `:time`, and `:datetime` with generated Gregorian
style-pattern and semantic CLDR skeleton data with `hourCycle` overrides and
UTC/fixed-offset `timeZone` values. `RelativeTimeCore::create($data)` prepares
explicit generated relative-time JSON once and provides direct formatting plus
parts formatting plus a `:relativeTime` registry adapter.

```php
use Mojito\MessageFormat2\DateTimeCore;
use Mojito\MessageFormat2\NumberCore;
use Mojito\MessageFormat2\RelativeTimeCore;

echo NumberCore::format(1234.5, ['locale' => 'fr-FR']);
var_export(NumberCore::formatToParts(1234.5, ['locale' => 'fr-FR']));
echo DateTimeCore::formatDateTime('2026-05-21T14:30:15Z', [
    'locale' => 'de-DE',
    'dateStyle' => 'full',
    'timeStyle' => 'medium',
]);

$relativeTimeData = json_decode(file_get_contents('../cldr/generated/relative-time/all/relative_time.json'), true);
$relativeTime = RelativeTimeCore::create($relativeTimeData);
echo $relativeTime->formatValue(-90, ['locale' => 'en', 'style' => 'narrow']);
```

Regenerate the vendored plural rules:

```sh
sh ../cldr/update_generated.sh
```

Regenerate and vendor the experimental generated-data number/date-time cores:

```sh
python3 ../cldr/generator/generate_number_data.py \
  --out ../cldr/generated/experimental-number \
  --php-runtime-out src/CldrNumberData.php \
  --clean

python3 ../cldr/generator/generate_datetime_data.py \
  --out ../cldr/generated/experimental-datetime \
  --php-runtime-out src/CldrDateTimeData.php \
  --clean
```

Run checks:

```sh
php tests/conformance.php
php tests/intl_functions.php
php tests/unicode_tests.php
php examples/demo.php
php examples/intl_demo.php
php bench.php
php bench.php --number-core ../conformance/fixtures/number-core/cases.json
php bench.php --date-time-core ../conformance/fixtures/date-time-core/cases.json
php bench.php --relative-time-core ../conformance/fixtures/functions/relative-time-duration-v0.json
```
