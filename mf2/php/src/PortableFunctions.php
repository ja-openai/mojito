<?php

declare(strict_types=1);

namespace Mojito\MessageFormat2\Internal;

use Mojito\MessageFormat2\FunctionRegistry;
use Mojito\MessageFormat2\MF2Error;

function portable_function_registry(): FunctionRegistry
{
    return new FunctionRegistry(
        [
            'string' => static fn(array $call): string => $call['value'],
            'number' => __NAMESPACE__ . '\\format_number',
            'percent' => __NAMESPACE__ . '\\format_percent',
            'integer' => __NAMESPACE__ . '\\format_integer',
            'offset' => __NAMESPACE__ . '\\format_offset',
        ],
        [
            'number' => __NAMESPACE__ . '\\select_number',
            'percent' => __NAMESPACE__ . '\\select_percent',
            'integer' => __NAMESPACE__ . '\\select_integer',
            'offset' => __NAMESPACE__ . '\\select_offset',
        ],
    );
}

function function_option_literal(array $functionRef, string $name, mixed $fallback): mixed
{
    $option = $functionRef['options'][$name] ?? null;
    return ($option['type'] ?? null) === 'literal' ? ($option['value'] ?? '') : $fallback;
}

function source_option_value(?array $source, string $name, mixed $fallback): mixed
{
    if ($source === null) {
        return $fallback;
    }
    if (isset($source['optionValue']) && is_callable($source['optionValue'])) {
        return $source['optionValue']($name, $fallback);
    }
    return function_option_literal($source['function'], $name, $fallback);
}

function is_numeric_function(?array $functionRef): bool
{
    return in_array($functionRef['name'] ?? '', ['number', 'integer', 'percent', 'offset'], true);
}

function is_decimal_source_function(?array $functionRef): bool
{
    return is_numeric_function($functionRef) || ($functionRef['name'] ?? '') === 'currency';
}

function numeric_select_uses_variable(?array $functionRef): bool
{
    return ($functionRef['options']['select']['type'] ?? null) === 'variable';
}

function inherited_exact_numeric_source(?array $source): bool
{
    if ($source === null) {
        return false;
    }
    if (is_numeric_function($source['function']) && source_option_value($source, 'select', null) === 'exact') {
        return true;
    }
    return inherited_exact_numeric_source($source['inherited']);
}

function invalid_numeric_selector(array $functionRef, ?array $source): bool
{
    $select = function_option_literal($functionRef, 'select', null);
    return numeric_select_uses_variable($functionRef) || ($select !== 'exact' && inherited_exact_numeric_source($source));
}

function format_number(array $call): string
{
    $value = parse_call_decimal($call, 'Number function requires a numeric operand.');
    return format_decimal_number($value, sign_display_always($call['function']), minimum_fraction_digits($call));
}

function select_number(array $match): ?int
{
    if (invalid_numeric_selector($match['function'], $match['inheritedSource'])) {
        throw MF2Error::badSelector('Number selector cannot match this operand.');
    }
    $value = parse_match_decimal($match, 'Number selector requires a numeric operand.');
    $key = parse_decimal_number($match['key']);
    return $key !== null && $value == $key ? 1 : null;
}

function format_percent(array $call): string
{
    $value = parse_call_decimal($call, 'Percent function requires a numeric operand.');
    $formatted = format_decimal_with_maximum_fraction_digits($value * 100, maximum_fraction_digits($call));
    if (sign_display_always($call['function']) && $value >= 0) {
        $formatted = '+' . $formatted;
    }
    return append_minimum_fraction_digits($formatted, minimum_fraction_digits($call)) . '%';
}

function select_percent(array $match): ?int
{
    if (invalid_numeric_selector($match['function'], $match['inheritedSource'])) {
        throw MF2Error::badSelector('Percent selector cannot match this operand.');
    }
    $value = parse_match_decimal($match, 'Percent selector requires a numeric operand.') * 100;
    $key = parse_decimal_number($match['key']);
    return $key !== null && $value == $key ? 1 : null;
}

function format_integer(array $call): string
{
    $value = parse_call_decimal($call, 'Integer function requires a numeric operand.');
    $integer = (int) ($value < 0 ? ceil($value) : floor($value));
    return sign_display_always($call['function']) && $integer >= 0 ? '+' . $integer : (string) $integer;
}

function select_integer(array $match): ?int
{
    if (invalid_numeric_selector($match['function'], $match['inheritedSource'])) {
        throw MF2Error::badSelector('Integer selector cannot match this operand.');
    }
    $value = parse_match_decimal($match, 'Integer selector requires a numeric operand.');
    $key = parse_integer($match['key']);
    return $key !== null && (int) ($value < 0 ? ceil($value) : floor($value)) === $key ? 1 : null;
}

function format_offset(array $call): string
{
    $value = parse_required_integer($call['value'], 'Offset function requires a numeric operand.');
    $result = $value + offset_delta($call);
    return inherited_sign_display_always($call['inheritedSource']) && $result >= 0 ? '+' . $result : (string) $result;
}

function select_offset(array $match): ?int
{
    $value = parse_required_integer($match['value'], 'Offset selector requires a numeric operand.');
    $key = parse_integer($match['key']);
    return $key !== null && $value === $key ? 1 : null;
}

function parse_call_decimal(array $call, string $message): float
{
    $parsed = parse_decimal_number($call['value']);
    if ($parsed === null) {
        $parsed = parse_source_decimal($call['inheritedSource']);
    }
    if ($parsed === null) {
        throw MF2Error::badOperand($message);
    }
    return $parsed;
}

function parse_match_decimal(array $match, string $message): float
{
    $parsed = parse_decimal_number($match['value']);
    if ($parsed === null) {
        $parsed = parse_source_decimal($match['inheritedSource']);
    }
    if ($parsed === null) {
        throw MF2Error::badSelector($message);
    }
    return $parsed;
}

function parse_source_decimal(?array $source): ?float
{
    if ($source === null) {
        return null;
    }
    if (is_decimal_source_function($source['function'])) {
        return parse_decimal_number($source['value']);
    }
    return parse_source_decimal($source['inherited']);
}

function parse_decimal_number(mixed $value): ?float
{
    $text = value_to_string($value);
    if (preg_match('/^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/', $text) !== 1) {
        return null;
    }
    $parsed = (float) $text;
    return is_finite($parsed) ? $parsed : null;
}

function format_decimal_number(float $value, bool $signAlways, int $minimumFractionDigits): string
{
    $formatted = value_to_string($value);
    if (str_ends_with($formatted, '.0')) {
        $formatted = substr($formatted, 0, -2);
    }
    if ($signAlways && $value >= 0) {
        $formatted = '+' . $formatted;
    }
    return append_minimum_fraction_digits($formatted, $minimumFractionDigits);
}

function format_decimal_with_maximum_fraction_digits(float $value, ?int $digits): string
{
    if ($digits === null) {
        return format_decimal_number($value, false, 0);
    }
    $formatted = number_format($value, $digits, '.', '');
    $formatted = rtrim(rtrim($formatted, '0'), '.');
    return $formatted === '-0' ? '0' : $formatted;
}

function append_minimum_fraction_digits(string $formatted, int $minimumFractionDigits): string
{
    if ($minimumFractionDigits === 0) {
        return $formatted;
    }
    $dot = strpos($formatted, '.');
    $fractionDigits = $dot === false ? 0 : strlen($formatted) - $dot - 1;
    $output = $formatted;
    if ($fractionDigits === 0) {
        $output .= '.';
    }
    for ($index = $fractionDigits; $index < $minimumFractionDigits; $index += 1) {
        $output .= '0';
    }
    return $output;
}

function minimum_fraction_digits(array $call): int
{
    $value = $call['optionValue']('minimumFractionDigits', null);
    return $value === null ? 0 : parse_non_negative_option($value, 'minimumFractionDigits option must be a non-negative integer.');
}

function maximum_fraction_digits(array $call): ?int
{
    $value = $call['optionValue']('maximumFractionDigits', null);
    return $value === null ? null : parse_non_negative_option($value, 'maximumFractionDigits option must be a non-negative integer.');
}

function parse_non_negative_option(mixed $value, string $message): int
{
    if (preg_match('/^\d+$/', value_to_string($value)) !== 1) {
        throw MF2Error::badOption($message);
    }
    return (int) $value;
}

function sign_display_always(array $functionRef): bool
{
    return function_option_literal($functionRef, 'signDisplay', null) === 'always';
}

function inherited_sign_display_always(?array $source): bool
{
    if ($source === null) {
        return false;
    }
    if (in_array($source['function']['name'] ?? '', ['number', 'integer'], true) && source_option_value($source, 'signDisplay', null) === 'always') {
        return true;
    }
    return inherited_sign_display_always($source['inherited']);
}

function offset_delta(array $call): int
{
    $add = $call['optionValue']('add', null);
    $subtract = $call['optionValue']('subtract', null);
    if (($add === null && $subtract === null) || ($add !== null && $subtract !== null)) {
        throw MF2Error::badOption('Offset function requires exactly one of add or subtract.');
    }
    $value = parse_integer($add ?? $subtract);
    if ($value === null) {
        throw MF2Error::badOption($add !== null ? 'Offset add option must be an integer.' : 'Offset subtract option must be an integer.');
    }
    return $add !== null ? $value : -$value;
}

function parse_required_integer(mixed $value, string $message): int
{
    $parsed = parse_integer($value);
    if ($parsed === null) {
        throw MF2Error::badOperand($message);
    }
    return $parsed;
}

function parse_integer(mixed $value): ?int
{
    $text = value_to_string($value);
    if (preg_match('/^[+-]?\d+$/', $text) !== 1) {
        return null;
    }
    return (int) $text;
}
