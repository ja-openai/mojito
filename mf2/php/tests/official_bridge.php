<?php

declare(strict_types=1);

use Mojito\MessageFormat2\FunctionRegistry;
use Mojito\MessageFormat2\IntlFunctions;
use function Mojito\MessageFormat2\parse_to_model;
use function Mojito\MessageFormat2\format_message;
use function Mojito\MessageFormat2\format_message_to_parts;
use function Mojito\MessageFormat2\Internal\error_code;

define('MF2_OFFICIAL_BRIDGE', true);
require_once __DIR__ . '/unicode_tests.php';

while (($line = fgets(STDIN)) !== false) {
    $request = json_decode($line, true, 512, JSON_THROW_ON_ERROR);
    $parsed = parse_to_model($request['source']);
    $response = ['diagnostics' => array_column($parsed['diagnostics'], 'code'), 'value' => '', 'errors' => [], 'parts' => []];
    if (!$parsed['hasDiagnostics']) {
        $base = ($request['registry'] ?? 'portable') === 'platform' ? IntlFunctions::registry() : FunctionRegistry::portable();
        $registry = $base->withFunction('test:function', 'official_test_function')->withFunction('test:select', 'official_test_select_resolver')->withFunction('test:format', 'official_test_format_resolver')
            ->withSelector('test:function', 'official_test_selector')->withSelector('test:select', 'official_test_selector')->withSelector('test:format', 'official_test_format_selector');
        $options = ['locale' => $request['locale'] ?? 'en', 'bidiIsolation' => $request['bidiIsolation'] ?? 'none', 'functions' => $registry];
        $result = format_message($parsed['model'], $request['arguments'] ?? [], $options);
        $parts = format_message_to_parts($parsed['model'], $request['arguments'] ?? [], $options);
        $response['value'] = $result['value'];
        $response['errors'] = array_map(error_code(...), $result['errors']);
        $response['parts'] = $parts['parts'];
        $response['partsErrors'] = array_map(error_code(...), $parts['errors']);
    }
    echo json_encode($response, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE) . "\n";
}
