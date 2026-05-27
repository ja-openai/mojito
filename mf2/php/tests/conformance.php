<?php

declare(strict_types=1);

use function MF2\canonical_locale_key;
use function MF2\error_code;
use function MF2\format_message;
use function MF2\format_message_to_parts;
use function MF2\format_message_to_parts_with_fallback;
use function MF2\format_message_with_fallback;
use function MF2\locale_lookup_chain;
use function MF2\parse_to_model;

require_once __DIR__ . '/../src/bootstrap.php';

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
        assert_same(basename($path) . ': format', $case['expected'], $actual);
        $formatCases += 1;
    }
    foreach ($fixture['partsCases'] ?? [] as $case) {
        $actual = format_message_to_parts($parse['model'], $case['arguments'] ?? [], [
            'locale' => $case['locale'] ?? 'en',
            'bidiIsolation' => $case['bidiIsolation'] ?? 'none',
        ]);
        assert_json_equal(basename($path) . ': parts', $case['expected'], $actual);
        $partsCases += 1;
    }
    foreach ($fixture['fallbackCases'] ?? [] as $case) {
        $actual = format_message_with_fallback($parse['model'], $case['arguments'] ?? [], [
            'locale' => $case['locale'] ?? 'en',
            'bidiIsolation' => $case['bidiIsolation'] ?? 'none',
        ]);
        assert_same(basename($path) . ': fallback value', $case['expected'], $actual['value']);
        assert_error_codes(basename($path) . ': fallback errors', $actual['errors'], $case['expectedErrors'] ?? []);
    }
    foreach ($fixture['fallbackPartsCases'] ?? [] as $case) {
        $actual = format_message_to_parts_with_fallback($parse['model'], $case['arguments'] ?? [], ['locale' => $case['locale'] ?? 'en']);
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
    try {
        format_message($fixture['model'], $fixture['arguments'] ?? [], ['locale' => $fixture['locale'] ?? 'en']);
        fail(basename($path) . ': expected format error');
    } catch (Throwable $error) {
        assert_same(basename($path) . ': format error', $fixture['expectedError']['code'], error_code($error));
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
    $actual = array_map(static fn(Throwable $error): string => MF2\error_code($error), $actualErrors);
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
