<?php

declare(strict_types=1);

use function Mojito\MessageFormat2\parse_to_model;
use function Mojito\MessageFormat2\format_message;
use function Mojito\MessageFormat2\format_message_to_parts;

require_once __DIR__ . '/../src/bootstrap.php';

function runtime_regressions(): void
{
    numeric_source_regressions();
    numeric_conversion_regressions();
    $registry = Mojito\MessageFormat2\FunctionRegistry::portable();
    $probe = $registry->withFunction('probe', static fn(array $call): string => $call['optionValue']('u:dir', 'removed'));
    $probeResult = format_message(parse_to_model('{:probe u:dir=$direction}')['model'], ['direction' => 'rtl'], ['functions' => $probe]);
    if ($probeResult['value'] !== 'removed' || $probeResult['hasErrors']) throw new RuntimeException('u:dir reached callback');
    $numeric = parse_to_model('{1 :number}')['model'];
    foreach (['en', 'ar', 'ar-Latn', 'en-Arab', 'en-Qaaa', 'zz', 'und', 'az-IR', 'sd-IN', 'en-u-nu-arab'] as $locale) {
        $ltr = in_array($locale, ['en', 'ar-Latn', 'sd-IN', 'en-u-nu-arab'], true);
        $result = format_message($numeric, [], ['locale' => $locale, 'bidiIsolation' => 'default', 'functions' => $registry]);
        if ($result['value'] !== ($ltr ? '1' : "\u{2068}1\u{2069}")) throw new RuntimeException('numeric direction ' . $locale);
    }
    $custom = $registry->withFunction('number', static fn(array $call): string => 'custom');
    if (format_message($numeric, [], ['bidiIsolation' => 'default', 'functions' => $custom])['value'] !== "\u{2068}custom\u{2069}") throw new RuntimeException('Custom override isolation');
    $selector = $registry->withSelector('number', static fn(array $match): ?int => null);
    if (format_message($numeric, [], ['bidiIsolation' => 'default', 'functions' => $selector])['value'] !== '1') throw new RuntimeException('Selector override retains direction');
    $directed = parse_to_model('.local $n = {1 :number u:dir=rtl}' . "\n" . '{{{$n}}}')['model'];
    if (format_message($directed, [], ['bidiIsolation' => 'default', 'functions' => $registry])['value'] !== "\u{2067}1\u{2069}") throw new RuntimeException('Inherited explicit direction');

    foreach (['{1 :number minimumFractionDigits=1001}', '{1 :number maximumFractionDigits=1001}', '{1 :number minimumFractionDigits=3 maximumFractionDigits=2}', '{1e-100000 :offset add=1}'] as $source) {
        $result = format_message(parse_to_model($source)['model']);
        if (!$result['hasErrors']) throw new RuntimeException('Accepted out of bounds numeric option: ' . $source);
    }
    $result = format_message(parse_to_model('{1 :number minimumFractionDigits=1000}')['model']);
    if ($result['hasErrors'] || $result['value'] !== '1.' . str_repeat('0', 1000)) throw new RuntimeException('Valid fraction boundary rejected.');
    $model = parse_to_model('{#a @role=|old|}Hello{/a}')['model'];
    $parts = format_message_to_parts($model);
    $parts['parts'][0]['attributes']['role']['value'] = 'new';
    if (format_message_to_parts($model)['parts'][0]['attributes']['role']['value'] !== 'old') throw new RuntimeException('Parts mutation changed catalog.');
}

function numeric_source_regressions(): void
{
    $portable = Mojito\MessageFormat2\FunctionRegistry::portable();
    $intl = Mojito\MessageFormat2\IntlFunctions::registry();
    $source = '.input {$n :number maximumFractionDigits=0}' . "\n";
    $previous = 'n';
    for ($index = 0; $index < 1000; ++$index) {
        $name = 'v' . $index;
        $source .= '.local $' . $name . ' = {$' . $previous . ' :number}' . "\n";
        $previous = $name;
    }
    $source .= '.local $alias = {$' . $previous . '}' . "\n" . '{{{$alias}|{$alias :number maximumFractionDigits=2}}}';
    $model = parse_to_model($source)['model'];
    foreach ([$portable, $intl] as $registry) {
        foreach ([['n' => 1.25, 'expected' => '1|1.25'], ['n' => 2.75, 'expected' => '3|2.75']] as $case) {
            $result = format_message($model, ['n' => $case['n']], ['functions' => $registry]);
            if ($result['hasErrors'] || $result['value'] !== $case['expected']) throw new RuntimeException('Long numeric source or independent format: ' . json_encode($result));
        }
    }
    $offsets = '.input {$n :number}' . "\n";
    $previous = 'n';
    for ($index = 0; $index < 400; ++$index) {
        $name = 'v' . $index;
        $offsets .= '.local $' . $name . ' = {$' . $previous . ' :offset add=1}' . "\n";
        $previous = $name;
    }
    $offsets .= '{{{$' . $previous . ' :number maximumFractionDigits=2}}}';
    $result = format_message(parse_to_model($offsets)['model'], ['n' => '123456789012345.25']);
    if ($result['hasErrors'] || $result['value'] !== '123456789012745.25') throw new RuntimeException('Long exact offset source: ' . json_encode($result));

    $variable = parse_to_model('.input {$n :number maximumFractionDigits=$digits}' . "\n"
        . '.local $warm = {$n :number}' . "\n"
        . '.input {$digits :offset add=1}' . "\n"
        . '{{{$n :number}}}')['model'];
    foreach ([$portable, $intl] as $registry) {
        $result = format_message($variable, ['n' => 1.23456, 'digits' => 2], ['functions' => $registry]);
        if ($result['hasErrors'] || $result['value'] !== '1.235') throw new RuntimeException('Variable option was cached: ' . json_encode($result));
    }

    $retained = null;
    $custom = $portable->withFunction('probe', static function (array $call) use ($portable, &$retained): string {
        $retained = $call['inheritedSource'];
        $copy = $retained;
        $copy['inherited']['value'] = '4';
        $value = $portable->format([
            'function' => ['name' => 'number'], 'value' => $call['value'],
            'rawValue' => $call['rawValue'], 'locale' => 'en',
            'inheritedSource' => $copy, 'optionValue' => static fn(string $name, mixed $fallback): mixed => $fallback,
        ]);
        if ($value !== '5') throw new RuntimeException('Copied source shared a stale operand.');
        return $call['value'];
    });
    $copyModel = parse_to_model('.local $base = {1 :offset add=1}' . "\n"
        . '.local $warm = {$base :number}' . "\n" . '{{{$warm :probe}|{$warm :number}}}')['model'];
    $snapshot = $copyModel;
    $result = format_message($copyModel, [], ['functions' => $custom]);
    if ($result['hasErrors'] || $result['value'] !== '2|2' || $copyModel !== $snapshot) throw new RuntimeException('Custom source copy changed runtime or model: ' . json_encode($result));
    if (Mojito\MessageFormat2\Internal\numeric_source_operand_text($retained) !== '2') throw new RuntimeException('Retained source changed after format.');

    $callbackError = new RuntimeException('retry option');
    $attempts = 0;
    $retryRegistry = $portable->withFunction('retry', static function (array $call) use ($callbackError, &$attempts): string {
        $source = $call['inheritedSource'];
        $source['function'] = ['name' => 'offset'];
        $source['optionValue'] = static function (string $name, mixed $fallback) use ($callbackError, &$attempts): mixed {
            if ($name !== 'add') return $fallback;
            if (++$attempts === 1) throw $callbackError;
            return '1';
        };
        try {
            Mojito\MessageFormat2\Internal\numeric_source_operand_text($source);
            throw new RuntimeException('Expected first callback failure.');
        } catch (RuntimeException $error) {
            if ($error !== $callbackError) throw $error;
        }
        return Mojito\MessageFormat2\Internal\numeric_source_operand_text($source);
    });
    $retry = parse_to_model('.local $n = {1 :number}' . "\n" . '{{{$n :retry}}}')['model'];
    $result = format_message($retry, [], ['functions' => $retryRegistry]);
    if ($result['hasErrors'] || $result['value'] !== '2' || $attempts !== 2) throw new RuntimeException('Callback failure was cached.');

    foreach ([$portable, $intl] as $registry) {
        foreach (['', ' u:dir=inherit', ' u:dir=auto'] as $option) {
            $directed = parse_to_model('.local $n = {1 :number}' . "\n" . '.local $s = {$n :string' . $option . '}' . "\n" . '{{{$s}}}')['model'];
            $result = format_message($directed, [], ['functions' => $registry, 'bidiIsolation' => 'default']);
            $expected = $option === ' u:dir=auto' ? "\u{2068}1\u{2069}" : '1';
            if ($result['hasErrors'] || $result['value'] !== $expected) throw new RuntimeException('Numeric LTR lost on string reannotation.');
            $parts = format_message_to_parts($directed, [], ['functions' => $registry]);
            if ($option !== ' u:dir=auto' && array_key_exists('direction', $parts['parts'][0])) throw new RuntimeException('Private numeric direction reached public parts.');
        }
    }
}

function numeric_conversion_regressions(): void
{
    set_error_handler(static function (int $code, string $message): never { throw new RuntimeException($message); });
    try {
        $cases = [
            ['{1e19 :integer}', '10000000000000000000'],
            ['{-1e19 :integer}', '-10000000000000000000'],
            ['{9223372036854775807.9 :integer}', '9223372036854775807'],
            ['{-9223372036854775808.9 :integer}', '-9223372036854775808'],
            ['{1e19 :integer signDisplay=always}', '+10000000000000000000'],
            ['{1 :offset add=9223372036854775807}', '9223372036854775808'],
            ['{1 :offset add=-9223372036854775808}', '-9223372036854775807'],
            ['.local $n = {1e19 :integer}' . "\n" . '{{{$n :offset add=1}}}', '10000000000000000001'],
        ];
        foreach ($cases as [$source, $expected]) {
            $result = format_message(parse_to_model($source)['model']);
            if ($result['hasErrors'] || $result['value'] !== $expected) throw new RuntimeException('Integer conversion corrupted value: ' . json_encode($result));
        }
        $native = parse_to_model('{$n}')['model'];
        foreach ([[1e19, '10000000000000000000'], [-1e19, '-10000000000000000000'], [1e-20, '0.00000000000000000001'], [1.234567891234567, '1.234567891234567']] as [$input, $expected]) {
            $result = format_message($native, ['n' => $input]);
            if ($result['hasErrors'] || $result['value'] !== $expected) throw new RuntimeException('Float conversion corrupted value: ' . json_encode($result));
        }
        foreach (['{1 :offset add=9223372036854775808}', '{1 :offset add=-9223372036854775809}', '{1 :offset subtract=-9223372036854775808}', '{1 :offset add=100000000000000000000}'] as $source) {
            $result = format_message(parse_to_model($source)['model']);
            if (array_map(static fn($error): string => $error->mf2Code, $result['errors']) !== ['bad-option']) throw new RuntimeException('Offset conversion silently clamped.');
        }
        $exact = parse_to_model('.input {$n :integer select=exact}' . "\n" . '.match $n' . "\n" . '10000000000000000000 {{exact}}' . "\n" . '* {{other}}')['model'];
        $result = format_message($exact, ['n' => '1e19']);
        if ($result['hasErrors'] || $result['value'] !== 'exact') throw new RuntimeException('Large integer exact selection.');
        foreach (['one {{one}}' . "\n" . '* {{fallback}}', '* {{fallback}}'] as $variants) {
            $model = parse_to_model('.input {$n :integer}' . "\n" . '.match $n' . "\n" . $variants)['model'];
            $result = format_message($model, ['n' => '1e19']);
            if ($result['value'] !== 'fallback' || array_map(static fn($error): string => $error->mf2Code, $result['errors']) !== ['bad-selector']) throw new RuntimeException('Out of range plural selector lost diagnostic.');
        }
        $customSelector = Mojito\MessageFormat2\FunctionRegistry::portable()->withSelector('integer', static fn(array $match): ?int => $match['key'] === 'one' ? 1 : null);
        $customMatch = parse_to_model('.input {$n :integer}' . "\n" . '.match $n' . "\n" . 'one {{custom}}' . "\n" . '* {{fallback}}')['model'];
        $result = format_message($customMatch, ['n' => '1e19'], ['functions' => $customSelector]);
        if ($result['hasErrors'] || $result['value'] !== 'custom') throw new RuntimeException('Built-in numeric category bounds blocked custom selector.');
        $registry = Mojito\MessageFormat2\FunctionRegistry::portable()->withFunction('replace', static fn(array $call): string => '9.8');
        $result = format_message(parse_to_model('.local $n = {1 :replace}' . "\n" . '{{{$n :integer}}}')['model'], [], ['functions' => $registry]);
        if ($result['hasErrors'] || $result['value'] !== '9') throw new RuntimeException('Integer reused a nonnumeric custom source.');
    } finally {
        restore_error_handler();
    }
}
