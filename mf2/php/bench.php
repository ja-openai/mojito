<?php

declare(strict_types=1);

use function Mojito\MessageFormat2\format_message;
use function Mojito\MessageFormat2\parse_to_model;
use Mojito\MessageFormat2\DateTimeCore;
use Mojito\MessageFormat2\NumberCore;
use Mojito\MessageFormat2\RelativeTimeCore;

require_once __DIR__ . '/src/bootstrap.php';

$args = $argv;
array_shift($args);

$mode = 'format';
if (in_array($args[0] ?? '', ['--format', '--parse', '--number-core', '--date-time-core', '--relative-time-core'], true)) {
    $mode = substr((string) array_shift($args), 2);
}

$fixtureDir = (string) ($args[0] ?? __DIR__ . '/../conformance/fixtures/source-to-model');
$iterations = (int) ($args[1] ?? 100000);
$warmupIterations = (int) ($args[2] ?? 10000);

if ($mode === 'parse') {
    run_parse_benchmark($fixtureDir, $iterations, $warmupIterations);
    exit(0);
}
if ($mode === 'number-core') {
    run_number_core_benchmark($fixtureDir, $iterations, $warmupIterations);
    exit(0);
}
if ($mode === 'date-time-core') {
    run_date_time_core_benchmark($fixtureDir, $iterations, $warmupIterations);
    exit(0);
}
if ($mode === 'relative-time-core') {
    run_relative_time_core_benchmark($fixtureDir, $iterations, $warmupIterations);
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

function run_number_core_benchmark(string $fixturePath, int $iterations, int $warmupIterations): void
{
    $fixture = read_json($fixturePath);
    $cases = [];
    foreach ($fixture['formatCases'] ?? [] as $item) {
        $options = $item['options'] ?? [];
        $options['locale'] = $item['locale'] ?? 'en-US';
        $cases[] = ['value' => $item['value'], 'options' => $options];
    }
    if ($cases === []) {
        fwrite(STDERR, "No number-core cases found.\n");
        exit(2);
    }

    for ($index = 0; $index < $warmupIterations; $index += 1) {
        $case = $cases[$index % count($cases)];
        NumberCore::format($case['value'], $case['options']);
    }

    $checksum = 0;
    $start = hrtime(true);
    for ($index = 0; $index < $iterations; $index += 1) {
        $case = $cases[$index % count($cases)];
        $checksum += strlen(NumberCore::format($case['value'], $case['options']));
    }
    $elapsedNs = hrtime(true) - $start;
    $seconds = $elapsedNs / 1_000_000_000;
    printf(
        "php-number-core-format iterations=%d warmup=%d cases=%d seconds=%.6f ops_per_second=%.0f ns_per_op=%.1f checksum=%d\n",
        $iterations,
        $warmupIterations,
        count($cases),
        $seconds,
        $iterations / $seconds,
        $elapsedNs / $iterations,
        $checksum,
    );
}

function run_date_time_core_benchmark(string $fixturePath, int $iterations, int $warmupIterations): void
{
    $fixture = read_json($fixturePath);
    $cases = $fixture['formatCases'] ?? [];
    if ($cases === []) {
        fwrite(STDERR, "No date-time-core cases found.\n");
        exit(2);
    }

    for ($index = 0; $index < $warmupIterations; $index += 1) {
        format_date_time_core_item($cases[$index % count($cases)]);
    }

    $checksum = 0;
    $start = hrtime(true);
    for ($index = 0; $index < $iterations; $index += 1) {
        $checksum += strlen(format_date_time_core_item($cases[$index % count($cases)]));
    }
    $elapsedNs = hrtime(true) - $start;
    $seconds = $elapsedNs / 1_000_000_000;
    printf(
        "php-date-time-core-format iterations=%d warmup=%d cases=%d seconds=%.6f ops_per_second=%.0f ns_per_op=%.1f checksum=%d\n",
        $iterations,
        $warmupIterations,
        count($cases),
        $seconds,
        $iterations / $seconds,
        $elapsedNs / $iterations,
        $checksum,
    );
}

function run_relative_time_core_benchmark(string $fixturePath, int $iterations, int $warmupIterations): void
{
    $data = read_json(__DIR__ . '/../cldr/generated/relative-time/all/relative_time.json');
    $formatter = RelativeTimeCore::create($data);
    $fixture = read_json($fixturePath);
    $cases = [];
    foreach ($fixture['cases'] ?? [] as $item) {
        $cases[] = [
            'value' => ($item['arguments'] ?? [])['delta'] ?? 0,
            'options' => relative_time_core_options_from_source($item['locale'] ?? 'en', $item['source'] ?? ''),
        ];
    }
    if ($cases === []) {
        fwrite(STDERR, "No relative-time-core cases found.\n");
        exit(2);
    }

    for ($index = 0; $index < $warmupIterations; $index += 1) {
        $case = $cases[$index % count($cases)];
        $formatter->formatValue($case['value'], $case['options']);
    }

    $checksum = 0;
    $start = hrtime(true);
    for ($index = 0; $index < $iterations; $index += 1) {
        $case = $cases[$index % count($cases)];
        $checksum += strlen($formatter->formatValue($case['value'], $case['options']));
    }
    $elapsedNs = hrtime(true) - $start;
    $seconds = $elapsedNs / 1_000_000_000;
    printf(
        "php-relative-time-core-format iterations=%d warmup=%d cases=%d seconds=%.6f ops_per_second=%.0f ns_per_op=%.1f checksum=%d\n",
        $iterations,
        $warmupIterations,
        count($cases),
        $seconds,
        $iterations / $seconds,
        $elapsedNs / $iterations,
        $checksum,
    );
}

function format_date_time_core_item(array $item): string
{
    $options = $item['options'] ?? [];
    $options['locale'] = $item['locale'] ?? 'en-US';
    return match ($item['kind'] ?? '') {
        'date' => DateTimeCore::formatDate($item['value'], $options),
        'time' => DateTimeCore::formatTime($item['value'], $options),
        'datetime' => DateTimeCore::formatDateTime($item['value'], $options),
        default => throw new RuntimeException('Unsupported date/time core fixture kind.'),
    };
}

function relative_time_core_options_from_source(string $locale, string $source): array
{
    return [
        'locale' => $locale,
        'style' => relative_time_core_source_option($source, 'style') ?: 'short',
        'numeric' => relative_time_core_source_option($source, 'numeric') ?: 'always',
        'policy' => relative_time_core_source_option($source, 'policy') ?: 'precise',
        'unit' => relative_time_core_source_option($source, 'unit') ?: 'auto',
    ];
}

function relative_time_core_source_option(string $source, string $name): string
{
    return preg_match('/(?:^|\s)' . preg_quote($name, '/') . '=([^\s}]+)/', $source, $matches) === 1
        ? $matches[1]
        : '';
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
