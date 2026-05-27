<?php

declare(strict_types=1);

use function MF2\format_message;
use function MF2\parse_to_model;

require_once __DIR__ . '/src/bootstrap.php';

$source = ".input {\$count :number}\n.match \$count\none {{{\$count} item}}\n* {{{\$count} items}}";
$iterations = 20000;
$parsed = parse_to_model($source);
if ($parsed['hasDiagnostics']) {
    fwrite(STDERR, 'parse failed: ' . json_encode($parsed['diagnostics'], JSON_UNESCAPED_UNICODE) . "\n");
    exit(1);
}
$model = $parsed['model'];

$start = hrtime(true);
for ($i = 0; $i < $iterations; $i += 1) {
    format_message($model, ['count' => ($i % 5) + 1], ['locale' => 'en']);
}
$formatNs = hrtime(true) - $start;

$start = hrtime(true);
for ($i = 0; $i < $iterations; $i += 1) {
    parse_to_model($source);
}
$parseNs = hrtime(true) - $start;

printf("PHP MF2 format: %.0f ops/s %.1f ns/op\n", $iterations / ($formatNs / 1_000_000_000), $formatNs / $iterations);
printf("PHP MF2 parse: %.0f ops/s %.1f ns/op\n", $iterations / ($parseNs / 1_000_000_000), $parseNs / $iterations);
