<?php

declare(strict_types=1);

use Mojito\MessageFormat2\IntlFunctions;
use function Mojito\MessageFormat2\format_message;
use function Mojito\MessageFormat2\Internal\error_code;
use function Mojito\MessageFormat2\parse_to_model;

require_once __DIR__ . '/../src/bootstrap.php';

$source = implode('; ', [
    'number={$amount :number minimumFractionDigits=2}',
    'percent={$ratio :percent maximumFractionDigits=1}',
    'currency={$price :currency currency=EUR}',
    'date={$due :date dateStyle=full timeZone=UTC}',
    'time={$start :time timeStyle=medium timeZone=UTC}',
    'datetime={$created :datetime dateStyle=medium timeStyle=medium timeZone=UTC}',
]);
$parse = parse_to_model($source);
if ($parse['hasDiagnostics']) {
    fail('unexpected parser diagnostics: ' . json_encode($parse['diagnostics'], JSON_UNESCAPED_UNICODE));
}

$arguments = [
    'amount' => 12345.678,
    'ratio' => 0.1234,
    'price' => 9876.5,
    'due' => '2026-05-21',
    'start' => '2026-05-21T14:30:15Z',
    'created' => new DateTimeImmutable('2026-05-21T14:30:15Z'),
];

foreach (['en-US', 'fr-FR', 'ja-JP', 'ar-EG'] as $locale) {
    $actual = format_message($parse['model'], $arguments, [
        'locale' => $locale,
        'functions' => IntlFunctions::registry(),
    ]);
    assert_error_codes("{$locale} Intl adapter errors", $actual['errors'], []);
    assert_same("{$locale} Intl adapter output", expected_output($locale, $arguments), $actual['value']);
}

$relative = parse_to_model('relative={$days :relativeTime unit=day}')['model'];
$relativeOutput = format_message($relative, ['days' => -1], ['functions' => IntlFunctions::registry()]);
assert_same('relativeTime fallback value', 'relative={$days}', $relativeOutput['value']);
assert_error_codes('relativeTime fallback errors', $relativeOutput['errors'], ['unknown-function']);

$laTime = parse_to_model('time={$start :time timeStyle=short timeZone=America/Los_Angeles}')['model'];
$laTimeOutput = format_message($laTime, ['start' => '2026-05-21T14:30:15Z'], [
    'locale' => 'en-US',
    'functions' => IntlFunctions::registry(),
]);
assert_error_codes('timeZone adapter errors', $laTimeOutput['errors'], []);
assert_same(
    'timeZone adapter output',
    'time=' . expected_date('en-US', '2026-05-21T14:30:15Z', IntlDateFormatter::NONE, IntlDateFormatter::SHORT, 'America/Los_Angeles'),
    $laTimeOutput['value'],
);

$badTimeZone = parse_to_model('time={$start :time timeStyle=short timeZone=No/Such_Zone}')['model'];
$badTimeZoneOutput = format_message($badTimeZone, ['start' => '2026-05-21T14:30:15Z'], [
    'functions' => IntlFunctions::registry(),
]);
assert_error_codes('invalid timeZone errors', $badTimeZoneOutput['errors'], ['bad-option']);

$reannotationCases = [
    [
        'localized number source and inherited options',
        ".local \$n = {1000000 :number minimumFractionDigits=2}\n{{Value {\$n :number}}}",
        'fr-FR',
        'Value ' . expected_number('fr-FR', 1000000, minFractionDigits: 2),
    ],
    [
        'localized currency source and inherited currency',
        ".local \$n = {1234.5 :currency currency=EUR}\n{{Value {\$n :currency}}}",
        'fr-FR',
        'Value ' . expected_currency('fr-FR', 1234.5, 'EUR'),
    ],
    [
        'localized datetime source and replacement timeZone',
        ".local \$d = {|2026-05-21T14:30:15Z| :datetime dateStyle=medium timeStyle=medium timeZone=UTC}\n"
            . "{{Value {\$d :time timeStyle=short timeZone=|America/Los_Angeles|}}}",
        'ja-JP',
        'Value ' . expected_date(
            'ja-JP',
            '2026-05-21T14:30:15Z',
            IntlDateFormatter::NONE,
            IntlDateFormatter::SHORT,
            'America/Los_Angeles',
        ),
    ],
];
foreach ($reannotationCases as [$label, $caseSource, $locale, $expected]) {
    $caseParse = parse_to_model($caseSource);
    assert_same("{$label} diagnostics", [], $caseParse['diagnostics']);
    $actual = format_message($caseParse['model'], [], [
        'locale' => $locale,
        'functions' => IntlFunctions::registry(),
    ]);
    assert_error_codes("{$label} errors", $actual['errors'], []);
    assert_same("{$label} output", $expected, $actual['value']);
}

$currencyBarrierPrefix = ".local \$usd = {42 :currency currency=USD}\n"
    . ".local \$plain = {\$usd :number}\n";
$missingCurrency = parse_to_model($currencyBarrierPrefix . "{{Value {\$plain :currency}}}");
assert_same('currency provenance barrier diagnostics', [], $missingCurrency['diagnostics']);
$missingCurrencyOutput = format_message($missingCurrency['model'], [], [
    'locale' => 'en-US',
    'functions' => IntlFunctions::registry(),
]);
assert_error_codes('currency provenance barrier errors', $missingCurrencyOutput['errors'], ['bad-operand']);

$selectionFixtureRoot = __DIR__ . '/../../reference/fixtures/selection-operands';
$resolvedValueFixtureRoot = __DIR__ . '/../../reference/fixtures/resolved-values';
$checkedSelectionCases = 0;
$fixturePatterns = [
    "{$selectionFixtureRoot}/common/*.json",
    "{$selectionFixtureRoot}/icu4j/*.json",
    "{$selectionFixtureRoot}/adapters/*.json",
    "{$resolvedValueFixtureRoot}/adapters/*.json",
];
foreach ($fixturePatterns as $fixturePattern) {
    $paths = glob($fixturePattern);
    sort($paths, SORT_STRING);
    foreach ($paths as $path) {
        $fixture = json_decode((string) file_get_contents($path), true, flags: JSON_THROW_ON_ERROR);
        $selectionParse = parse_to_model($fixture['source']);
        assert_same("{$fixture['name']} diagnostics", [], $selectionParse['diagnostics']);
        foreach ($fixture['formatCases'] as $case) {
            $actual = format_message($selectionParse['model'], $case['arguments'], [
                'locale' => $case['locale'],
                'functions' => IntlFunctions::registry(),
            ]);
            $label = "{$fixture['name']}/{$case['name']}";
            assert_same("{$label} output", $case['expected'], $actual['value']);
            assert_error_codes("{$label} errors", $actual['errors'], []);
            $checkedSelectionCases += 1;
        }
    }
}
assert_same('adapter differential case count', 47, $checkedSelectionCases);

// Explicit numbering systems keep this regression independent of ICU's locale defaults.
$offsetSource = json_decode((string) file_get_contents("{$selectionFixtureRoot}/adapters/offset-variable.json"), true, flags: JSON_THROW_ON_ERROR)['source'];
foreach (['ar-u-nu-arab', 'ar-u-nu-latn'] as $locale) {
    $actual = format_message(parse_to_model($offsetSource)['model'], ['value' => 10, 'step' => 10.9], [
        'locale' => $locale,
        'functions' => IntlFunctions::registry(),
    ]);
    assert_same("{$locale} numeric variable option output", 'zero', $actual['value']);
    assert_error_codes("{$locale} numeric variable option errors", $actual['errors'], []);
}

$fractionOption = parse_to_model('.input {$digits :integer}' . "\n" . '{{{1.25 :number maximumFractionDigits=$digits}}}')['model'];
$fractionOutput = format_message($fractionOption, ['digits' => 1], [
    'locale' => 'ar-u-nu-arab',
    'functions' => IntlFunctions::registry(),
]);
assert_same('localized fraction option output', expected_number('ar-u-nu-arab', 1.25, maxFractionDigits: 1), $fractionOutput['value']);
assert_error_codes('localized fraction option errors', $fractionOutput['errors'], []);

$minimumOption = parse_to_model('.input {$digits :integer}' . "\n" . '{{{1.2 :number minimumFractionDigits=$digits}}}')['model'];
$actual = format_message($minimumOption, ['digits' => 2], ['locale' => 'ar-u-nu-arab', 'functions' => IntlFunctions::registry()]);
assert_same('localized minimum fraction option output', expected_number('ar-u-nu-arab', 1.2, minFractionDigits: 2), $actual['value']);
assert_error_codes('localized minimum fraction option errors', $actual['errors'], []);

$currencyOption = parse_to_model('.input {$digits :integer}' . "\n" . '{{{1.25 :currency currency=USD fractionDigits=$digits}}}')['model'];
$actual = format_message($currencyOption, ['digits' => 1], ['locale' => 'ar-u-nu-arab', 'functions' => IntlFunctions::registry()]);
$currencyFormatter = new NumberFormatter('ar-u-nu-arab', NumberFormatter::CURRENCY);
$currencyFormatter->setAttribute(NumberFormatter::MIN_FRACTION_DIGITS, 1);
$currencyFormatter->setAttribute(NumberFormatter::MAX_FRACTION_DIGITS, 1);
assert_same('localized currency fraction option output', $currencyFormatter->formatCurrency(1.25, 'USD'), $actual['value']);
assert_error_codes('localized currency fraction option errors', $actual['errors'], []);

$localizedDigits = expected_number('ar-u-nu-arab', 1);
foreach (['withFunction', 'withNumericFunction'] as $registration) {
    $custom = IntlFunctions::registry()->$registration('number', static fn(array $call): string => $call['optionValue']('maximumFractionDigits', 'missing'));
    foreach ([
        '.local $digits = {1 :integer}' . "\n" . '{{{2 :number maximumFractionDigits=$digits}}}',
        '.local $digits = {1 :integer}' . "\n" . '.local $n = {1.25 :percent maximumFractionDigits=$digits}' . "\n" . '{{{$n :number}}}',
    ] as $source) {
        $actual = format_message(parse_to_model($source)['model'], [], ['locale' => 'ar-u-nu-arab', 'functions' => $custom]);
        assert_same('custom numeric option keeps display', $localizedDigits, $actual['value']);
        assert_error_codes('custom numeric option errors', $actual['errors'], []);
    }
}

$customSource = IntlFunctions::registry()->withFunction('replace', static fn(array $call): string => '9.8');
$source = '.local $raw = {1 :replace}' . "\n" . '.local $digits = {$raw :integer}' . "\n" . '{{{10 :offset add=$digits}}}';
$actual = format_message(parse_to_model($source)['model'], [], ['locale' => 'ar-u-nu-arab', 'functions' => $customSource]);
assert_same('localized reannotation respects custom boundary', '19', $actual['value']);
assert_error_codes('localized reannotation errors', $actual['errors'], []);

$customOption = IntlFunctions::registry()->withFunction('probe', static fn(array $call): string => $call['optionValue']('label', 'missing'));
$source = '.local $digits = {1 :integer}' . "\n" . '{{{:probe label=$digits}}}';
$actual = format_message(parse_to_model($source)['model'], [], ['locale' => 'ar-u-nu-arab', 'functions' => $customOption]);
assert_same('custom nonnumeric option keeps display', $localizedDigits, $actual['value']);
assert_error_codes('custom nonnumeric option errors', $actual['errors'], []);

$customSelector = IntlFunctions::registry()->withSelector('number', static fn(array $match): ?int => $match['key'] === 'one' && $match['optionValue']('maximumFractionDigits', null) === $localizedDigits ? 1 : null);
$source = '.input {$digits :integer}' . "\n" . '.input {$n :number maximumFractionDigits=$digits}' . "\n" . '.match $n' . "\n" . 'one {{custom}}' . "\n" . '* {{other}}';
$actual = format_message(parse_to_model($source)['model'], ['digits' => 1, 'n' => 1.25], ['locale' => 'ar-u-nu-arab', 'functions' => $customSelector]);
assert_same('custom selector option keeps display', 'custom', $actual['value']);
assert_error_codes('custom selector option errors', $actual['errors'], []);

echo "PHP Intl function registry tests passed.\n";

function expected_output(string $locale, array $arguments): string
{
    return implode('; ', [
        'number=' . expected_number($locale, $arguments['amount'], minFractionDigits: 2),
        'percent=' . expected_number($locale, $arguments['ratio'], NumberFormatter::PERCENT, maxFractionDigits: 1),
        'currency=' . expected_currency($locale, $arguments['price'], 'EUR'),
        'date=' . expected_date($locale, $arguments['due'], IntlDateFormatter::FULL, IntlDateFormatter::NONE),
        'time=' . expected_date($locale, $arguments['start'], IntlDateFormatter::NONE, IntlDateFormatter::MEDIUM),
        'datetime=' . expected_date($locale, $arguments['created'], IntlDateFormatter::MEDIUM, IntlDateFormatter::MEDIUM),
    ]);
}

function expected_number(
    string $locale,
    float $value,
    int $style = NumberFormatter::DECIMAL,
    ?int $minFractionDigits = null,
    ?int $maxFractionDigits = null,
): string {
    $formatter = new NumberFormatter($locale, $style);
    if ($minFractionDigits !== null) {
        $formatter->setAttribute(NumberFormatter::MIN_FRACTION_DIGITS, $minFractionDigits);
    }
    if ($maxFractionDigits !== null) {
        $formatter->setAttribute(NumberFormatter::MAX_FRACTION_DIGITS, $maxFractionDigits);
    }
    $formatted = $formatter->format($value);
    return $formatted === false ? fail('NumberFormatter failed') : $formatted;
}

function expected_currency(string $locale, float $value, string $currency): string
{
    $formatter = new NumberFormatter($locale, NumberFormatter::CURRENCY);
    $formatted = $formatter->formatCurrency($value, $currency);
    return $formatted === false ? fail('NumberFormatter currency failed') : $formatted;
}

function expected_date(
    string $locale,
    DateTimeInterface|string $value,
    int $dateStyle,
    int $timeStyle,
    string $timeZone = 'UTC',
): string
{
    $formatter = new IntlDateFormatter($locale, $dateStyle, $timeStyle, $timeZone, IntlDateFormatter::GREGORIAN);
    $date = $value instanceof DateTimeInterface ? $value : new DateTimeImmutable($value, new DateTimeZone($timeZone));
    $formatted = $formatter->format($date);
    return $formatted === false ? fail('IntlDateFormatter failed') : $formatted;
}

function assert_same(string $label, mixed $expected, mixed $actual): void
{
    if ($expected !== $actual) {
        fail("{$label}: expected " . json_encode($expected, JSON_UNESCAPED_UNICODE) . ', got ' . json_encode($actual, JSON_UNESCAPED_UNICODE));
    }
}

function assert_error_codes(string $label, array $actualErrors, array $expected): void
{
    $actual = array_map(static fn(Throwable $error): string => error_code($error), $actualErrors);
    sort($actual, SORT_STRING);
    sort($expected, SORT_STRING);
    assert_same($label, $expected, $actual);
}

function fail(string $message): never
{
    fwrite(STDERR, $message . "\n");
    exit(1);
}
