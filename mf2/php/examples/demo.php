<?php

declare(strict_types=1);

use function MF2\format_message;
use function MF2\format_message_to_parts;
use function MF2\parse_to_model;

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
    echo "{$id}[{$locale}] -> " . json_encode($output, JSON_UNESCAPED_UNICODE) . "\n";
    if ($id === 'rich.link') {
        echo "{$id} parts -> " . json_encode(format_message_to_parts($parsed['model'], $args, ['locale' => 'en']), JSON_UNESCAPED_UNICODE) . "\n";
    }
}

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
