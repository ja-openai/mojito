<?php

declare(strict_types=1);

use function Mojito\MessageFormat2\format_message;
use function Mojito\MessageFormat2\parse_to_model;

require_once __DIR__ . '/src/bootstrap.php';

$args = $argv;
array_shift($args);

$mode = 'format';
if (($args[0] ?? '') === '--format' || ($args[0] ?? '') === '--parse') {
    $mode = substr((string) array_shift($args), 2);
}

$fixtureDir = (string) ($args[0] ?? __DIR__ . '/../conformance/fixtures/source-to-model');
$iterations = (int) ($args[1] ?? 100000);
$warmupIterations = (int) ($args[2] ?? 10000);

if ($mode === 'parse') {
    run_parse_benchmark($fixtureDir, $iterations, $warmupIterations);
    exit(0);
}

run_format_benchmark($fixtureDir, $iterations, $warmupIterations);

function run_format_benchmark(string $fixtureDir, int $iterations, int $warmupIterations): void
{
    $cases = [];
    foreach (fixture_paths($fixtureDir) as $path) {
        $fixture = read_json($path);
        $parse = parse_to_model($fixture['source']);
        if ($parse['hasDiagnostics']) {
            fwrite(STDERR, basename($path) . ': unexpected diagnostics ' . json_encode($parse['diagnostics'], JSON_UNESCAPED_UNICODE) . "\n");
            exit(1);
        }
        foreach ($fixture['formatCases'] ?? [] as $case) {
            $cases[] = [
                'model' => $parse['model'],
                'arguments' => $case['arguments'] ?? [],
                'locale' => $case['locale'] ?? 'en',
                'bidiIsolation' => $case['bidiIsolation'] ?? 'none',
            ];
        }
    }
    if ($cases === []) {
        fwrite(STDERR, "No format cases found.\n");
        exit(2);
    }

    for ($index = 0; $index < $warmupIterations; $index += 1) {
        $case = $cases[$index % count($cases)];
        $result = format_message($case['model'], $case['arguments'], [
            'locale' => $case['locale'],
            'bidiIsolation' => $case['bidiIsolation'],
        ]);
        if ($result['hasErrors']) {
            fwrite(STDERR, 'Unexpected format errors: ' . json_encode($result['errors'], JSON_UNESCAPED_UNICODE) . "\n");
            exit(1);
        }
    }

    $checksum = 0;
    $start = hrtime(true);
    for ($index = 0; $index < $iterations; $index += 1) {
        $case = $cases[$index % count($cases)];
        $result = format_message($case['model'], $case['arguments'], [
            'locale' => $case['locale'],
            'bidiIsolation' => $case['bidiIsolation'],
        ]);
        if ($result['hasErrors']) {
            fwrite(STDERR, 'Unexpected format errors: ' . json_encode($result['errors'], JSON_UNESCAPED_UNICODE) . "\n");
            exit(1);
        }
        $checksum += strlen($result['value']);
    }
    $elapsedNs = hrtime(true) - $start;
    $seconds = $elapsedNs / 1_000_000_000;
    printf(
        "php format iterations=%d warmup=%d cases=%d seconds=%.6f ops_per_second=%.0f ns_per_op=%.1f checksum=%d\n",
        $iterations,
        $warmupIterations,
        count($cases),
        $seconds,
        $iterations / $seconds,
        $elapsedNs / $iterations,
        $checksum,
    );
}

function run_parse_benchmark(string $fixtureDir, int $iterations, int $warmupIterations): void
{
    $sources = [];
    foreach (fixture_paths($fixtureDir) as $path) {
        $fixture = read_json($path);
        if (isset($fixture['source']) && is_string($fixture['source'])) {
            $sources[] = $fixture['source'];
        }
    }
    if ($sources === []) {
        fwrite(STDERR, "No source fixtures found.\n");
        exit(2);
    }

    for ($index = 0; $index < $warmupIterations; $index += 1) {
        parse_to_model($sources[$index % count($sources)]);
    }

    $parsedCount = 0;
    $diagnosticCount = 0;
    $byteCount = 0;
    $start = hrtime(true);
    for ($index = 0; $index < $iterations; $index += 1) {
        $source = $sources[$index % count($sources)];
        $result = parse_to_model($source);
        if (!$result['hasDiagnostics']) {
            $parsedCount += 1;
        }
        $diagnosticCount += count($result['diagnostics']);
        $byteCount += strlen($source);
    }
    $elapsedNs = hrtime(true) - $start;
    $seconds = $elapsedNs / 1_000_000_000;
    printf(
        "php parse iterations=%d warmup=%d cases=%d seconds=%.6f ops_per_second=%.0f ns_per_op=%.1f parsed=%d diagnostics=%d bytes=%d\n",
        $iterations,
        $warmupIterations,
        count($sources),
        $seconds,
        $iterations / $seconds,
        $elapsedNs / $iterations,
        $parsedCount,
        $diagnosticCount,
        $byteCount,
    );
}

function fixture_paths(string $root): array
{
    $paths = glob(rtrim($root, '/') . '/*.json') ?: [];
    sort($paths, SORT_STRING);
    return $paths;
}

function read_json(string $path): array
{
    $json = file_get_contents($path);
    if ($json === false) {
        fwrite(STDERR, "Unable to read fixture {$path}\n");
        exit(1);
    }
    return json_decode($json, true, flags: JSON_THROW_ON_ERROR);
}
