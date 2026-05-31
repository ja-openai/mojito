<?php

declare(strict_types=1);

use Mojito\MessageFormat2\IntlFunctions;
use function Mojito\MessageFormat2\format_message;
use function Mojito\MessageFormat2\parse_to_model;

require_once __DIR__ . '/../src/bootstrap.php';

$quiet = in_array('--quiet', $argv, true);

$source = implode('; ', [
    'number={$amount :number}',
    'percent={$ratio :percent maximumFractionDigits=1}',
    'currency={$price :currency currency=EUR}',
    'date={$due :date dateStyle=full timeZone=UTC}',
    'time={$start :time timeStyle=medium timeZone=UTC}',
    'datetime={$created :datetime dateStyle=medium timeStyle=medium timeZone=UTC}',
]);
$parsed = parse_to_model($source);
if ($parsed['hasDiagnostics']) {
    throw new RuntimeException('Unexpected parser diagnostics: ' . json_encode($parsed['diagnostics'], JSON_UNESCAPED_UNICODE));
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
    $result = format_message($parsed['model'], $arguments, [
        'locale' => $locale,
        'functions' => IntlFunctions::registry(),
    ]);
    if ($result['hasErrors']) {
        throw new RuntimeException("Unexpected {$locale} Intl errors: " . json_encode($result['errors'], JSON_UNESCAPED_UNICODE));
    }
    if (!$quiet) {
        echo "{$locale} -> {$result['value']}\n";
    }
}

$relative = format_message(parse_to_model('relative={$days :relativeTime unit=day}')['model'], ['days' => -1], [
    'functions' => IntlFunctions::registry(),
]);
if (!$relative['hasErrors']) {
    throw new RuntimeException('PHP Intl registry should not claim relativeTime support.');
}
if (!$quiet) {
    echo "relativeTime -> deferred; PHP intl does not expose IntlRelativeTimeFormatter in this environment.\n";
}
