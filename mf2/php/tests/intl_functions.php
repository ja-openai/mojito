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

assert_intl_locale_bad_option(
    'malformed locale number errors',
    'number={$amount :number}',
    ['amount' => 1],
    'bad locale ???',
);
assert_intl_locale_bad_option(
    'unknown locale number errors',
    'number={$amount :number}',
    ['amount' => 1],
    'zz-ZZ',
);
assert_intl_locale_bad_option(
    'malformed locale date errors',
    'date={$instant :date dateStyle=medium timeZone=UTC}',
    ['instant' => '2026-05-21'],
    'bad locale ???',
);
assert_intl_locale_bad_option(
    'oversized locale date errors',
    'date={$instant :date dateStyle=medium timeZone=UTC}',
    ['instant' => '2026-05-21'],
    str_repeat('a', 257),
);

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

$oversizedDigits = parse_to_model('number={$amount :number minimumFractionDigits=10000}')['model'];
$oversizedDigitsOutput = format_message($oversizedDigits, ['amount' => 1], [
    'functions' => IntlFunctions::registry(),
]);
assert_error_codes('oversized fraction digits errors', $oversizedDigitsOutput['errors'], ['bad-option']);

assert_intl_numeric_bad_operand('Intl adapter rejects leading plus numeric operands', '+1');
assert_intl_numeric_bad_operand('Intl adapter rejects leading whitespace numeric operands', ' 1');
assert_intl_numeric_bad_operand('Intl adapter rejects trailing whitespace numeric operands', '1 ');
assert_intl_numeric_bad_operand('Intl adapter rejects leading-dot numeric operands', '.5');
assert_intl_numeric_bad_operand('Intl adapter rejects trailing-dot numeric operands', '1.');
assert_intl_numeric_bad_operand('Intl adapter rejects leading-zero numeric operands', '01');

$inheritedCurrency = parse_to_model(".local \$price = {\$amount :currency currency=\$currency}\n{{{\$price :currency}}}")['model'];
$inheritedCurrencyOutput = format_message($inheritedCurrency, ['amount' => 12.3, 'currency' => 'EUR'], [
    'locale' => 'en-US',
    'functions' => IntlFunctions::registry(),
    'bidiIsolation' => 'none',
]);
assert_error_codes('inherited currency Intl adapter errors', $inheritedCurrencyOutput['errors'], []);
assert_same('inherited currency Intl adapter output', expected_currency('en-US', 12.3, 'EUR'), $inheritedCurrencyOutput['value']);

$invalidCurrentCurrency = parse_to_model(".local \$price = {\$amount :currency currency=USD}\n{{{\$price :currency currency=||}}}")['model'];
$invalidCurrentCurrencyOutput = format_message($invalidCurrentCurrency, ['amount' => 12.3], [
    'locale' => 'en-US',
    'functions' => IntlFunctions::registry(),
    'bidiIsolation' => 'none',
]);
assert_error_codes('invalid current currency Intl adapter errors', $invalidCurrentCurrencyOutput['errors'], ['bad-option']);

$inheritedDate = parse_to_model(".local \$date = {\$instant :date dateStyle=full timeZone=UTC}\n{{{\$date :date dateStyle=short timeZone=UTC}}}")['model'];
$inheritedDateOutput = format_message($inheritedDate, ['instant' => '2026-05-21T14:30:15Z'], [
    'locale' => 'fr-FR',
    'functions' => IntlFunctions::registry(),
    'bidiIsolation' => 'none',
]);
assert_error_codes('inherited date Intl adapter errors', $inheritedDateOutput['errors'], []);
assert_same('inherited date Intl adapter output', expected_date('fr-FR', '2026-05-21T14:30:15Z', IntlDateFormatter::SHORT, IntlDateFormatter::NONE), $inheritedDateOutput['value']);

assert_intl_date_bad_operand(
    'Intl adapter rejects unpadded date strings',
    'date={$instant :date dateStyle=medium timeZone=UTC}',
    '2020-1-2',
);
assert_intl_date_bad_operand(
    'Intl adapter rejects impossible dates',
    'date={$instant :date dateStyle=medium timeZone=UTC}',
    '2020-02-30',
);
assert_intl_date_bad_operand(
    'Intl adapter rejects impossible datetimes',
    'datetime={$instant :datetime dateStyle=medium timeStyle=medium timeZone=UTC}',
    '2020-02-30T03:04:05Z',
);
assert_intl_date_bad_operand(
    'Intl adapter rejects out-of-range datetime offsets',
    'datetime={$instant :datetime dateStyle=medium timeStyle=medium timeZone=UTC}',
    '2020-01-02T03:04:05+18:01',
);

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

function assert_intl_date_bad_operand(string $label, string $source, string $instant): void
{
    $output = format_message(parse_to_model($source)['model'], ['instant' => $instant], [
        'locale' => 'en-US',
        'functions' => IntlFunctions::registry(),
        'bidiIsolation' => 'none',
    ]);
    assert_error_codes($label, $output['errors'], ['bad-operand']);
}

function assert_intl_numeric_bad_operand(string $label, string $amount): void
{
    $output = format_message(parse_to_model('number={$amount :number}')['model'], ['amount' => $amount], [
        'locale' => 'en-US',
        'functions' => IntlFunctions::registry(),
        'bidiIsolation' => 'none',
    ]);
    assert_error_codes($label, $output['errors'], ['bad-operand']);
}

function assert_intl_locale_bad_option(string $label, string $source, array $arguments, string $locale): void
{
    $output = format_message(parse_to_model($source)['model'], $arguments, [
        'locale' => $locale,
        'functions' => IntlFunctions::registry(),
        'bidiIsolation' => 'none',
    ]);
    assert_error_codes($label, $output['errors'], ['bad-option']);
}

function fail(string $message): never
{
    fwrite(STDERR, $message . "\n");
    exit(1);
}
