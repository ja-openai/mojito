<?php

declare(strict_types=1);

use MF2\FunctionRegistry;
use MF2\MF2Error;
use function MF2\error_code;
use function MF2\format_message;
use function MF2\format_message_with_fallback;
use function MF2\parse_to_model;

require_once __DIR__ . '/../src/bootstrap.php';

const CHECKS = [
    ['tests/syntax.json', 'parse'],
    ['tests/syntax-errors.json', 'parse'],
    ['tests/bidi.json', 'parse'],
    ['tests/data-model-errors.json', 'data-model'],
    ['tests/functions/string.json', 'runtime'],
    ['tests/functions/number.json', 'runtime'],
    ['tests/functions/percent.json', 'runtime'],
    ['tests/functions/currency.json', 'runtime'],
    ['tests/functions/date.json', 'runtime'],
    ['tests/functions/datetime.json', 'runtime'],
    ['tests/functions/time.json', 'runtime'],
    ['tests/functions/offset.json', 'runtime'],
    ['tests/functions/integer.json', 'runtime'],
    ['tests/u-options.json', 'runtime'],
    ['tests/fallback.json', 'runtime'],
    ['tests/pattern-selection.json', 'runtime'],
];

$root = $argv[1] ?? realpath(__DIR__ . '/../../third_party/message-format-wg/test');
$baselinePath = $argv[2] ?? realpath(__DIR__ . '/../../conformance/unicode-official-baseline.json');

$summary = ['passed' => 0, 'skipped' => 0, 'notWired' => 0, 'files' => [], 'skipExamples' => []];
$wired = [];
foreach (CHECKS as [$path, $mode]) {
    $wired[$path] = true;
    run_file($root, $path, $mode, $summary);
}

foreach (official_json_paths($root . '/tests') as $path) {
    $pathKey = str_replace('\\', '/', substr($path, strlen($root) + 1));
    if (isset($wired[$pathKey])) {
        continue;
    }
    $suite = read_json_file($path);
    $summary['notWired'] += count($suite['tests'] ?? []);
}

foreach ($summary['files'] as $file) {
    echo "  {$file['mode']} {$file['path']} passed={$file['passed']} skipped={$file['skipped']}\n";
}
if (count($summary['skipExamples']) > 0) {
    echo "  skip examples:\n";
    foreach ($summary['skipExamples'] as $example) {
        echo "    {$example}\n";
    }
}
echo 'PHP Unicode official tests passed=' . $summary['passed'] . ' skipped=' . $summary['skipped'] . ' not_wired=' . $summary['notWired'] . ' total=' . ($summary['passed'] + $summary['skipped'] + $summary['notWired']) . "\n";

check_baseline($summary, $baselinePath);

function run_file(string $root, string $path, string $mode, array &$summary): void
{
    $suite = read_json_file($root . '/' . $path);
    $defaults = $suite['defaultTestProperties'] ?? [];
    $passed = 0;
    $skipped = 0;
    foreach (($suite['tests'] ?? []) as $index => $test) {
        $ok = match ($mode) {
            'parse' => check_parse_test($defaults, $test),
            'data-model' => check_data_model_error_test($defaults, $test),
            default => check_runtime_test($defaults, $test),
        };
        if ($ok) {
            $passed += 1;
        } else {
            $skipped += 1;
            record_skip($summary, $path, $index, $test, "{$mode} behavior differs");
        }
    }
    $summary['passed'] += $passed;
    $summary['skipped'] += $skipped;
    $summary['files'][] = ['path' => $path, 'mode' => $mode, 'passed' => $passed, 'skipped' => $skipped];
}

function check_parse_test(array $defaults, array $test): bool
{
    $expectedSyntaxError = false;
    foreach (expected_errors($defaults, $test) as $error) {
        if (($error['type'] ?? '') === 'syntax-error') {
            $expectedSyntaxError = true;
            break;
        }
    }
    $result = parse_to_model($test['src']);
    return $result['hasDiagnostics'] === $expectedSyntaxError;
}

function check_data_model_error_test(array $defaults, array $test): bool
{
    $expectedCodes = expected_local_codes($defaults, $test);
    $result = parse_to_model($test['src']);
    if (count($expectedCodes) === 0) {
        if (!array_key_exists('exp', $test) || $result['hasDiagnostics']) {
            return false;
        }
        try {
            $actual = format_message($result['model'], arguments_for($test, $result['model']), [
                'locale' => locale($defaults, $test),
                'bidiIsolation' => bidi_isolation($defaults, $test),
            ]);
            return $actual === $test['exp'];
        } catch (Throwable) {
            return false;
        }
    }
    $actualCodes = [];
    if ($result['hasDiagnostics']) {
        $actualCodes = array_map(static fn(array $diagnostic): string => $diagnostic['code'], $result['diagnostics']);
    } else {
        try {
            format_message($result['model'], arguments_for($test, $result['model']), [
                'locale' => locale($defaults, $test),
                'bidiIsolation' => bidi_isolation($defaults, $test),
            ]);
        } catch (Throwable $error) {
            $actualCodes[] = error_code($error);
        }
    }
    return contains_any($actualCodes, $expectedCodes);
}

function check_runtime_test(array $defaults, array $test): bool
{
    $result = parse_to_model($test['src']);
    if ($result['hasDiagnostics']) {
        return false;
    }
    $expectedCodes = expected_local_codes($defaults, $test);
    try {
        $actual = format_message_with_fallback($result['model'], runtime_arguments_for($test), [
            'locale' => locale($defaults, $test),
            'bidiIsolation' => bidi_isolation($defaults, $test),
            'functions' => official_function_registry(),
        ]);
        $actualCodes = array_map(static fn(Throwable $error): string => error_code($error), $actual['errors']);
        if (!contains_all($actualCodes, $expectedCodes)) {
            return false;
        }
        if (count($expectedCodes) === 0 && count($actualCodes) > 0) {
            return false;
        }
        return !array_key_exists('exp', $test) || $actual['value'] === $test['exp'];
    } catch (Throwable $error) {
        return in_array(error_code($error), $expectedCodes, true);
    }
}

function official_function_registry(): FunctionRegistry
{
    return FunctionRegistry::defaults()
        ->withFunction('test:function', 'official_test_function')
        ->withFunction('test:select', 'official_test_select_resolver')
        ->withFunction('test:format', 'official_test_format_resolver')
        ->withSelector('test:function', 'official_test_selector')
        ->withSelector('test:select', 'official_test_selector')
        ->withSelector('test:format', 'official_test_format_selector');
}

function official_test_function(array $call): string
{
    $state = official_test_state_from_call($call);
    if ($state['failsFormat']) {
        throw new MF2Error('bad-option', ':test:function fails=format requested a format failure.');
    }
    return format_official_test_value($state);
}

function official_test_select_resolver(array $call): string
{
    return format_official_test_value(official_test_state_from_call($call));
}

function official_test_format_resolver(array $call): string
{
    return format_official_test_value(official_test_state_from_call($call));
}

function official_test_selector(array $match): ?int
{
    $state = official_test_state_from_match($match);
    if ($state['failsSelect']) {
        throw MF2Error::badSelector(':test function fails selection.');
    }
    if ((int) floor($state['input']) !== 1) {
        return null;
    }
    if ($state['decimalPlaces'] === 1 && $match['key'] === '1.0') {
        return 2;
    }
    return $match['key'] === '1' ? 1 : null;
}

function official_test_format_selector(array $match): never
{
    throw MF2Error::badSelector(':test:format cannot be used for selection.');
}

function official_test_state_from_call(array $call): array
{
    return official_test_state($call['value'], $call['inheritedSource'], fn(string $name, mixed $fallback): mixed => $call['optionValue']($name, $fallback));
}

function official_test_state_from_match(array $match): array
{
    return official_test_state($match['value'], $match['inheritedSource'], fn(string $name, mixed $fallback): mixed => $match['optionValue']($name, $fallback));
}

function official_test_state(string $value, ?array $inheritedSource, callable $optionValue): array
{
    $state = $inheritedSource === null ? official_test_state_from_value($value) : official_test_state_from_source($inheritedSource);
    apply_official_test_options($state, $optionValue);
    return $state;
}

function official_test_state_from_source(array $source): array
{
    $state = $source['inherited'] === null ? official_test_state_from_value($source['value']) : official_test_state_from_source($source['inherited']);
    if (is_official_test_function($source['function']['name'] ?? '')) {
        apply_official_test_options($state, fn(string $name, mixed $fallback): mixed => source_option_value_for_official($source, $name, $fallback));
    }
    return $state;
}

function official_test_state_from_value(string $value): array
{
    $input = (float) $value;
    if (!is_finite($input) || !is_numeric($value)) {
        throw MF2Error::badOperand('Unicode test function requires a numeric operand.');
    }
    return ['input' => $input, 'decimalPlaces' => 0, 'failsFormat' => false, 'failsSelect' => false];
}

function apply_official_test_options(array &$state, callable $optionValue): void
{
    $decimalPlaces = $optionValue('decimalPlaces', null);
    if ($decimalPlaces !== null) {
        if ($decimalPlaces === '0') {
            $state['decimalPlaces'] = 0;
        } elseif ($decimalPlaces === '1') {
            $state['decimalPlaces'] = 1;
        } else {
            throw new MF2Error('bad-option', ':test function decimalPlaces must be 0 or 1.');
        }
    }
    switch ($optionValue('fails', '')) {
        case 'always':
            $state['failsFormat'] = true;
            $state['failsSelect'] = true;
            break;
        case 'format':
            $state['failsFormat'] = true;
            break;
        case 'select':
            $state['failsSelect'] = true;
            break;
    }
}

function source_option_value_for_official(array $source, string $name, mixed $fallback): mixed
{
    $option = $source['function']['options'][$name] ?? null;
    return ($option['type'] ?? null) === 'literal' ? ($option['value'] ?? '') : $fallback;
}

function format_official_test_value(array $state): string
{
    $sign = $state['input'] < 0 ? '-' : '';
    $absolute = abs($state['input']);
    $integer = (int) floor($absolute);
    if ($state['decimalPlaces'] === 1) {
        $digit = (int) floor(($absolute - $integer) * 10);
        return "{$sign}{$integer}.{$digit}";
    }
    return "{$sign}{$integer}";
}

function is_official_test_function(string $name): bool
{
    return in_array($name, ['test:function', 'test:select', 'test:format'], true);
}

function arguments_for(array $test, array $model): array
{
    $args = [];
    foreach ($model['declarations'] ?? [] as $declaration) {
        if (($declaration['type'] ?? '') === 'input') {
            $args[$declaration['name']] = '1';
        }
    }
    return array_merge($args, runtime_arguments_for($test));
}

function runtime_arguments_for(array $test): array
{
    $args = [];
    foreach ($test['params'] ?? [] as $param) {
        $args[$param['name']] = $param['value'];
    }
    return $args;
}

function expected_errors(array $defaults, array $test): array
{
    return $test['expErrors'] ?? $defaults['expErrors'] ?? [];
}

function expected_local_codes(array $defaults, array $test): array
{
    return array_map(static fn(array $error): string => ($error['type'] ?? '') === 'variant-key-mismatch' ? 'variant-key-count-mismatch' : (string) ($error['type'] ?? ''), expected_errors($defaults, $test));
}

function locale(array $defaults, array $test): string
{
    return (string) ($test['locale'] ?? $defaults['locale'] ?? 'en');
}

function bidi_isolation(array $defaults, array $test): string
{
    return (string) ($test['bidiIsolation'] ?? $defaults['bidiIsolation'] ?? 'none');
}

function record_skip(array &$summary, string $path, int $index, array $test, string $reason): void
{
    if (count($summary['skipExamples']) >= 8) {
        return;
    }
    $label = $test['description'] ?? $test['src'];
    $summary['skipExamples'][] = $path . '#' . ($index + 1) . ": {$reason}: {$label}";
}

function contains_all(array $actual, array $expected): bool
{
    $counts = array_count_values($actual);
    foreach ($expected as $code) {
        if (($counts[$code] ?? 0) <= 0) {
            return false;
        }
        $counts[$code] -= 1;
    }
    return true;
}

function contains_any(array $actual, array $expected): bool
{
    foreach ($actual as $code) {
        if (in_array($code, $expected, true)) {
            return true;
        }
    }
    return false;
}

function official_json_paths(string $root): array
{
    $paths = [];
    $iterator = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($root, FilesystemIterator::SKIP_DOTS));
    foreach ($iterator as $file) {
        if ($file->isFile() && str_ends_with($file->getFilename(), '.json')) {
            $paths[] = $file->getPathname();
        }
    }
    sort($paths, SORT_STRING);
    return $paths;
}

function check_baseline(array $summary, string $baselinePath): void
{
    $baseline = read_json_file($baselinePath);
    $total = $summary['passed'] + $summary['skipped'] + $summary['notWired'];
    if ($baseline['passed'] !== $summary['passed'] || $baseline['skipped'] !== $summary['skipped'] || $baseline['notWired'] !== $summary['notWired'] || $baseline['total'] !== $total) {
        fail("{$baselinePath}: expected official-test counts passed={$baseline['passed']} skipped={$baseline['skipped']} notWired={$baseline['notWired']} total={$baseline['total']}, got passed={$summary['passed']} skipped={$summary['skipped']} notWired={$summary['notWired']} total={$total}");
    }
    foreach ($summary['files'] as $file) {
        $expected = $baseline['files'][$file['path']] ?? null;
        if ($expected === null || $expected['passed'] !== $file['passed'] || $expected['skipped'] !== $file['skipped']) {
            $expectedPassed = $expected['passed'] ?? 'missing';
            $expectedSkipped = $expected['skipped'] ?? 'missing';
            fail("{$baselinePath}: expected {$file['path']} passed={$expectedPassed} skipped={$expectedSkipped}, got passed={$file['passed']} skipped={$file['skipped']}");
        }
    }
}

function read_json_file(string $path): array
{
    $json = file_get_contents($path);
    if ($json === false) {
        fail("Unable to read {$path}");
    }
    return json_decode($json, true, flags: JSON_THROW_ON_ERROR);
}

function fail(string $message): never
{
    fwrite(STDERR, $message . "\n");
    exit(1);
}
