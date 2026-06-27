<?php

declare(strict_types=1);

namespace Mojito\MessageFormat2\Internal;

use Mojito\MessageFormat2\FunctionRegistry;
use Mojito\MessageFormat2\MF2Error;

const MAX_FRACTION_DIGITS = 100;
const MAX_DECIMAL_OPERAND_LENGTH = 256;
const MAX_DECIMAL_OUTPUT_CHARS = 1000;
const MAX_OFFSET_INTEGER = '1000000000000000000000';
const MAX_SAFE_FLOAT_INTEGER = 9007199254740991.0;

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
    return is_numeric_function($functionRef) || in_array($functionRef['name'] ?? '', ['currency', 'relativeTime'], true);
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
    $message = 'Number function requires a numeric operand.';
    $value = parse_call_decimal_operand($call, $message);
    $minimumFractionDigits = minimum_fraction_digits($call);
    $maximumFractionDigits = maximum_fraction_digits($call);
    validate_fraction_digits($minimumFractionDigits, $maximumFractionDigits);
    $rounded = round_decimal_operand_to_maximum_fraction_digits($value, $maximumFractionDigits);
    ensure_decimal_output_bounded($rounded, $minimumFractionDigits, $message);
    return format_decimal_operand($rounded, sign_display_always($call['function']), $minimumFractionDigits);
}

function select_number(array $match): ?int
{
    if (invalid_numeric_selector($match['function'], $match['inheritedSource'])) {
        throw MF2Error::badSelector('Number selector cannot match this operand.');
    }
    $value = parse_match_decimal_operand($match, 'Number selector requires a numeric operand.');
    $key = parse_decimal_operand($match['key']);
    return $key !== null && decimal_operands_equal($value, $key) ? 2 : null;
}

function format_percent(array $call): string
{
    $message = 'Percent function requires a numeric operand.';
    $value = parse_call_decimal_operand($call, $message);
    $minimumFractionDigits = minimum_fraction_digits($call);
    $maximumFractionDigits = maximum_fraction_digits($call);
    validate_fraction_digits($minimumFractionDigits, $maximumFractionDigits);
    $percentValue = round_decimal_operand_to_maximum_fraction_digits(
        shift_decimal_operand($value, 2),
        $maximumFractionDigits,
    );
    ensure_decimal_output_bounded($percentValue, $minimumFractionDigits, $message);
    $formatted = format_decimal_operand($percentValue, false, 0);
    if (sign_display_always($call['function']) && !$value['negative']) {
        $formatted = '+' . $formatted;
    }
    return append_minimum_fraction_digits($formatted, $minimumFractionDigits) . '%';
}

function select_percent(array $match): ?int
{
    if (invalid_numeric_selector($match['function'], $match['inheritedSource'])) {
        throw MF2Error::badSelector('Percent selector cannot match this operand.');
    }
    $value = shift_decimal_operand(parse_match_decimal_operand($match, 'Percent selector requires a numeric operand.'), 2);
    $key = parse_decimal_operand($match['key']);
    return $key !== null && decimal_operands_equal($value, $key) ? 2 : null;
}

function format_integer(array $call): string
{
    $message = 'Integer function requires a numeric operand.';
    $integer = truncate_decimal_operand_to_integer(parse_call_decimal_operand($call, $message));
    ensure_decimal_output_bounded($integer, 0, $message);
    $formatted = format_decimal_operand($integer, false, 0);
    return sign_display_always($call['function']) && !$integer['negative'] ? '+' . $formatted : $formatted;
}

function select_integer(array $match): ?int
{
    if (invalid_numeric_selector($match['function'], $match['inheritedSource'])) {
        throw MF2Error::badSelector('Integer selector cannot match this operand.');
    }
    $value = truncate_decimal_operand_to_integer(parse_match_decimal_operand($match, 'Integer selector requires a numeric operand.'));
    $key = parse_integer_operand($match['key']);
    return $key !== null && decimal_operands_equal($value, $key) ? 2 : null;
}

function format_offset(array $call): string
{
    $value = parse_required_offset_integer($call['rawValue'] ?? $call['value'], 'Offset function requires a numeric operand.');
    $result = offset_integer_add($value, offset_delta($call));
    if (!offset_integer_in_range($result)) {
        throw MF2Error::badOperand('Offset result is outside the supported integer range.');
    }
    return inherited_sign_display_always($call['inheritedSource']) && !str_starts_with($result, '-') ? '+' . $result : $result;
}

function select_offset(array $match): ?int
{
    $value = parse_required_offset_integer($match['rawValue'] ?? $match['value'], 'Offset selector requires a numeric operand.');
    $key = parse_offset_integer($match['key']);
    return $key !== null && $value === $key ? 2 : null;
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

function parse_match_decimal_operand(array $match, string $message): array
{
    $parsed = parse_source_decimal_operand($match['inheritedSource']);
    if ($parsed === null) {
        $parsed = parse_decimal_operand($match['value']);
    }
    if ($parsed === null) {
        throw MF2Error::badSelector($message);
    }
    return $parsed;
}

function parse_call_decimal_operand(array $call, string $message): array
{
    $parsed = parse_decimal_operand($call['value']);
    if ($parsed === null) {
        $parsed = parse_source_decimal_operand($call['inheritedSource']);
    }
    if ($parsed === null) {
        throw MF2Error::badOperand($message);
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

function parse_source_decimal_operand(?array $source): ?array
{
    if ($source === null) {
        return null;
    }
    if (is_decimal_source_function($source['function'])) {
        return parse_decimal_operand($source['value']);
    }
    return parse_source_decimal_operand($source['inherited']);
}

function parse_decimal_number(mixed $value): ?float
{
    $text = value_to_string($value);
    if (strlen($text) > MAX_DECIMAL_OPERAND_LENGTH) {
        return null;
    }
    if (preg_match('/^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/', $text) !== 1) {
        return null;
    }
    $parsed = (float) $text;
    return is_finite($parsed) ? $parsed : null;
}

function parse_decimal_operand(mixed $value): ?array
{
    $text = value_to_string($value);
    if (strlen($text) > MAX_DECIMAL_OPERAND_LENGTH) {
        return null;
    }
    if (preg_match('/^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/', $text) !== 1) {
        return null;
    }
    $negative = str_starts_with($text, '-');
    $body = $negative ? substr($text, 1) : $text;
    [$significand, $exponentText] = split_decimal_exponent($body);
    $exponent = parse_bounded_decimal_exponent($exponentText);
    if ($exponent === null) {
        return null;
    }
    $pieces = explode('.', $significand, 2);
    $fraction = $pieces[1] ?? '';
    return normalize_decimal_operand($negative, $pieces[0] . $fraction, strlen($fraction) - $exponent);
}

function parse_integer_operand(mixed $value): ?array
{
    $text = value_to_string($value);
    if (strlen($text) > MAX_DECIMAL_OPERAND_LENGTH) {
        return null;
    }
    if (preg_match('/^[+-]?\d+$/', $text) !== 1) {
        return null;
    }
    $negative = str_starts_with($text, '-');
    $digits = ($negative || str_starts_with($text, '+')) ? substr($text, 1) : $text;
    return normalize_decimal_operand($negative, $digits, 0);
}

function split_decimal_exponent(string $value): array
{
    $e = stripos($value, 'e');
    if ($e === false) {
        return [$value, ''];
    }
    return [substr($value, 0, $e), substr($value, $e + 1)];
}

function parse_bounded_decimal_exponent(string $value): ?int
{
    if ($value === '') {
        return 0;
    }
    $negative = str_starts_with($value, '-');
    $digits = ($negative || str_starts_with($value, '+')) ? substr($value, 1) : $value;
    $digits = ltrim($digits, '0');
    if ($digits === '') {
        return 0;
    }
    if (strlen($digits) > 7) {
        return null;
    }
    $parsed = (int) $digits;
    if ($parsed > 1000000) {
        return null;
    }
    return $negative ? -$parsed : $parsed;
}

function normalize_decimal_operand(bool $negative, string $digits, int $scale): array
{
    $digits = ltrim($digits, '0');
    if ($digits === '') {
        return ['negative' => false, 'digits' => '0', 'scale' => 0];
    }
    while (str_ends_with($digits, '0')) {
        $digits = substr($digits, 0, -1);
        $scale -= 1;
    }
    return ['negative' => $negative, 'digits' => $digits, 'scale' => $scale];
}

function decimal_operands_equal(array $left, array $right): bool
{
    return $left['negative'] === $right['negative'] && $left['digits'] === $right['digits'] && $left['scale'] === $right['scale'];
}

function shift_decimal_operand(array $operand, int $places): array
{
    return normalize_decimal_operand($operand['negative'], $operand['digits'], $operand['scale'] - $places);
}

function truncate_decimal_operand_to_integer(array $operand): array
{
    if ($operand['scale'] <= 0) {
        return $operand;
    }
    $keep = strlen($operand['digits']) - $operand['scale'];
    if ($keep <= 0) {
        return normalize_decimal_operand(false, '0', 0);
    }
    return normalize_decimal_operand($operand['negative'], substr($operand['digits'], 0, $keep), 0);
}

function format_decimal_operand(array $operand, bool $signAlways, int $minimumFractionDigits): string
{
    $formatted = decimal_operand_to_string($operand);
    if ($signAlways && !$operand['negative']) {
        $formatted = '+' . $formatted;
    }
    return append_minimum_fraction_digits($formatted, $minimumFractionDigits);
}

function decimal_operand_to_string(array $operand): string
{
    $sign = $operand['negative'] ? '-' : '';
    if ($operand['scale'] <= 0) {
        return $sign . $operand['digits'] . str_repeat('0', -$operand['scale']);
    }
    if ($operand['scale'] >= strlen($operand['digits'])) {
        return $sign . '0.' . str_repeat('0', $operand['scale'] - strlen($operand['digits'])) . $operand['digits'];
    }
    $integerDigits = strlen($operand['digits']) - $operand['scale'];
    return $sign . substr($operand['digits'], 0, $integerDigits) . '.' . substr($operand['digits'], $integerDigits);
}

function round_decimal_operand_to_maximum_fraction_digits(array $operand, ?int $maximumFractionDigits): array
{
    if ($maximumFractionDigits === null || $operand['scale'] <= $maximumFractionDigits) {
        return $operand;
    }
    $drop = $operand['scale'] - $maximumFractionDigits;
    $keep = strlen($operand['digits']) - $drop;
    $kept = $keep > 0 ? substr($operand['digits'], 0, $keep) : '0';
    $remainder = $keep > 0 ? substr($operand['digits'], $keep) : $operand['digits'];
    $rounded = ltrim($kept, '0');
    if ($rounded === '') {
        $rounded = '0';
    }
    $comparison = compare_decimal_remainder_to_half($remainder, $drop);
    if ($comparison >= 0) {
        $rounded = increment_decimal_string($rounded);
    }
    return normalize_decimal_operand($operand['negative'], $rounded, $maximumFractionDigits);
}

function compare_decimal_remainder_to_half(string $remainder, int $droppedDigits): int
{
    if (preg_match('/[1-9]/', $remainder) !== 1) {
        return -1;
    }
    if (strlen($remainder) < $droppedDigits) {
        return -1;
    }
    if ($remainder[0] < '5') {
        return -1;
    }
    if ($remainder[0] > '5') {
        return 1;
    }
    return preg_match('/[1-9]/', substr($remainder, 1)) === 1 ? 1 : 0;
}

function increment_decimal_string(string $value): string
{
    $digits = str_split($value);
    for ($index = count($digits) - 1; $index >= 0; $index -= 1) {
        if ($digits[$index] !== '9') {
            $digits[$index] = chr(ord($digits[$index]) + 1);
            return implode('', $digits);
        }
        $digits[$index] = '0';
    }
    return '1' . implode('', $digits);
}

function ensure_decimal_output_bounded(array $operand, int $minimumFractionDigits, string $message): void
{
    if (estimated_decimal_output_chars($operand, $minimumFractionDigits) > MAX_DECIMAL_OUTPUT_CHARS) {
        throw MF2Error::badOperand($message);
    }
}

function estimated_decimal_output_chars(array $operand, int $minimumFractionDigits): int
{
    $sign = $operand['negative'] ? 1 : 0;
    if ($operand['scale'] <= 0) {
        return $sign + strlen($operand['digits']) - $operand['scale'];
    }
    $integerDigits = max(strlen($operand['digits']) - $operand['scale'], 1);
    $fractionDigits = max($operand['scale'], $minimumFractionDigits);
    return $sign + $integerDigits + ($fractionDigits > 0 ? 1 + $fractionDigits : 0);
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

function validate_fraction_digits(int $minimumFractionDigits, ?int $maximumFractionDigits): void
{
    if ($maximumFractionDigits !== null && $minimumFractionDigits > $maximumFractionDigits) {
        throw MF2Error::badOption('maximumFractionDigits must be greater than or equal to minimumFractionDigits.');
    }
}

function parse_non_negative_option(mixed $value, string $message): int
{
    $text = value_to_string($value);
    if (strlen($text) > MAX_DECIMAL_OPERAND_LENGTH || preg_match('/^\d+$/', $text) !== 1) {
        throw MF2Error::badOption($message);
    }
    $parsed = (int) $text;
    if ($parsed > MAX_FRACTION_DIGITS) {
        throw MF2Error::badOption($message);
    }
    return $parsed;
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

function offset_delta(array $call): string
{
    $add = $call['optionValue']('add', null);
    $subtract = $call['optionValue']('subtract', null);
    if (($add === null && $subtract === null) || ($add !== null && $subtract !== null)) {
        throw MF2Error::badOption('Offset function requires exactly one of add or subtract.');
    }
    $value = parse_offset_integer($add ?? $subtract);
    if ($value === null) {
        throw MF2Error::badOption($add !== null ? 'Offset add option must be an integer.' : 'Offset subtract option must be an integer.');
    }
    return $add !== null ? $value : offset_integer_negate($value);
}

function parse_required_offset_integer(mixed $value, string $message): string
{
    $parsed = parse_offset_integer($value);
    if ($parsed === null) {
        throw MF2Error::badOperand($message);
    }
    return $parsed;
}

function parse_offset_integer(mixed $value): ?string
{
    if (is_int($value)) {
        return parse_offset_integer_text((string) $value);
    }
    if (is_float($value)) {
        if (!is_finite($value) || floor($value) !== $value || abs($value) > MAX_SAFE_FLOAT_INTEGER) {
            return null;
        }
        return parse_offset_integer_text(sprintf('%.0F', $value));
    }
    return parse_offset_integer_text(value_to_string($value));
}

function parse_offset_integer_text(string $text): ?string
{
    if (preg_match('/^[+-]?[0-9]+$/', $text) !== 1) {
        return null;
    }
    $negative = str_starts_with($text, '-');
    $digits = ($negative || str_starts_with($text, '+')) ? substr($text, 1) : $text;
    $digits = ltrim($digits, '0');
    if ($digits === '') {
        return '0';
    }
    if (!offset_digits_in_range($digits)) {
        return null;
    }
    return $negative ? '-' . $digits : $digits;
}

function offset_integer_add(string $left, string $right): string
{
    $leftNegative = str_starts_with($left, '-');
    $rightNegative = str_starts_with($right, '-');
    $leftDigits = $leftNegative ? substr($left, 1) : $left;
    $rightDigits = $rightNegative ? substr($right, 1) : $right;
    if ($leftNegative === $rightNegative) {
        return normalize_offset_integer($leftNegative, add_offset_digits($leftDigits, $rightDigits));
    }
    $comparison = compare_offset_digits($leftDigits, $rightDigits);
    if ($comparison === 0) {
        return '0';
    }
    if ($comparison > 0) {
        return normalize_offset_integer($leftNegative, subtract_offset_digits($leftDigits, $rightDigits));
    }
    return normalize_offset_integer($rightNegative, subtract_offset_digits($rightDigits, $leftDigits));
}

function offset_integer_negate(string $value): string
{
    return $value === '0' ? '0' : (str_starts_with($value, '-') ? substr($value, 1) : '-' . $value);
}

function offset_integer_in_range(string $value): bool
{
    $digits = str_starts_with($value, '-') ? substr($value, 1) : $value;
    return offset_digits_in_range($digits);
}

function offset_digits_in_range(string $digits): bool
{
    return strlen($digits) < strlen(MAX_OFFSET_INTEGER)
        || (strlen($digits) === strlen(MAX_OFFSET_INTEGER) && strcmp($digits, MAX_OFFSET_INTEGER) < 0);
}

function add_offset_digits(string $left, string $right): string
{
    $carry = 0;
    $result = '';
    $leftIndex = strlen($left) - 1;
    $rightIndex = strlen($right) - 1;
    while ($leftIndex >= 0 || $rightIndex >= 0 || $carry > 0) {
        $sum = $carry;
        if ($leftIndex >= 0) {
            $sum += intval($left[$leftIndex]);
            $leftIndex -= 1;
        }
        if ($rightIndex >= 0) {
            $sum += intval($right[$rightIndex]);
            $rightIndex -= 1;
        }
        $result = strval($sum % 10) . $result;
        $carry = intdiv($sum, 10);
    }
    return $result;
}

function subtract_offset_digits(string $left, string $right): string
{
    $borrow = 0;
    $result = '';
    $leftIndex = strlen($left) - 1;
    $rightIndex = strlen($right) - 1;
    while ($leftIndex >= 0) {
        $difference = intval($left[$leftIndex]) - $borrow - ($rightIndex >= 0 ? intval($right[$rightIndex]) : 0);
        if ($difference < 0) {
            $difference += 10;
            $borrow = 1;
        } else {
            $borrow = 0;
        }
        $result = strval($difference) . $result;
        $leftIndex -= 1;
        $rightIndex -= 1;
    }
    return ltrim($result, '0') ?: '0';
}

function compare_offset_digits(string $left, string $right): int
{
    if (strlen($left) !== strlen($right)) {
        return strlen($left) <=> strlen($right);
    }
    return strcmp($left, $right);
}

function normalize_offset_integer(bool $negative, string $digits): string
{
    $digits = ltrim($digits, '0') ?: '0';
    return $negative && $digits !== '0' ? '-' . $digits : $digits;
}
