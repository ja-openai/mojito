<?php

declare(strict_types=1);

use function Mojito\MessageFormat2\format_message_to_parts;
use function Mojito\MessageFormat2\format_message;
use function Mojito\MessageFormat2\Internal\error_code;
use function Mojito\MessageFormat2\Internal\canonical_locale_key;
use function Mojito\MessageFormat2\Internal\locale_lookup_chain;
use function Mojito\MessageFormat2\parse_to_model;
use Mojito\MessageFormat2\FunctionRegistry;
use Mojito\MessageFormat2\IntlFunctions;

require_once __DIR__ . '/../src/bootstrap.php';

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

echo "PHP MF2 conformance runner passed {$formatCases} format cases, {$partsCases} parts cases, {$formatErrorCases} format error cases, and {$localeCases} locale cases.\n";

function fixture_paths(string $root): array
{
    $paths = glob($root . '/*.json') ?: [];
    sort($paths, SORT_STRING);
    return $paths;
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
        'Mojito\\MessageFormat2\\FunctionRegistry',
        'Mojito\\MessageFormat2\\IntlFunctions',
        'Mojito\\MessageFormat2\\MF2Error',
    ], $classes);
    foreach (array_merge($functions, $classes) as $name) {
        $normalized = normalize_public_name($name);
        if (
            str_contains($normalized, 'inflection')
            || str_contains($normalized, 'm2if')
            || str_contains($normalized, 'compiledtermpack')
            || str_contains($normalized, 'termpack')
        ) {
            fail('Inflection runtime public API must stay out of the PHP package until a product API is approved: ' . $name);
        }
    }

    assert_same('Intl registry formatter', true, IntlFunctions::registry()->hasFormatter(['name' => 'currency']));
    assert_same('Intl registry keeps relative time unsupported', false, IntlFunctions::registry()->hasFormatter(['name' => 'relativeTime']));
}

function normalize_public_name(string $name): string
{
    return preg_replace('/[^a-z0-9]/', '', strtolower($name)) ?? '';
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
