<?php

declare(strict_types=1);

use function Mojito\MessageFormat2\format_message_to_parts;
use function Mojito\MessageFormat2\format_message;
use function Mojito\MessageFormat2\Internal\error_code;
use function Mojito\MessageFormat2\Internal\canonical_locale_key;
use function Mojito\MessageFormat2\Internal\feature_lookup_chain;
use function Mojito\MessageFormat2\Internal\locale_lookup_chain;
use function Mojito\MessageFormat2\parse_to_model;
use Mojito\MessageFormat2\DateTimeCore;
use Mojito\MessageFormat2\FunctionRegistry;
use Mojito\MessageFormat2\IntlFunctions;
use Mojito\MessageFormat2\MF2Error;
use Mojito\MessageFormat2\NumberCore;
use Mojito\MessageFormat2\Internal\NumberOperands;
use Mojito\MessageFormat2\RelativeTimeCore;

require_once __DIR__ . '/../src/bootstrap.php';

final class ThrowingStringValue
{
    public function __toString(): string
    {
        throw new RuntimeException('boom stringify');
    }
}

final class ThrowingMF2StringValue
{
    public function __toString(): string
    {
        throw new MF2Error('host-error', 'boom mf2 stringify');
    }
}

assert_public_api_boundary();

$formatCases = 0;
$partsCases = 0;
$formatErrorCases = 0;
$localeCases = 0;

foreach (fixture_paths(__DIR__ . '/../../conformance/fixtures/source-to-model') as $path) {
    $fixture = read_json($path);
    $parse = parse_to_model($fixture['source']);
    if ($parse['hasDiagnostics']) {
        fail(basename($path) . ': unexpected diagnostics ' . json_encode($parse['diagnostics'], JSON_UNESCAPED_UNICODE));
    }
    assert_json_equal(basename($path) . ': model', $fixture['expectedModel'], $parse['model']);
    foreach ($fixture['formatCases'] ?? [] as $case) {
        $actual = format_message($parse['model'], $case['arguments'] ?? [], [
            'locale' => $case['locale'] ?? 'en',
            'bidiIsolation' => $case['bidiIsolation'] ?? 'none',
        ]);
        assert_same(basename($path) . ': format', $case['expected'], $actual['value']);
        assert_error_codes(basename($path) . ': format errors', $actual['errors'], []);
        $formatCases += 1;
    }
    foreach ($fixture['partsCases'] ?? [] as $case) {
        $actual = format_message_to_parts($parse['model'], $case['arguments'] ?? [], [
            'locale' => $case['locale'] ?? 'en',
            'bidiIsolation' => $case['bidiIsolation'] ?? 'none',
        ]);
        assert_json_equal(basename($path) . ': parts', $case['expected'], $actual['parts']);
        assert_error_codes(basename($path) . ': parts errors', $actual['errors'], []);
        $partsCases += 1;
    }
    foreach ($fixture['fallbackCases'] ?? [] as $case) {
        $actual = format_message($parse['model'], $case['arguments'] ?? [], [
            'locale' => $case['locale'] ?? 'en',
            'bidiIsolation' => $case['bidiIsolation'] ?? 'none',
        ]);
        assert_same(basename($path) . ': fallback value', $case['expected'], $actual['value']);
        assert_error_codes(basename($path) . ': fallback errors', $actual['errors'], $case['expectedErrors'] ?? []);
    }
    foreach ($fixture['fallbackPartsCases'] ?? [] as $case) {
        $actual = format_message_to_parts($parse['model'], $case['arguments'] ?? [], ['locale' => $case['locale'] ?? 'en']);
        assert_json_equal(basename($path) . ': fallback parts', $case['expected'], $actual['parts']);
        assert_error_codes(basename($path) . ': fallback part errors', $actual['errors'], $case['expectedErrors'] ?? []);
    }
}

foreach (fixture_paths(__DIR__ . '/../../conformance/fixtures/invalid-source') as $path) {
    $fixture = read_json($path);
    $parse = parse_to_model($fixture['source']);
    if (!$parse['hasDiagnostics']) {
        fail(basename($path) . ': expected diagnostics');
    }
    $actual = array_map(static fn(array $diagnostic): string => $diagnostic['code'], $parse['diagnostics']);
    assert_contains_all(basename($path) . ': diagnostics', $actual, expected_codes($fixture['expectedDiagnostics'] ?? []));
}

foreach (fixture_paths(__DIR__ . '/../../conformance/fixtures/format-errors') as $path) {
    $fixture = read_json($path);
    $expected = $fixture['expectedError']['code'];
    try {
        $actual = format_message($fixture['model'], $fixture['arguments'] ?? [], ['locale' => $fixture['locale'] ?? 'en']);
        assert_contains_all(basename($path) . ': format error', array_map(static fn($error): string => error_code($error), $actual['errors']), [$expected]);
    } catch (Throwable $error) {
        assert_same(basename($path) . ': format error', $expected, error_code($error));
    }
    $formatErrorCases += 1;
}

$localeFixture = read_json(__DIR__ . '/../../conformance/fixtures/locale-key/cases.json');
foreach (($localeFixture['canonical'] ?? $localeFixture['canonicalCases'] ?? []) as $case) {
    assert_same('canonical locale', $case['expected'], canonical_locale_key($case['source']));
    $localeCases += 1;
}
foreach (($localeFixture['lookupChains'] ?? $localeFixture['lookupCases'] ?? []) as $case) {
    assert_json_equal('lookup chain', $case['expected'], locale_lookup_chain($case['source']));
    $localeCases += 1;
}
foreach ($localeFixture['featureLookupChains'] ?? [] as $case) {
    assert_json_equal('feature lookup chain', $case['expected'], feature_lookup_chain($case['source'], $case['parents'] ?? []));
    $localeCases += 1;
}

$numberCoreCases = assert_number_core_fixtures();
$dateTimeCoreCases = assert_date_time_core_fixtures();
$relativeTimeCoreCases = assert_relative_time_core_fixtures();
$portableRegressionCases = assert_portable_regressions();

echo "PHP MF2 conformance runner passed {$formatCases} format cases, {$partsCases} parts cases, {$formatErrorCases} format error cases, {$localeCases} locale cases, {$numberCoreCases} number-core cases, {$dateTimeCoreCases} date-time-core cases, {$relativeTimeCoreCases} relative-time-core cases, and {$portableRegressionCases} portable regression cases.\n";

function fixture_paths(string $root): array
{
    $paths = glob($root . '/*.json') ?: [];
    sort($paths, SORT_STRING);
    return $paths;
}

function assert_number_core_fixtures(): int
{
    $fixture = read_json(__DIR__ . '/../../conformance/fixtures/number-core/cases.json');
    $checked = 0;
    foreach ($fixture['formatCases'] ?? [] as $item) {
        $actual = NumberCore::format($item['value'], number_core_options($item));
        assert_same($item['name'] . ': number core format', $item['expected'], $actual);
        $checked += 1;
    }

    $referenceCases = $fixture['intlReferenceCases'] ?? [];
    $references = node_intl_number_outputs($referenceCases);
    foreach ($referenceCases as $index => $item) {
        $actual = NumberCore::format($item['value'], number_core_options($item));
        assert_same("Intl number reference {$index}", $references[$index], $actual);
        $checked += 1;
    }

    foreach ($fixture['errorCases'] ?? [] as $item) {
        try {
            NumberCore::format($item['value'], number_core_options($item));
            fail($item['name'] . ': expected number core error');
        } catch (Throwable $error) {
            assert_same($item['name'] . ': number core error', $item['expectedError'], error_code($error));
        }
        $checked += 1;
    }

    $directPartsOptions = ['locale' => 'en-US'];
    assert_json_equal(
        'number-core direct parts',
        [['type' => 'text', 'value' => NumberCore::format(1234.5, $directPartsOptions)]],
        NumberCore::formatToParts(1234.5, $directPartsOptions),
    );
    $checked += 1;

    assert_mf2_error('number-core direct throwing operand', 'bad-operand', static fn(): string => NumberCore::format(new ThrowingStringValue()));
    assert_mf2_error('number-core direct throwing locale option', 'bad-option', static fn(): string => NumberCore::format(1, ['locale' => new ThrowingStringValue()]));
    assert_mf2_error('number-core direct throwing style option', 'bad-option', static fn(): string => NumberCore::format(1, ['style' => new ThrowingStringValue()]));
    assert_mf2_error('number-core direct throwing fraction option', 'bad-option', static fn(): string => NumberCore::format(1, ['minimumFractionDigits' => new ThrowingStringValue()]));
    assert_mf2_error('number-core direct throwing grouping option', 'bad-option', static fn(): string => NumberCore::format(1, ['useGrouping' => new ThrowingStringValue()]));
    assert_mf2_error('number-core direct throwing currency display option', 'bad-option', static fn(): string => NumberCore::format(1, ['style' => 'currency', 'currency' => 'USD', 'currencyDisplay' => new ThrowingStringValue()]));
    $checked += 6;

    $currency = parse_to_model('Total: {$amount :currency currency=USD}');
    if ($currency['hasDiagnostics']) {
        fail('number-core registry parse diagnostics: ' . json_encode($currency['diagnostics'], JSON_UNESCAPED_UNICODE));
    }
    $currencyResult = format_message($currency['model'], ['amount' => 1234.5], [
        'locale' => 'en-US',
        'functions' => NumberCore::registry(),
    ]);
    assert_same('number-core registry currency', 'Total: $1,234.50', $currencyResult['value']);
    assert_error_codes('number-core registry currency errors', $currencyResult['errors'], []);

    $selector = parse_to_model(".input {\$count :number}\n.match \$count\none {{one}}\n* {{other}}");
    if ($selector['hasDiagnostics']) {
        fail('number-core selector parse diagnostics: ' . json_encode($selector['diagnostics'], JSON_UNESCAPED_UNICODE));
    }
    $selectorResult = format_message($selector['model'], ['count' => 1], [
        'locale' => 'en',
        'functions' => NumberCore::registry(),
    ]);
    assert_same('number-core registry selector', 'one', $selectorResult['value']);
    assert_error_codes('number-core registry selector errors', $selectorResult['errors'], []);

    $checked += 2;
    foreach ($fixture['registryCases'] ?? [] as $item) {
        $parsed = parse_to_model($item['source']);
        if ($parsed['hasDiagnostics']) {
            fail($item['name'] . ': parse diagnostics: ' . json_encode($parsed['diagnostics'], JSON_UNESCAPED_UNICODE));
        }
        $result = format_message($parsed['model'], $item['arguments'] ?? [], [
            'locale' => $item['locale'] ?? 'en',
            'functions' => NumberCore::registry(),
        ]);
        assert_same($item['name'] . ': number-core registry', $item['expected'], $result['value']);
        assert_error_codes($item['name'] . ': number-core registry errors', $result['errors'], []);
        $checked += 1;
    }

    foreach ($fixture['registryErrorCases'] ?? [] as $item) {
        $parsed = parse_to_model($item['source']);
        if ($parsed['hasDiagnostics']) {
            fail($item['name'] . ': parse diagnostics: ' . json_encode($parsed['diagnostics'], JSON_UNESCAPED_UNICODE));
        }
        $result = format_message($parsed['model'], $item['arguments'] ?? [], [
            'locale' => $item['locale'] ?? 'en',
            'functions' => NumberCore::registry(),
        ]);
        $actualCodes = array_map(static fn(Throwable $error): string => error_code($error), $result['errors']);
        assert_same($item['name'] . ': number-core registry error codes', $item['expectedErrors'], $actualCodes);
        $checked += 1;
    }
    return $checked;
}

function assert_date_time_core_fixtures(): int
{
    $fixture = read_json(__DIR__ . '/../../conformance/fixtures/date-time-core/cases.json');
    $checked = 0;
    foreach ($fixture['formatCases'] ?? [] as $item) {
        $actual = format_date_time_core_item($item);
        assert_same($item['name'] . ': date-time core format', $item['expected'], $actual);
        $checked += 1;
    }
    foreach ($fixture['numericTimestampCases'] ?? [] as $item) {
        $actual = format_date_time_core_item($item);
        assert_same($item['name'] . ': date-time core numeric timestamp', $item['expected'], $actual);
        $checked += 1;
    }

    $referenceCases = $fixture['intlReferenceCases'] ?? [];
    $references = node_intl_date_time_outputs($referenceCases);
    foreach ($referenceCases as $index => $item) {
        $actual = format_date_time_core_item($item);
        assert_same("Intl date/time reference {$index}", $references[$index], $actual);
        $checked += 1;
    }

    $semanticReferenceCases = $fixture['semanticStyleReferenceCases'] ?? [];
    $semanticReferences = node_intl_date_time_outputs(array_map('date_time_core_reference_item', $semanticReferenceCases));
    foreach ($semanticReferenceCases as $index => $item) {
        $actual = format_date_time_core_item($item);
        assert_same($item['name'] . ': semantic style reference', $semanticReferences[$index], $actual);
        $checked += 1;
    }

    foreach ($fixture['errorCases'] ?? [] as $item) {
        try {
            format_date_time_core_item($item);
            fail($item['name'] . ': expected date-time core error');
        } catch (Throwable $error) {
            assert_same($item['name'] . ': date-time core error', $item['expectedError'], error_code($error));
        }
        $checked += 1;
    }

    $directPartsValue = '2026-05-21T14:30:15Z';
    $directDateOptions = ['locale' => 'en-US', 'dateStyle' => 'short', 'timeZone' => 'UTC'];
    $directTimeOptions = ['locale' => 'en-US', 'timeStyle' => 'short', 'timeZone' => 'UTC'];
    $directDateTimeOptions = [
        'locale' => 'en-US',
        'dateStyle' => 'short',
        'timeStyle' => 'short',
        'timeZone' => 'UTC',
    ];
    assert_json_equal(
        'date-time-core direct date parts',
        [['type' => 'text', 'value' => DateTimeCore::formatDate($directPartsValue, $directDateOptions)]],
        DateTimeCore::formatDateToParts($directPartsValue, $directDateOptions),
    );
    assert_json_equal(
        'date-time-core direct time parts',
        [['type' => 'text', 'value' => DateTimeCore::formatTime($directPartsValue, $directTimeOptions)]],
        DateTimeCore::formatTimeToParts($directPartsValue, $directTimeOptions),
    );
    assert_json_equal(
        'date-time-core direct datetime parts',
        [['type' => 'text', 'value' => DateTimeCore::formatDateTime($directPartsValue, $directDateTimeOptions)]],
        DateTimeCore::formatDateTimeToParts($directPartsValue, $directDateTimeOptions),
    );
    $checked += 3;

    assert_mf2_error('date-time-core direct throwing locale option', 'bad-option', static fn(): string => DateTimeCore::formatDateTime('2026-05-21T14:30:15Z', ['locale' => new ThrowingStringValue()]));
    assert_mf2_error('date-time-core direct throwing skeleton option', 'bad-option', static fn(): string => DateTimeCore::formatDateTime('2026-05-21T14:30:15Z', ['skeleton' => new ThrowingStringValue()]));
    assert_mf2_error('date-time-core direct throwing timeZone option', 'bad-option', static fn(): string => DateTimeCore::formatDateTime('2026-05-21T14:30:15Z', ['timeZone' => new ThrowingStringValue()]));
    assert_mf2_error('date-time-core direct throwing hourCycle option', 'bad-option', static fn(): string => DateTimeCore::formatDateTime('2026-05-21T14:30:15Z', ['hourCycle' => new ThrowingStringValue()]));
    assert_mf2_error('date-time-core direct throwing calendar option', 'bad-option', static fn(): string => DateTimeCore::formatDateTime('2026-05-21T14:30:15Z', ['calendar' => new ThrowingStringValue()]));
    assert_mf2_error('date-time-core direct throwing dateStyle option', 'bad-option', static fn(): string => DateTimeCore::formatDateTime('2026-05-21T14:30:15Z', ['dateStyle' => new ThrowingStringValue()]));
    assert_mf2_error('date-time-core direct throwing timeStyle option', 'bad-option', static fn(): string => DateTimeCore::formatTime('2026-05-21T14:30:15Z', ['timeStyle' => new ThrowingStringValue()]));
    $checked += 7;

    foreach ($fixture['registryFormatCases'] ?? [] as $item) {
        $message = parse_to_model($item['source']);
        if ($message['hasDiagnostics']) {
            fail($item['name'] . ': date-time-core registry parse diagnostics: ' . json_encode($message['diagnostics'], JSON_UNESCAPED_UNICODE));
        }
        $result = format_message($message['model'], $item['arguments'] ?? [], [
            'locale' => $item['locale'],
            'functions' => DateTimeCore::registry(),
        ]);
        assert_same($item['name'] . ': date-time-core registry format', $item['expected'], $result['value']);
        assert_error_codes($item['name'] . ': date-time-core registry errors', $result['errors'], []);
        $checked += 1;
    }

    foreach ($fixture['registryErrorCases'] ?? [] as $item) {
        $message = parse_to_model($item['source']);
        if ($message['hasDiagnostics']) {
            fail($item['name'] . ': date-time-core registry error parse diagnostics: ' . json_encode($message['diagnostics'], JSON_UNESCAPED_UNICODE));
        }
        $result = format_message($message['model'], $item['arguments'] ?? [], [
            'locale' => $item['locale'],
            'functions' => DateTimeCore::registry(),
        ]);
        $actualCodes = array_map(static fn(Throwable $error): string => error_code($error), $result['errors']);
        assert_same($item['name'] . ': date-time-core registry error codes', $item['expectedErrors'], $actualCodes);
        $checked += 1;
    }

    $dateMessage = parse_to_model('At {$instant :datetime dateStyle=full timeStyle=medium timeZone=UTC}');
    if ($dateMessage['hasDiagnostics']) {
        fail('date-time-core registry parse diagnostics: ' . json_encode($dateMessage['diagnostics'], JSON_UNESCAPED_UNICODE));
    }
    $dateResult = format_message($dateMessage['model'], ['instant' => '2026-05-21T14:30:15Z'], [
        'locale' => 'de-DE',
        'functions' => DateTimeCore::registry(),
    ]);
    assert_same('date-time-core registry datetime', 'At Donnerstag, 21. Mai 2026 um 14:30:15', $dateResult['value']);
    assert_error_codes('date-time-core registry datetime errors', $dateResult['errors'], []);

    $stringMessage = parse_to_model('Hello {$name :string}');
    if ($stringMessage['hasDiagnostics']) {
        fail('date-time-core string parse diagnostics: ' . json_encode($stringMessage['diagnostics'], JSON_UNESCAPED_UNICODE));
    }
    $stringResult = format_message($stringMessage['model'], ['name' => 'Mojito'], ['functions' => DateTimeCore::registry()]);
    assert_same('date-time-core registry string', 'Hello Mojito', $stringResult['value']);
    assert_error_codes('date-time-core registry string errors', $stringResult['errors'], []);
    return $checked + 2;
}

function assert_relative_time_core_fixtures(): int
{
    $data = read_json(__DIR__ . '/../../cldr/generated/relative-time/all/relative_time.json');
    $formatter = RelativeTimeCore::create($data);
    $fixture = read_json(__DIR__ . '/../../conformance/fixtures/functions/relative-time-duration-v0.json');
    $checked = 0;

    foreach ($fixture['cases'] ?? [] as $item) {
        $parse = parse_to_model($item['source']);
        if ($parse['hasDiagnostics']) {
            fail($item['label'] . ': relative-time parse diagnostics ' . json_encode($parse['diagnostics'], JSON_UNESCAPED_UNICODE));
        }
        $actual = format_message($parse['model'], $item['arguments'] ?? [], [
            'locale' => $item['locale'] ?? 'en',
            'functions' => $formatter->functionRegistry(),
        ]);
        assert_same($item['label'] . ': relative-time registry format', $item['expected'], $actual['value']);
        assert_error_codes($item['label'] . ': relative-time registry errors', $actual['errors'], []);
        $checked += 1;
    }

    foreach ($fixture['errorCases'] ?? [] as $item) {
        $parse = parse_to_model($item['source']);
        if ($parse['hasDiagnostics']) {
            fail($item['label'] . ': relative-time error parse diagnostics ' . json_encode($parse['diagnostics'], JSON_UNESCAPED_UNICODE));
        }
        $actual = format_message($parse['model'], $item['arguments'] ?? [], [
            'locale' => $item['locale'] ?? 'en',
            'functions' => $formatter->functionRegistry(),
        ]);
        assert_error_codes($item['label'] . ': relative-time error', $actual['errors'], [$item['expectedError']]);
        $checked += 1;
    }

    assert_same('relative-time direct format', 'in 1h', RelativeTimeCore::format(3_600, $data, [
        'locale' => 'en',
        'style' => 'narrow',
        'numeric' => 'always',
        'policy' => 'precise',
        'unit' => 'auto',
    ]));
    assert_same('relative-time direct empty locale', 'in 1h', RelativeTimeCore::format(3_600, $data, [
        'locale' => '',
        'style' => 'narrow',
        'numeric' => 'always',
        'policy' => 'precise',
        'unit' => 'auto',
    ]));
    assert_same('relative-time direct negative zero', '0 seconds ago', RelativeTimeCore::format(-0.0, $data, [
        'locale' => 'en',
        'style' => 'long',
        'numeric' => 'always',
        'policy' => 'precise',
        'unit' => 'second',
    ]));
    assert_same('relative-time direct after tomorrow', 'après-demain', RelativeTimeCore::format(172_800, $data, [
        'locale' => 'fr',
        'style' => 'long',
        'numeric' => 'auto',
        'policy' => 'precise',
        'unit' => 'day',
    ]));
    assert_json_equal('relative-time direct parts', [['type' => 'text', 'value' => 'yesterday']], RelativeTimeCore::formatToParts(-86_400, $data, [
        'locale' => 'en',
        'style' => 'long',
        'numeric' => 'auto',
        'unit' => 'day',
    ]));
    try {
        RelativeTimeCore::format('1e30', $data, [
            'locale' => 'en',
            'style' => 'narrow',
            'numeric' => 'always',
            'policy' => 'precise',
            'unit' => 'auto',
        ]);
        fail('huge relative-time quantity expected bad-operand');
    } catch (Throwable $error) {
        assert_same('huge relative-time quantity error', 'bad-operand', error_code($error));
    }
    $checked += 6;

    assert_mf2_error('relative-time direct throwing operand', 'bad-operand', static fn(): string => RelativeTimeCore::format(new ThrowingStringValue(), $data));
    assert_mf2_error('relative-time direct throwing locale option', 'bad-option', static fn(): string => RelativeTimeCore::format(1, $data, ['locale' => new ThrowingStringValue()]));
    assert_mf2_error('relative-time direct throwing style option', 'bad-option', static fn(): string => RelativeTimeCore::format(1, $data, ['style' => new ThrowingStringValue()]));
    assert_mf2_error('relative-time direct throwing unit option', 'bad-option', static fn(): string => RelativeTimeCore::format(1, $data, ['unit' => new ThrowingStringValue()]));
    assert_mf2_error('relative-time empty locale map', 'missing-locale-data', static fn(): string => RelativeTimeCore::format(1, [
        'localeMap' => [],
        'patternSets' => [['id' => 'rt', 'data' => ['short' => ['second' => ['future' => ['other' => 'in {0} sec.']]]]]],
    ]));
    assert_mf2_error('relative-time empty pattern sets', 'missing-locale-data', static fn(): string => RelativeTimeCore::format(1, [
        'localeMap' => ['en' => 'rt'],
        'patternSets' => [],
    ]));
    assert_mf2_error(
        'relative-time invalid locale map value',
        'missing-locale-data',
        static fn(): FunctionRegistry => RelativeTimeCore::registry([
            'localeMap' => ['en' => []],
            'patternSets' => [[
                'id' => 'rt',
                'data' => ['short' => ['second' => ['future' => ['other' => 'in {0} sec.']]]],
            ]],
        ])
    );
    assert_mf2_error('relative-time empty pattern set data', 'missing-locale-data', static fn(): string => RelativeTimeCore::format(1, [
        'localeMap' => ['en' => 'rt'],
        'patternSets' => [['id' => 'rt', 'data' => []]],
    ]));
    assert_mf2_error('relative-time empty pattern set id', 'missing-locale-data', static fn(): string => RelativeTimeCore::format(1, [
        'localeMap' => ['en' => 'rt'],
        'patternSets' => [['id' => '', 'data' => ['short' => ['second' => ['future' => ['other' => 'in {0} sec.']]]]]],
    ]));
    $checked += 9;

    $referenceCases = [
        ['locale' => 'en', 'style' => 'long', 'numeric' => 'auto', 'unit' => 'day', 'value' => -1, 'seconds' => -86_400],
        ['locale' => 'en', 'style' => 'long', 'numeric' => 'always', 'unit' => 'day', 'value' => 1, 'seconds' => 86_400],
        ['locale' => 'ja', 'style' => 'narrow', 'numeric' => 'always', 'unit' => 'minute', 'value' => 3, 'seconds' => 180],
        ['locale' => 'en', 'style' => 'narrow', 'numeric' => 'always', 'unit' => 'minute', 'value' => -1, 'seconds' => -60],
        ['locale' => 'en', 'style' => 'long', 'numeric' => 'always', 'unit' => 'second', 'value' => -0.0, 'seconds' => -0.0],
        ['locale' => 'fr', 'style' => 'long', 'numeric' => 'auto', 'unit' => 'day', 'value' => 2, 'seconds' => 172_800],
    ];
    $references = node_intl_relative_time_outputs($referenceCases);
    foreach ($referenceCases as $index => $item) {
        $actual = $formatter->formatValue($item['seconds'], [
            'locale' => $item['locale'],
            'style' => $item['style'],
            'numeric' => $item['numeric'],
            'policy' => 'precise',
            'unit' => $item['unit'],
        ]);
        assert_same("Intl relative-time reference {$index}", $references[$index], $actual);
        $checked += 1;
    }

    return $checked;
}

function assert_mf2_error(string $label, string $expectedCode, callable $callback): void
{
    try {
        $callback();
        fail("{$label}: expected {$expectedCode}");
    } catch (Throwable $error) {
        assert_same("{$label} error", $expectedCode, error_code($error));
    }
}

function assert_portable_regressions(): int
{
    $checked = 0;

    $unsafeOffset = parse_to_model('Value: {$n :offset add=0}');
    if ($unsafeOffset['hasDiagnostics']) {
        fail('unsafe offset regression parse diagnostics: ' . json_encode($unsafeOffset['diagnostics'], JSON_UNESCAPED_UNICODE));
    }
    $unsafeResult = assert_no_php_warnings(
        'unsafe float offset operand',
        static fn(): array => format_message($unsafeOffset['model'], ['n' => 1e20], ['locale' => 'en', 'bidiIsolation' => 'none']),
    );
    assert_same('unsafe float offset operand value', 'Value: {$n}', $unsafeResult['value']);
    assert_error_codes('unsafe float offset operand errors', $unsafeResult['errors'], [['code' => 'bad-operand']]);
    $checked += 1;

    $largeSelector = parse_to_model(".local \$x = {999999999999999999998 :offset add=1}\n.match \$x\n999999999999999999999 {{exact}}\n* {{other}}");
    if ($largeSelector['hasDiagnostics']) {
        fail('large offset selector regression parse diagnostics: ' . json_encode($largeSelector['diagnostics'], JSON_UNESCAPED_UNICODE));
    }
    $largeResult = assert_no_php_warnings(
        'large offset exact selector',
        static fn(): array => format_message($largeSelector['model'], [], ['locale' => 'en', 'bidiIsolation' => 'none']),
    );
    assert_same('large offset exact selector value', 'exact', $largeResult['value']);
    assert_error_codes('large offset exact selector errors', $largeResult['errors'], []);
    $checked += 1;

    $largeFormat = parse_to_model('Values: {9007199254740993 :number}; {999999999999999999999 :number}; {100000000000000000001 :integer}; {9007199254740993 :percent}');
    if ($largeFormat['hasDiagnostics']) {
        fail('large portable numeric format regression parse diagnostics: ' . json_encode($largeFormat['diagnostics'], JSON_UNESCAPED_UNICODE));
    }
    $largeFormatResult = assert_no_php_warnings(
        'large portable numeric format',
        static fn(): array => format_message($largeFormat['model'], [], ['locale' => 'en', 'bidiIsolation' => 'none']),
    );
    assert_same('large portable numeric format value', 'Values: 9007199254740993; 999999999999999999999; 100000000000000000001; 900719925474099300%', $largeFormatResult['value']);
    assert_error_codes('large portable numeric format errors', $largeFormatResult['errors'], []);
    $checked += 1;

    $fractionBounds = parse_to_model('Values: {1.234 :number maximumFractionDigits=2}; {1 :number maximumFractionDigits=10000}; {1.234 :percent minimumFractionDigits=2 maximumFractionDigits=1}');
    if ($fractionBounds['hasDiagnostics']) {
        fail('portable numeric fraction bounds regression parse diagnostics: ' . json_encode($fractionBounds['diagnostics'], JSON_UNESCAPED_UNICODE));
    }
    $fractionBoundsResult = assert_no_php_warnings(
        'portable numeric fraction bounds',
        static fn(): array => format_message($fractionBounds['model'], [], ['locale' => 'en', 'bidiIsolation' => 'none']),
    );
    assert_same('portable numeric fraction bounds value', 'Values: 1.23; {|1|}; {|1.234|}', $fractionBoundsResult['value']);
    assert_error_codes('portable numeric fraction bounds errors', $fractionBoundsResult['errors'], [['code' => 'bad-option'], ['code' => 'bad-option']]);
    $checked += 1;

    $halfExpand = parse_to_model('Values: {1.005 :number maximumFractionDigits=2}; {2.225 :number maximumFractionDigits=2}; {-1.005 :number maximumFractionDigits=2}; {0.005 :percent maximumFractionDigits=0}');
    if ($halfExpand['hasDiagnostics']) {
        fail('portable numeric half-expand rounding regression parse diagnostics: ' . json_encode($halfExpand['diagnostics'], JSON_UNESCAPED_UNICODE));
    }
    $halfExpandResult = assert_no_php_warnings(
        'portable numeric half-expand rounding',
        static fn(): array => format_message($halfExpand['model'], [], ['locale' => 'en', 'bidiIsolation' => 'none']),
    );
    assert_same('portable numeric half-expand rounding value', 'Values: 1.01; 2.23; -1.01; 1%', $halfExpandResult['value']);
    assert_error_codes('portable numeric half-expand rounding errors', $halfExpandResult['errors'], []);
    $checked += 1;

    try {
        new NumberOperands(str_repeat('1', 257));
        fail('oversized plural operand should fail');
    } catch (RangeException $error) {
        assert_same('oversized plural operand error message', 'Unsupported plural operand value', $error->getMessage());
    }
    $checked += 1;

    return $checked;
}

function assert_no_php_warnings(string $label, callable $callback): mixed
{
    $warnings = [];
    set_error_handler(static function (int $severity, string $message) use (&$warnings): bool {
        $warnings[] = $message;
        return true;
    });
    try {
        $result = $callback();
    } finally {
        restore_error_handler();
    }
    assert_same($label . ' warnings', [], $warnings);
    return $result;
}

function number_core_options(array $item): array
{
    $options = $item['options'] ?? [];
    $options['locale'] = $item['locale'] ?? 'en-US';
    return $options;
}

function date_time_core_options(array $item): array
{
    $options = $item['options'] ?? [];
    $options['locale'] = $item['locale'] ?? 'en-US';
    return $options;
}

function format_date_time_core_item(array $item): string
{
    return match ($item['kind'] ?? '') {
        'date' => DateTimeCore::formatDate($item['value'], date_time_core_options($item)),
        'time' => DateTimeCore::formatTime($item['value'], date_time_core_options($item)),
        'datetime' => DateTimeCore::formatDateTime($item['value'], date_time_core_options($item)),
        default => throw new RuntimeException('Unsupported date/time core fixture kind.'),
    };
}

function date_time_core_reference_item(array $item): array
{
    return [
        'kind' => $item['kind'],
        'locale' => $item['locale'],
        'value' => $item['value'],
        'options' => $item['referenceOptions'],
    ];
}

function node_intl_number_outputs(array $cases): array
{
    return node_json_outputs($cases, <<<'JS'
const fs = require("fs");
const cases = JSON.parse(fs.readFileSync(0, "utf8"));
function intlOptions(options) {
  if (options.style === "number") return {};
  if (options.style === "percent") return { style: "percent" };
  if (options.style === "currency") return { style: "currency", currency: options.currency };
  throw new Error("Unsupported Intl reference style: " + options.style);
}
process.stdout.write(JSON.stringify(cases.map((item) =>
  new Intl.NumberFormat(item.locale, intlOptions(item.options || {})).format(item.value)
)));
JS);
}

function node_intl_date_time_outputs(array $cases): array
{
    return node_json_outputs($cases, <<<'JS'
const fs = require("fs");
const cases = JSON.parse(fs.readFileSync(0, "utf8"));
process.stdout.write(JSON.stringify(cases.map((item) =>
  new Intl.DateTimeFormat(item.locale, { timeZone: "UTC", ...(item.options || {}) }).format(new Date(item.value))
)));
JS);
}

function node_intl_relative_time_outputs(array $cases): array
{
    return node_json_outputs($cases, <<<'JS'
const fs = require("fs");
const cases = JSON.parse(fs.readFileSync(0, "utf8"));
process.stdout.write(JSON.stringify(cases.map((item) =>
  new Intl.RelativeTimeFormat(item.locale, { style: item.style, numeric: item.numeric }).format(item.value, item.unit)
)));
JS);
}

function node_json_outputs(array $cases, string $script): array
{
    $process = proc_open(
        'node -e ' . escapeshellarg($script),
        [['pipe', 'r'], ['pipe', 'w'], ['pipe', 'w']],
        $pipes,
    );
    if (!is_resource($process)) {
        fail('Unable to start node for Intl reference comparisons.');
    }
    fwrite($pipes[0], json_encode($cases, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
    fclose($pipes[0]);
    $stdout = stream_get_contents($pipes[1]);
    $stderr = stream_get_contents($pipes[2]);
    fclose($pipes[1]);
    fclose($pipes[2]);
    $status = proc_close($process);
    if ($status !== 0) {
        fail("node Intl reference failed with status {$status}: {$stderr}");
    }
    return json_decode($stdout, true, flags: JSON_THROW_ON_ERROR);
}

function assert_public_api_boundary(): void
{
    assert_same('portable registry formatter', true, FunctionRegistry::portable()->hasFormatter(['name' => 'number']));

    $parsed = parse_to_model('Total: {$amount :currency currency=USD}');
    $formatted = format_message($parsed['model'], ['amount' => 42]);
    assert_same('unsupported function fallback', 'Total: {$amount}', $formatted['value']);
    assert_json_equal('unsupported function diagnostic', ['unknown-function'], array_map(static fn($error): string => error_code($error), $formatted['errors']));

    $message = parse_to_model('Hello {$name}')['model'];
    $emptyMissing = format_message($message, [], [
        'onMissingArgument' => static fn(array $context): string => '',
    ]);
    assert_same('empty missing recovery value', 'Hello ', $emptyMissing['value']);
    assert_json_equal('empty missing recovery errors', ['unresolved-variable'], array_map(static fn($error): string => error_code($error), $emptyMissing['errors']));
    $emptyMissingParts = format_message_to_parts($message, [], [
        'onMissingArgument' => static fn(array $context): string => '',
    ]);
    assert_json_equal('empty missing recovery parts', [
        ['type' => 'text', 'value' => 'Hello '],
        ['type' => 'fallback', 'source' => '$name', 'value' => ''],
    ], $emptyMissingParts['parts']);

    $declinedMissing = format_message($message, [], [
        'onMissingArgument' => static fn(array $context): null => null,
    ]);
    assert_same('declined missing recovery value', 'Hello {$name}', $declinedMissing['value']);
    $declinedMissingParts = format_message_to_parts($message, [], [
        'onMissingArgument' => static fn(array $context): null => null,
    ]);
    assert_json_equal('declined missing recovery parts', [
        ['type' => 'text', 'value' => 'Hello '],
        ['type' => 'fallback', 'source' => '$name'],
    ], $declinedMissingParts['parts']);

    $integer = parse_to_model('Hello {$name :integer}')['model'];
    $emptyFormatError = format_message($integer, ['name' => 'abc'], [
        'onFormatError' => static fn(array $context): string => '',
    ]);
    assert_same('empty format-error recovery value', 'Hello ', $emptyFormatError['value']);
    assert_json_equal('empty format-error recovery errors', ['bad-operand'], array_map(static fn($error): string => error_code($error), $emptyFormatError['errors']));
    $emptyFormatErrorParts = format_message_to_parts($integer, ['name' => 'abc'], [
        'onFormatError' => static fn(array $context): string => '',
    ]);
    assert_json_equal('empty format-error recovery parts', [
        ['type' => 'text', 'value' => 'Hello '],
        ['type' => 'fallback', 'source' => '$name', 'value' => ''],
    ], $emptyFormatErrorParts['parts']);

    $throwingPlaceholder = parse_to_model('Hello {$x}')['model'];
    $throwingPlaceholderResult = format_message($throwingPlaceholder, ['x' => new ThrowingStringValue()]);
    assert_same('throwing host placeholder recovery value', 'Hello {$x}', $throwingPlaceholderResult['value']);
    assert_json_equal('throwing host placeholder recovery errors', ['bad-operand'], array_map(static fn($error): string => error_code($error), $throwingPlaceholderResult['errors']));
    $throwingPlaceholderParts = format_message_to_parts($throwingPlaceholder, ['x' => new ThrowingStringValue()]);
    assert_json_equal('throwing host placeholder recovery parts', [
        ['type' => 'text', 'value' => 'Hello '],
        ['type' => 'fallback', 'source' => '$x'],
    ], $throwingPlaceholderParts['parts']);

    $throwingString = parse_to_model('Hello {$x :string}')['model'];
    $throwingStringResult = format_message($throwingString, ['x' => new ThrowingStringValue()]);
    assert_same('throwing host annotated recovery value', 'Hello {$x}', $throwingStringResult['value']);
    assert_json_equal('throwing host annotated recovery errors', ['bad-operand'], array_map(static fn($error): string => error_code($error), $throwingStringResult['errors']));

    $throwingSelector = parse_to_model('.input {$x :string}' . "\n" . '.match $x' . "\n" . 'ok {{ok}}' . "\n" . '* {{fallback}}')['model'];
    $throwingSelectorResult = format_message($throwingSelector, ['x' => new ThrowingStringValue()]);
    assert_same('throwing host selector recovery value', 'fallback', $throwingSelectorResult['value']);
    assert_json_equal('throwing host selector recovery errors', ['bad-operand', 'bad-selector'], array_map(static fn($error): string => error_code($error), $throwingSelectorResult['errors']));

    $throwingLocaleResult = format_message($throwingPlaceholder, ['x' => 'ok'], ['locale' => new ThrowingStringValue()]);
    assert_same('throwing host locale recovery value', '', $throwingLocaleResult['value']);
    assert_json_equal('throwing host locale recovery errors', ['bad-option'], array_map(static fn($error): string => error_code($error), $throwingLocaleResult['errors']));
    $throwingLocaleParts = format_message_to_parts($throwingPlaceholder, ['x' => 'ok'], ['locale' => new ThrowingStringValue()]);
    assert_json_equal('throwing host locale recovery parts', [], $throwingLocaleParts['parts']);
    assert_json_equal('throwing host locale recovery part errors', ['bad-option'], array_map(static fn($error): string => error_code($error), $throwingLocaleParts['errors']));

    $throwingNumberOption = parse_to_model('Hello {1 :number minimumFractionDigits=$d}')['model'];
    $throwingNumberOptionResult = format_message($throwingNumberOption, ['d' => new ThrowingStringValue()], [
        'locale' => 'en-US',
        'functions' => NumberCore::registry(),
    ]);
    assert_same('throwing host number option recovery value', 'Hello {|1|}', $throwingNumberOptionResult['value']);
    assert_json_equal('throwing host number option recovery errors', ['bad-option'], array_map(static fn($error): string => error_code($error), $throwingNumberOptionResult['errors']));

    $throwingMf2OptionResult = format_message($throwingNumberOption, ['d' => new ThrowingMF2StringValue()], [
        'locale' => 'en-US',
        'functions' => NumberCore::registry(),
    ]);
    assert_same('throwing MF2 host number option recovery value', 'Hello {|1|}', $throwingMf2OptionResult['value']);
    assert_json_equal('throwing MF2 host number option recovery errors', ['bad-option'], array_map(static fn($error): string => error_code($error), $throwingMf2OptionResult['errors']));

    $throwingOptionSelector = parse_to_model('.input {$n :number minimumFractionDigits=$d}' . "\n" . '.match $n' . "\n" . 'one {{one}}' . "\n" . '* {{fallback}}')['model'];
    $throwingOptionSelectorResult = format_message($throwingOptionSelector, ['n' => 1, 'd' => new ThrowingStringValue()], [
        'locale' => 'en-US',
        'functions' => NumberCore::registry(),
    ]);
    assert_same('throwing host number option selector recovery value', 'fallback', $throwingOptionSelectorResult['value']);
    $throwingOptionSelectorErrors = array_map(static fn($error): string => error_code($error), $throwingOptionSelectorResult['errors']);
    assert_same('throwing host number option selector first error', 'bad-option', $throwingOptionSelectorErrors[0] ?? null);
    assert_contains_all('throwing host number option selector recovery errors', $throwingOptionSelectorErrors, ['bad-option', 'bad-selector']);

    $throwingMf2OptionSelectorResult = format_message($throwingOptionSelector, ['n' => 1, 'd' => new ThrowingMF2StringValue()], [
        'locale' => 'en-US',
        'functions' => NumberCore::registry(),
    ]);
    assert_same('throwing MF2 host number option selector recovery value', 'fallback', $throwingMf2OptionSelectorResult['value']);
    $throwingMf2OptionSelectorErrors = array_map(static fn($error): string => error_code($error), $throwingMf2OptionSelectorResult['errors']);
    assert_same('throwing MF2 host number option selector first error', 'bad-option', $throwingMf2OptionSelectorErrors[0] ?? null);
    assert_contains_all('throwing MF2 host number option selector recovery errors', $throwingMf2OptionSelectorErrors, ['bad-option', 'bad-selector']);

    $functions = array_values(array_filter(
        get_defined_functions()['user'],
        static fn(string $name): bool => str_starts_with($name, 'mojito\\messageformat2\\')
            && !str_starts_with($name, 'mojito\\messageformat2\\internal\\'),
    ));
    sort($functions);
    assert_json_equal('public PHP functions', [
        'mojito\\messageformat2\\format_message',
        'mojito\\messageformat2\\format_message_to_parts',
        'mojito\\messageformat2\\parse_to_model',
    ], $functions);

    $classes = array_values(array_filter(
        get_declared_classes(),
        static fn(string $name): bool => str_starts_with($name, 'Mojito\\MessageFormat2\\')
            && !str_starts_with($name, 'Mojito\\MessageFormat2\\Internal\\'),
    ));
    sort($classes);
    assert_json_equal('public PHP classes', [
        'Mojito\\MessageFormat2\\DateTimeCore',
        'Mojito\\MessageFormat2\\FunctionRegistry',
        'Mojito\\MessageFormat2\\IntlFunctions',
        'Mojito\\MessageFormat2\\MF2Error',
        'Mojito\\MessageFormat2\\NumberCore',
        'Mojito\\MessageFormat2\\RelativeTimeCore',
    ], $classes);

    assert_same('Intl registry formatter', true, IntlFunctions::registry()->hasFormatter(['name' => 'currency']));
    assert_same('Intl registry keeps relative time unsupported', false, IntlFunctions::registry()->hasFormatter(['name' => 'relativeTime']));
    assert_same('NumberCore registry formatter', true, NumberCore::registry()->hasFormatter(['name' => 'currency']));
    assert_same('DateTimeCore registry formatter', true, DateTimeCore::registry()->hasFormatter(['name' => 'datetime']));
    assert_same('RelativeTimeCore registry formatter', true, RelativeTimeCore::registry(['localeMap' => ['en' => 'rt'], 'patternSets' => [['id' => 'rt', 'data' => ['short' => ['second' => ['future' => ['other' => 'in {0} sec.'], 'past' => ['other' => '{0} sec. ago']]]]]]])->hasFormatter(['name' => 'relativeTime']));
}

function read_json(string $path): array
{
    $json = file_get_contents($path);
    if ($json === false) {
        fail("Unable to read fixture {$path}");
    }
    return json_decode($json, true, flags: JSON_THROW_ON_ERROR);
}

function assert_json_equal(string $label, mixed $expected, mixed $actual): void
{
    $expectedJson = json_encode(canonical_json_value($expected), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    $actualJson = json_encode(canonical_json_value($actual), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    if ($expectedJson !== $actualJson) {
        fail("{$label} mismatch\nexpected: {$expectedJson}\nactual:   {$actualJson}");
    }
}

function canonical_json_value(mixed $value): mixed
{
    if (!is_array($value)) {
        return $value;
    }
    if (array_is_list($value)) {
        return array_map('canonical_json_value', $value);
    }
    ksort($value, SORT_STRING);
    foreach ($value as $key => $item) {
        $value[$key] = canonical_json_value($item);
    }
    return $value;
}

function assert_same(string $label, mixed $expected, mixed $actual): void
{
    if ($expected !== $actual) {
        fail("{$label}: expected " . json_encode($expected, JSON_UNESCAPED_UNICODE) . ', got ' . json_encode($actual, JSON_UNESCAPED_UNICODE));
    }
}

function assert_contains_all(string $label, array $actual, array $expected): void
{
    $counts = array_count_values($actual);
    foreach ($expected as $code) {
        if (($counts[$code] ?? 0) <= 0) {
            fail("{$label}: expected " . json_encode($expected) . ', got ' . json_encode($actual));
        }
        $counts[$code] -= 1;
    }
}

function assert_error_codes(string $label, array $actualErrors, array $expectedErrors): void
{
    $actual = array_map(static fn(Throwable $error): string => error_code($error), $actualErrors);
    assert_contains_all($label, $actual, expected_codes($expectedErrors));
}

function expected_codes(array $items): array
{
    return array_map(static fn(array $item): string => (string) ($item['code'] ?? $item['type'] ?? ''), $items);
}

function fail(string $message): never
{
    fwrite(STDERR, $message . "\n");
    exit(1);
}
