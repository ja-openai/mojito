<?php

declare(strict_types=1);

use function Mojito\MessageFormat2\format_message;
use function Mojito\MessageFormat2\format_message_to_parts;
use function Mojito\MessageFormat2\parse_to_model;

require_once __DIR__ . '/../src/bootstrap.php';

$catalog = [
    'welcome' => 'Welcome, {$name}!',
    'cart.items' => ".input {\$count :number}\n.match \$count\none {{{\$count} item}}\n* {{{\$count} items}}",
    'cart.items.ru' => ".input {\$count :number}\n.match \$count\none {{{\$count} предмет}}\nfew {{{\$count} предмета}}\n* {{{\$count} предметов}}",
    'assignee.files' => ".input {\$gender :string}\n.input {\$count :number}\n.match \$gender \$count\nmale one {{He reviewed {\$count} file}}\nfemale one {{She reviewed {\$count} file}}\nmale * {{He reviewed {\$count} files}}\nfemale * {{She reviewed {\$count} files}}\n* * {{They reviewed {\$count} files}}",
    'rich.link' => 'Tap {#link href=$url @title=|Profile page|}profile{/link}. {$name :string @kind=person}',
    'file.saved' => 'File {$name :string u:dir=rtl} saved.',
];

foreach ($catalog as $id => $source) {
    $parsed = parse_to_model($source);
    if ($parsed['hasDiagnostics']) {
        echo $id . ' -> parser diagnostics ' . json_encode($parsed['diagnostics'], JSON_UNESCAPED_UNICODE) . "\n";
        continue;
    }
    $args = demo_arguments($id);
    $locale = str_ends_with($id, '.ru') ? 'ru' : 'en';
    $output = format_message($parsed['model'], $args, ['locale' => $locale, 'bidiIsolation' => 'default']);
    if ($output['hasErrors']) {
        echo "{$id}[{$locale}] errors -> " . json_encode($output['errors'], JSON_UNESCAPED_UNICODE) . "\n";
        continue;
    }
    echo "{$id}[{$locale}] -> " . json_encode($output['value'], JSON_UNESCAPED_UNICODE) . "\n";
    if ($id === 'rich.link') {
        $parts = format_message_to_parts($parsed['model'], $args, ['locale' => 'en']);
        echo "{$id} parts -> " . json_encode($parts['parts'], JSON_UNESCAPED_UNICODE) . "\n";
    }
}

$recoveryModel = parse_to_model('Hello {$name}')['model'];
$recovered = format_message($recoveryModel, [], [
    'onMissingArgument' => static fn(array $context): string => '[missing ' . $context['variableName'] . ']',
]);
if ($recovered['value'] !== 'Hello [missing name]' || count($recovered['errors']) !== 1) {
    throw new RuntimeException('Unexpected recovery result: ' . json_encode($recovered, JSON_UNESCAPED_UNICODE));
}
echo 'recovery[en] -> ' . json_encode($recovered['value'], JSON_UNESCAPED_UNICODE) . "\n";

function demo_arguments(string $id): array
{
    return match ($id) {
        'welcome' => ['name' => 'Mojito'],
        'cart.items' => ['count' => 2],
        'cart.items.ru' => ['count' => 5],
        'assignee.files' => ['gender' => 'female', 'count' => 3],
        'rich.link' => ['name' => 'Jean', 'url' => '/people/jean'],
        'file.saved' => ['name' => 'שלום.txt'],
        default => [],
    };
}
