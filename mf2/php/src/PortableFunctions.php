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

function inherited_exact_numeric_source(?array $source, string $targetFunction): bool
{
    if ($source === null || $targetFunction === 'percent' || !is_numeric_function($source['function'])) {
        return false;
    }
    $sourceFunction = (string) ($source['function']['name'] ?? '');
    if ($sourceFunction === 'percent') {
        return false;
    }
    if (source_option_value($source, 'select', null) === 'exact') {
        return true;
    }
    return inherited_exact_numeric_source($source['inherited'], $sourceFunction);
}

function invalid_numeric_selector(array $functionRef, ?array $source): bool
{
    $select = function_option_literal($functionRef, 'select', null);
    return numeric_select_uses_variable($functionRef)
        || ($select !== 'exact' && inherited_exact_numeric_source($source, (string) ($functionRef['name'] ?? '')));
}

function format_number(array $call): string
{
    $value = parse_call_decimal($call, 'Number function requires a numeric operand.');
    $formatted = format_decimal_with_maximum_fraction_digits($value, maximum_fraction_digits($call));
    if (sign_display_always($call) && $value >= 0) {
        $formatted = '+' . $formatted;
    }
    return append_minimum_fraction_digits($formatted, minimum_fraction_digits($call));
}

function select_number(array $match): ?int
{
    if (invalid_numeric_selector($match['function'], $match['inheritedSource'])) {
        throw MF2Error::badSelector('Number selector cannot match this operand.');
    }
    $value = parse_match_decimal($match, 'Number selector requires a numeric operand.');
    validate_numeric_variant_key($match['key']);
    return $match['key'] === numeric_match_operand($match, $value, 'number') ? 2 : null;
}

function format_percent(array $call): string
{
    $value = parse_call_decimal($call, 'Percent function requires a numeric operand.');
    $formatted = format_decimal_with_maximum_fraction_digits($value * 100, maximum_fraction_digits($call));
    if (sign_display_always($call) && $value >= 0) {
        $formatted = '+' . $formatted;
    }
    return append_minimum_fraction_digits($formatted, minimum_fraction_digits($call)) . '%';
}

function select_percent(array $match): ?int
{
    if (invalid_numeric_selector($match['function'], $match['inheritedSource'])) {
        throw MF2Error::badSelector('Percent selector cannot match this operand.');
    }
    $value = parse_match_decimal($match, 'Percent selector requires a numeric operand.');
    validate_numeric_variant_key($match['key']);
    return $match['key'] === numeric_match_operand($match, $value, 'percent') ? 2 : null;
}

function format_integer(array $call): string
{
    $value = parse_call_decimal($call, 'Integer function requires a numeric operand.');
    $integer = (int) ($value < 0 ? ceil($value) : floor($value));
    return sign_display_always($call) && $integer >= 0 ? '+' . $integer : (string) $integer;
}

function select_integer(array $match): ?int
{
    if (invalid_numeric_selector($match['function'], $match['inheritedSource'])) {
        throw MF2Error::badSelector('Integer selector cannot match this operand.');
    }
    $value = parse_match_decimal($match, 'Integer selector requires a numeric operand.');
    validate_numeric_variant_key($match['key']);
    return $match['key'] === numeric_match_operand($match, $value, 'integer') ? 2 : null;
}

function format_offset(array $call): string
{
    $operand = numeric_source_operand_text($call['inheritedSource']);
    if ($operand === null && parse_decimal_number($call['value']) !== null) {
        $operand = value_to_string($call['value']);
    }
    if ($operand === null) {
        throw MF2Error::badOperand('Offset function requires a numeric operand.');
    }
    $result = add_integer_offset_decimal($operand, offset_delta($call));
    if ($result === null) {
        throw MF2Error::badOperand('Offset result is outside the supported numeric range.');
    }
    return sign_display_always($call) && !str_starts_with($result, '-') ? '+' . $result : $result;
}

function select_offset(array $match): ?int
{
    $value = parse_match_decimal($match, 'Offset selector requires a numeric operand.');
    validate_numeric_variant_key($match['key']);
    return $match['key'] === numeric_match_operand($match, $value, 'offset') ? 2 : null;
}

function numeric_match_operand(array $match, float $value, string $functionName): string
{
    $minimum = $match['optionValue']('minimumFractionDigits', '0');
    $maximum = $match['optionValue']('maximumFractionDigits', null);
    $minimumDigits = parse_non_negative_option(
        $minimum ?? '0',
        'minimumFractionDigits option must be a non-negative integer.',
    );
    $maximumDigits = $maximum === null ? null : parse_non_negative_option(
        $maximum,
        'maximumFractionDigits option must be a non-negative integer.',
    );
    return numeric_operand_with_options($value, $functionName, $minimumDigits, $maximumDigits);
}

function validate_numeric_variant_key(string $key): void
{
    if (in_array($key, ['zero', 'one', 'two', 'few', 'many', 'other'], true) || parse_decimal_number($key) !== null) {
        return;
    }
    throw MF2Error::badVariantKey('Numeric selector keys must be number literals or plural keywords.');
}

function parse_call_decimal(array $call, string $message): float
{
    $parsed = parse_source_decimal($call['inheritedSource']);
    if ($parsed === null) {
        $parsed = parse_decimal_number($call['value']);
    }
    if ($parsed === null) {
        throw MF2Error::badOperand($message);
    }
    return $parsed;
}

function numeric_selection_operand(array $resolvedValue, array $functionRef, callable $optionValue): ?string
{
    if (function_option_literal($functionRef, 'select', 'plural') === 'exact') {
        return null;
    }
    $source = $resolvedValue['source'] ?? null;
    $sourceInput = is_decimal_source_function($source['function'] ?? null)
        ? numeric_source_operand_text($source)
        : null;
    $input = ($functionRef['name'] ?? '') === 'offset'
        ? $resolvedValue['rawValue']
        : ($sourceInput ?? $resolvedValue['rawValue']);
    $value = parse_decimal_number($input);
    if ($value === null) {
        return null;
    }
    $minimum = $optionValue('minimumFractionDigits', '0');
    $maximum = $optionValue('maximumFractionDigits', null);
    $minimumDigits = parse_non_negative_option(
        $minimum ?? '0',
        'minimumFractionDigits option must be a non-negative integer.',
    );
    $maximumDigits = $maximum === null ? null : parse_non_negative_option(
        $maximum,
        'maximumFractionDigits option must be a non-negative integer.',
    );

    return numeric_operand_with_options($value, (string) ($functionRef['name'] ?? ''), $minimumDigits, $maximumDigits);
}

function numeric_operand_with_options(float $value, string $functionName, int $minimumDigits, ?int $maximumDigits): string
{
    if ($functionName === 'integer') {
        return (string) (int) ($value < 0 ? ceil($value) : floor($value));
    }
    if ($functionName === 'percent') {
        $value *= 100;
    }
    if (in_array($functionName, ['number', 'percent'], true)) {
        return append_minimum_fraction_digits(
            format_decimal_with_maximum_fraction_digits($value, $maximumDigits),
            $minimumDigits,
        );
    }
    return value_to_string($value);
}

function inherits_numeric_options_from(string $targetFunction, string $sourceFunction): bool
{
    return match ($targetFunction) {
        'number', 'integer', 'percent', 'offset' => in_array($sourceFunction, ['number', 'integer', 'percent', 'offset'], true),
        'currency' => $sourceFunction === 'currency',
        default => false,
    };
}

function numeric_option_is_discarded(string $functionName, string $optionName): bool
{
    return match ($functionName) {
        'integer' => in_array($optionName, ['minimumFractionDigits', 'maximumFractionDigits', 'minimumSignificantDigits'], true),
        'percent' => in_array($optionName, ['minimumIntegerDigits', 'roundingIncrement', 'select'], true),
        'offset' => in_array($optionName, ['add', 'subtract'], true),
        default => false,
    };
}

function parse_match_decimal(array $match, string $message): float
{
    $parsed = parse_source_decimal($match['inheritedSource']);
    if ($parsed === null) {
        $parsed = parse_decimal_number($match['value']);
    }
    if ($parsed === null) {
        throw MF2Error::badSelector($message);
    }
    return $parsed;
}

function parse_source_decimal(?array $source): ?float
{
    if ($source === null || !is_decimal_source_function($source['function'])) {
        return null;
    }
    return parse_decimal_number(numeric_source_operand_text($source));
}

function numeric_source_operand(?array $source): ?float
{
    return parse_decimal_number(numeric_source_operand_text($source));
}

function numeric_source_operand_text(?array $source): ?string
{
    if ($source === null) {
        return null;
    }
    $operand = numeric_source_operand_text($source['inherited']);
    if ($operand === null) {
        $operand = parse_decimal_number($source['value']) === null
            ? null
            : value_to_string($source['value']);
    }
    if (!is_decimal_source_function($source['function']) || $operand === null) {
        return $operand;
    }
    return match ($source['function']['name'] ?? '') {
        'integer' => truncate_decimal_operand($operand),
        'offset' => apply_source_offset($source, $operand),
        default => canonical_decimal_operand($operand),
    };
}

function apply_source_offset(array $source, string $operand): ?string
{
    $add = source_option_value($source, 'add', null);
    $subtract = source_option_value($source, 'subtract', null);
    if (($add === null) === ($subtract === null)) {
        return null;
    }
    try {
        $delta = parse_integer($add ?? $subtract);
    } catch (\Throwable) {
        return null;
    }
    if ($delta === null) {
        return null;
    }
    return add_integer_offset_decimal($operand, $add !== null ? $delta : -$delta);
}

const MAX_EXPANDED_DECIMAL_DIGITS = 4096;

function canonical_decimal_operand(string $value): ?string
{
    $decimal = parse_scaled_decimal($value);
    return $decimal === null ? null : format_scaled_decimal($decimal);
}

function truncate_decimal_operand(string $value): ?string
{
    $decimal = parse_scaled_decimal($value);
    if ($decimal === null || $decimal['scale'] >= strlen($decimal['digits'])) {
        return $decimal === null ? null : '0';
    }
    $digits = substr($decimal['digits'], 0, strlen($decimal['digits']) - $decimal['scale']);
    return ($decimal['negative'] ? '-' : '') . normalize_magnitude($digits);
}

function add_integer_offset_decimal(string $value, int $delta): ?string
{
    $decimal = parse_scaled_decimal($value);
    if ($decimal === null || $delta === 0) {
        return $decimal === null ? null : format_scaled_decimal($decimal);
    }
    $deltaText = (string) $delta;
    $deltaNegative = str_starts_with($deltaText, '-');
    $deltaDigits = ltrim($deltaText, '+-') . str_repeat('0', $decimal['scale']);
    if ($decimal['negative'] === $deltaNegative) {
        $decimal['digits'] = add_magnitudes($decimal['digits'], $deltaDigits);
    } else {
        $comparison = compare_magnitudes($decimal['digits'], $deltaDigits);
        if ($comparison > 0) {
            $decimal['digits'] = subtract_magnitudes($decimal['digits'], $deltaDigits);
        } elseif ($comparison < 0) {
            $decimal['digits'] = subtract_magnitudes($deltaDigits, $decimal['digits']);
            $decimal['negative'] = $deltaNegative;
        } else {
            $decimal['digits'] = '0';
            $decimal['negative'] = false;
        }
    }
    return format_scaled_decimal($decimal);
}

/** @return array{negative: bool, digits: string, scale: int}|null */
function parse_scaled_decimal(string $value): ?array
{
    if (preg_match('/^(-?)(0|[1-9]\d*)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/', $value, $matches) !== 1) {
        return null;
    }
    $fraction = $matches[3] ?? '';
    $exponentText = $matches[4] ?? '0';
    $unsignedExponent = ltrim($exponentText, '+-0');
    if (strlen($unsignedExponent) > 4) {
        return null;
    }
    $exponent = (int) $exponentText;
    if (abs($exponent) > MAX_EXPANDED_DECIMAL_DIGITS) {
        return null;
    }
    $digits = normalize_magnitude($matches[2] . $fraction);
    if ($digits === '0') {
        return ['negative' => false, 'digits' => '0', 'scale' => 0];
    }
    $scale = strlen($fraction) - $exponent;
    if ($scale < 0) {
        $zeroes = -$scale;
        if ($zeroes > MAX_EXPANDED_DECIMAL_DIGITS) {
            return null;
        }
        $digits .= str_repeat('0', $zeroes);
        $scale = 0;
    }
    if ($scale > MAX_EXPANDED_DECIMAL_DIGITS) {
        return null;
    }
    return ['negative' => $matches[1] === '-', 'digits' => $digits, 'scale' => $scale];
}

/** @param array{negative: bool, digits: string, scale: int} $decimal */
function format_scaled_decimal(array $decimal): string
{
    $digits = normalize_magnitude($decimal['digits']);
    if ($digits === '0') {
        return '0';
    }
    if ($decimal['scale'] > 0) {
        $digits = str_pad($digits, $decimal['scale'] + 1, '0', STR_PAD_LEFT);
        $split = strlen($digits) - $decimal['scale'];
        $fraction = rtrim(substr($digits, $split), '0');
        $digits = $fraction === ''
            ? substr($digits, 0, $split)
            : substr($digits, 0, $split) . '.' . $fraction;
    }
    return ($decimal['negative'] ? '-' : '') . $digits;
}

function normalize_magnitude(string $digits): string
{
    $normalized = ltrim($digits, '0');
    return $normalized === '' ? '0' : $normalized;
}

function compare_magnitudes(string $left, string $right): int
{
    $left = normalize_magnitude($left);
    $right = normalize_magnitude($right);
    $lengthComparison = strlen($left) <=> strlen($right);
    return $lengthComparison !== 0 ? $lengthComparison : strcmp($left, $right);
}

function add_magnitudes(string $left, string $right): string
{
    $output = '';
    $leftIndex = strlen($left) - 1;
    $rightIndex = strlen($right) - 1;
    $carry = 0;
    while ($leftIndex >= 0 || $rightIndex >= 0 || $carry > 0) {
        $sum = $carry;
        if ($leftIndex >= 0) {
            $sum += ord($left[$leftIndex--]) - ord('0');
        }
        if ($rightIndex >= 0) {
            $sum += ord($right[$rightIndex--]) - ord('0');
        }
        $output .= (string) ($sum % 10);
        $carry = intdiv($sum, 10);
    }
    return strrev($output);
}

function subtract_magnitudes(string $left, string $right): string
{
    $output = '';
    $rightIndex = strlen($right) - 1;
    $borrow = 0;
    for ($leftIndex = strlen($left) - 1; $leftIndex >= 0; $leftIndex -= 1) {
        $rightDigit = $rightIndex >= 0 ? ord($right[$rightIndex--]) - ord('0') : 0;
        $difference = ord($left[$leftIndex]) - ord('0') - $rightDigit - $borrow;
        if ($difference < 0) {
            $difference += 10;
            $borrow = 1;
        } else {
            $borrow = 0;
        }
        $output .= (string) $difference;
    }
    return normalize_magnitude(strrev($output));
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

function sign_display_always(array $call): bool
{
    return $call['optionValue']('signDisplay', null) === 'always';
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
