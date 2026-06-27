<?php

declare(strict_types=1);

namespace Mojito\MessageFormat2;

final class NumberCore
{
    private const DEFAULT_LOCALE = 'en-US';
    private const ABSENT_OPTION = "\0__mojito_mf2_absent__";
    private const MAX_LOCALE_LENGTH = 256;
    private const MAX_OPTION_LENGTH = 256;
    private const MAX_OPERAND_LENGTH = 256;
    private const MAX_FRACTION_DIGITS = 100;
    private const STYLE_NUMBER = 'number';
    private const STYLE_INTEGER = 'integer';
    private const STYLE_PERCENT = 'percent';
    private const STYLE_CURRENCY = 'currency';
    private const CURRENCY_DISPLAY_SYMBOL = 'symbol';
    private const CURRENCY_DISPLAY_NARROW_SYMBOL = 'narrowSymbol';
    private const CURRENCY_DISPLAY_CODE = 'code';
    private const SIGN_DISPLAY_AUTO = 'auto';
    private const SIGN_DISPLAY_ALWAYS = 'always';
    private const SIGN_DISPLAY_NEVER = 'never';

    private function __construct()
    {
    }

    public static function registry(): FunctionRegistry
    {
        return FunctionRegistry::portable()
            ->withFunction('number', static fn(array $call): string => self::formatCallNumber($call, self::STYLE_NUMBER))
            ->withFunction('integer', static fn(array $call): string => self::formatCallNumber($call, self::STYLE_INTEGER))
            ->withFunction('percent', static fn(array $call): string => self::formatCallNumber($call, self::STYLE_PERCENT))
            ->withFunction('currency', static fn(array $call): string => self::formatCallNumber($call, self::STYLE_CURRENCY));
    }

    public static function format(mixed $value, array $options = []): string
    {
        $style = self::optionOneOf(
            self::stringOption($options['style'] ?? self::STYLE_NUMBER, 'style'),
            [self::STYLE_NUMBER, self::STYLE_INTEGER, self::STYLE_PERCENT, self::STYLE_CURRENCY],
            'style',
        );
        $signDisplay = self::optionOneOf(
            self::stringOption($options['signDisplay'] ?? self::SIGN_DISPLAY_AUTO, 'signDisplay'),
            [self::SIGN_DISPLAY_AUTO, self::SIGN_DISPLAY_ALWAYS, self::SIGN_DISPLAY_NEVER],
            'signDisplay',
        );
        $localeData = self::resolveLocaleData(self::localeOption($options['locale'] ?? self::DEFAULT_LOCALE));
        $parsed = self::parseFiniteDecimalOperand($value);
        if ($parsed === null) {
            throw MF2Error::badOperand('Number core requires a finite numeric value.');
        }

        $currency = $style === self::STYLE_CURRENCY ? self::parseCurrency($options['currency'] ?? null) : null;
        $pattern = self::patternForStyle($localeData, $style);
        $fraction = self::fractionOptions(
            $style,
            $currency,
            $options['minimumFractionDigits'] ?? null,
            $options['maximumFractionDigits'] ?? null,
            $pattern,
        );
        $normalized = $style === self::STYLE_INTEGER ? self::truncateDecimalPreservingZeroSign($parsed) : $parsed;
        $scaled = $style === self::STYLE_PERCENT ? self::shiftDecimalPreservingZeroSign($normalized, 2) : $normalized;
        self::ensureSupportedMagnitude($scaled);
        $formatted = self::formatDecimal(
            self::absDecimalOperand($scaled),
            $localeData,
            $pattern,
            $fraction,
            self::booleanOption($options['useGrouping'] ?? true, true),
        );

        if ($style === self::STYLE_PERCENT) {
            return self::applySignedPattern(
                $pattern,
                $formatted,
                $scaled,
                $localeData['symbols'],
                $signDisplay,
                percentSign: $localeData['symbols']['percentSign'] ?? '%',
            );
        }
        if ($style === self::STYLE_CURRENCY) {
            return self::applySignedPattern(
                $pattern,
                $formatted,
                $scaled,
                $localeData['symbols'],
                $signDisplay,
                currency: self::currencyDisplay($localeData, $currency ?? '', self::stringOption($options['currencyDisplay'] ?? self::CURRENCY_DISPLAY_SYMBOL, 'currencyDisplay')),
            );
        }
        return self::applySign($formatted, $scaled, $localeData['symbols'], $signDisplay);
    }

    public static function formatToParts(mixed $value, array $options = []): array
    {
        return [['type' => 'text', 'value' => self::format($value, $options)]];
    }

    private static function formatCallNumber(array $call, string $style): string
    {
        $value = self::callNumberValue($call, $style);
        return self::format($value, [
            'locale' => $call['locale'] ?? self::DEFAULT_LOCALE,
            'style' => $style,
            'currency' => $style === self::STYLE_CURRENCY
                ? self::inheritedOption($call, 'currency')
                : self::callOption($call, 'currency'),
            'currencyDisplay' => self::callOption($call, 'currencyDisplay', self::CURRENCY_DISPLAY_SYMBOL),
            'minimumFractionDigits' => self::callOption($call, 'minimumFractionDigits'),
            'maximumFractionDigits' => self::callOption($call, 'maximumFractionDigits'),
            'signDisplay' => self::callOption($call, 'signDisplay', self::SIGN_DISPLAY_AUTO),
            'useGrouping' => self::callOption($call, 'useGrouping', 'true'),
        ]);
    }

    private static function callNumberValue(array $call, string $style): mixed
    {
        $source = $call['inheritedSource'] ?? null;
        if (is_array($source)) {
            if (
                $style === self::STYLE_NUMBER
                && (($source['function']['name'] ?? null) === self::STYLE_INTEGER)
            ) {
                $parsed = self::parseFiniteDecimalOperand($source['value'] ?? null);
                if ($parsed !== null) {
                    return Internal\decimal_operand_to_string(self::truncateDecimalPreservingZeroSign($parsed));
                }
            }
            return $source['value'] ?? null;
        }
        return $call['rawValue'] ?? $call['value'];
    }

    private static function inheritedOption(array $call, string $name, mixed $fallback = null): mixed
    {
        $value = self::callOption($call, $name, self::ABSENT_OPTION);
        if ($value !== self::ABSENT_OPTION) {
            return $value;
        }
        $source = $call['inheritedSource'] ?? null;
        while (is_array($source)) {
            $value = self::sourceOption($source, $name, self::ABSENT_OPTION);
            if ($value !== self::ABSENT_OPTION) {
                return $value;
            }
            $source = $source['inherited'] ?? null;
        }
        return $fallback;
    }

    private static function sourceOption(array $source, string $name, mixed $fallback): mixed
    {
        if (isset($source['optionValue']) && is_callable($source['optionValue'])) {
            return $source['optionValue']($name, $fallback);
        }
        $option = $source['function']['options'][$name] ?? null;
        return ($option['type'] ?? null) === 'literal' ? ($option['value'] ?? '') : $fallback;
    }

    private static function resolveLocaleData(string $locale): array
    {
        $locales = Internal\cldr_number_data()['locales'];
        foreach (Internal\locale_lookup_chain($locale !== '' ? $locale : self::DEFAULT_LOCALE) as $candidate) {
            if (array_key_exists($candidate, $locales)) {
                return $locales[$candidate];
            }
            foreach ($locales as $localeData) {
                if (($localeData['numbersSourceLocale'] ?? null) === $candidate) {
                    return $localeData;
                }
            }
        }
        return $locales[self::DEFAULT_LOCALE];
    }

    private static function patternForStyle(array $localeData, string $style): string
    {
        return match ($style) {
            self::STYLE_PERCENT => (string) $localeData['percentPattern'],
            self::STYLE_CURRENCY => (string) $localeData['currencyPattern'],
            default => (string) $localeData['decimalPattern'],
        };
    }

    private static function fractionOptions(
        string $style,
        ?string $currency,
        mixed $minimumFractionDigits,
        mixed $maximumFractionDigits,
        string $pattern,
    ): array {
        [$minimum, $maximum] = self::fractionDefaultsFromPattern($pattern);
        if ($style === self::STYLE_INTEGER) {
            $minimum = 0;
            $maximum = 0;
        } elseif ($style === self::STYLE_CURRENCY) {
            $fractions = Internal\cldr_number_data()['currencyFractions'];
            $currencyDefaults = $fractions[$currency ?? ''] ?? $fractions['DEFAULT'];
            $minimum = (int) ($currencyDefaults['digits'] ?? 2);
            $maximum = $minimum;
        }

        $minimum = self::nonNegativeIntegerOption($minimumFractionDigits, $minimum, 'minimumFractionDigits');
        $maximum = self::nonNegativeIntegerOption($maximumFractionDigits, $maximum, 'maximumFractionDigits');
        if ($minimumFractionDigits !== null && $maximumFractionDigits === null && $maximum < $minimum) {
            $maximum = $minimum;
        }
        if ($maximumFractionDigits !== null && $minimumFractionDigits === null && $maximum < $minimum) {
            $minimum = $maximum;
        }
        if ($maximum < $minimum) {
            throw MF2Error::badOption('maximumFractionDigits must be greater than or equal to minimumFractionDigits.');
        }
        return ['minimum' => $minimum, 'maximum' => $maximum];
    }

    private static function fractionDefaultsFromPattern(string $pattern): array
    {
        $numberPattern = self::numberPattern($pattern);
        $dot = strpos($numberPattern, '.');
        if ($dot === false) {
            return [0, 0];
        }
        $fraction = substr($numberPattern, $dot + 1);
        return [substr_count($fraction, '0'), strlen($fraction)];
    }

    private static function formatDecimal(
        array $value,
        array $localeData,
        string $pattern,
        array $fraction,
        bool $useGrouping,
    ): string {
        $rounded = Internal\decimal_operand_to_string(
            Internal\round_decimal_operand_to_maximum_fraction_digits($value, $fraction['maximum']),
        );
        [$integer, $decimal] = array_pad(explode('.', $rounded, 2), 2, '');
        while (strlen($decimal) > $fraction['minimum'] && str_ends_with($decimal, '0')) {
            $decimal = substr($decimal, 0, -1);
        }
        while (strlen($decimal) < $fraction['minimum']) {
            $decimal .= '0';
        }

        $grouping = self::groupingInfo($pattern);
        if ($useGrouping && self::shouldGroup($integer, $grouping, (int) ($localeData['minimumGroupingDigits'] ?? 1))) {
            $integer = self::groupInteger($integer, $grouping, (string) ($localeData['symbols']['group'] ?? ','));
        }
        $digits = $localeData['numberingSystemDigits'] ?? null;
        $integer = self::localizeDigits($integer, is_string($digits) ? $digits : null);
        if ($decimal !== '') {
            return $integer
                . (string) ($localeData['symbols']['decimal'] ?? '.')
                . self::localizeDigits($decimal, is_string($digits) ? $digits : null);
        }
        return $integer;
    }

    private static function groupingInfo(string $pattern): array
    {
        $integerPattern = explode('.', self::numberPattern($pattern), 2)[0];
        $groups = explode(',', $integerPattern);
        if (count($groups) === 1) {
            return ['primary' => 0, 'secondary' => 0];
        }
        $primary = self::placeholderCount($groups[count($groups) - 1]);
        $secondary = count($groups) > 2 ? self::placeholderCount($groups[count($groups) - 2]) : $primary;
        return ['primary' => $primary, 'secondary' => $secondary];
    }

    private static function shouldGroup(string $integer, array $grouping, int $minimumGroupingDigits): bool
    {
        return $grouping['primary'] > 0 && strlen($integer) >= $grouping['primary'] + $minimumGroupingDigits;
    }

    private static function groupInteger(string $integer, array $grouping, string $separator): string
    {
        $groups = [];
        $end = strlen($integer);
        $size = $grouping['primary'];
        while ($end > 0) {
            $start = max(0, $end - $size);
            array_unshift($groups, substr($integer, $start, $end - $start));
            $end = $start;
            $size = $grouping['secondary'] ?: $grouping['primary'];
        }
        return implode($separator, $groups);
    }

    private static function applySign(string $formatted, array $value, array $symbols, string $signDisplay): string
    {
        if ($signDisplay === self::SIGN_DISPLAY_NEVER) {
            return $formatted;
        }
        if (self::isNegative($value)) {
            return (string) ($symbols['minusSign'] ?? '-') . $formatted;
        }
        if ($signDisplay === self::SIGN_DISPLAY_ALWAYS) {
            return (string) ($symbols['plusSign'] ?? '+') . $formatted;
        }
        return $formatted;
    }

    private static function applyPattern(
        string $pattern,
        string $formatted,
        ?string $percentSign = null,
        ?string $currency = null,
    ): string {
        $output = preg_replace('/[#0,.]+/u', $formatted, $pattern, 1) ?? $pattern;
        if ($percentSign !== null) {
            $output = str_replace('%', $percentSign, $output);
        }
        if ($currency !== null) {
            $output = str_replace('¤', $currency, $output);
        }
        return $output;
    }

    private static function applySignedPattern(
        string $pattern,
        string $formatted,
        array $value,
        array $symbols,
        string $signDisplay,
        ?string $percentSign = null,
        ?string $currency = null,
    ): string {
        $separator = strpos($pattern, ';');
        $positivePattern = $separator === false ? $pattern : substr($pattern, 0, $separator);
        if (self::isNegative($value) && $signDisplay !== self::SIGN_DISPLAY_NEVER) {
            if ($separator !== false) {
                return self::applyPattern(substr($pattern, $separator + 1), $formatted, $percentSign, $currency);
            }
            return (string) ($symbols['minusSign'] ?? '-') . self::applyPattern($positivePattern, $formatted, $percentSign, $currency);
        }
        $output = self::applyPattern($positivePattern, $formatted, $percentSign, $currency);
        if ($signDisplay === self::SIGN_DISPLAY_ALWAYS) {
            return (string) ($symbols['plusSign'] ?? '+') . $output;
        }
        return $output;
    }

    private static function isNegative(array $value): bool
    {
        return $value['negative'];
    }

    private static function currencyDisplay(array $localeData, string $currency, string $display): string
    {
        $display = self::optionOneOf(
            $display,
            [self::CURRENCY_DISPLAY_SYMBOL, self::CURRENCY_DISPLAY_NARROW_SYMBOL, self::CURRENCY_DISPLAY_CODE],
            'currencyDisplay',
        );
        if ($display === self::CURRENCY_DISPLAY_CODE) {
            return self::currencyCodeDisplay($localeData, $currency);
        }
        $data = $localeData['currencies'][$currency] ?? [];
        if ($display === self::CURRENCY_DISPLAY_NARROW_SYMBOL && ($data['narrowSymbol'] ?? '') !== '') {
            return (string) $data['narrowSymbol'];
        }
        return (string) (($data['symbol'] ?? '') !== '' ? $data['symbol'] : $currency);
    }

    private static function currencyCodeDisplay(array $localeData, string $currency): string
    {
        $positivePattern = explode(';', (string) ($localeData['currencyPattern'] ?? ''), 2)[0];
        $before = preg_match('/[#0]\x{00a4}/u', $positivePattern) === 1
            ? self::currencySpacingInsert($localeData, 'beforeCurrency')
            : '';
        $after = preg_match('/\x{00a4}[#0]/u', $positivePattern) === 1
            ? self::currencySpacingInsert($localeData, 'afterCurrency')
            : '';
        return $before . $currency . $after;
    }

    private static function currencySpacingInsert(array $localeData, string $direction): string
    {
        $insert = $localeData['currencySpacing'][$direction]['insertBetween'] ?? "\u{a0}";
        return is_string($insert) && $insert !== '' ? $insert : "\u{a0}";
    }

    private static function parseFiniteDecimalOperand(mixed $value): ?array
    {
        if (is_bool($value) || $value === null) {
            return null;
        }
        if (is_float($value) && !is_finite($value)) {
            return null;
        }
        if (is_object($value) && !method_exists($value, '__toString')) {
            return null;
        }
        if (is_array($value)) {
            return null;
        }
        $text = trim(self::stringOperand($value));
        if (strlen($text) > self::MAX_OPERAND_LENGTH) {
            return null;
        }
        if (preg_match('/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/', $text) !== 1) {
            return null;
        }
        $parsed = Internal\parse_decimal_operand($text);
        if ($parsed !== null && str_starts_with($text, '-') && $parsed['digits'] === '0' && $parsed['scale'] === 0) {
            $parsed['negative'] = true;
        }
        return $parsed;
    }

    private static function shiftDecimalPreservingZeroSign(array $operand, int $places): array
    {
        $shifted = Internal\shift_decimal_operand($operand, $places);
        if ($operand['negative'] && $shifted['digits'] === '0' && $shifted['scale'] === 0) {
            $shifted['negative'] = true;
        }
        return $shifted;
    }

    private static function truncateDecimalPreservingZeroSign(array $operand): array
    {
        $truncated = Internal\truncate_decimal_operand_to_integer($operand);
        if ($operand['negative'] && $truncated['digits'] === '0' && $truncated['scale'] === 0) {
            $truncated['negative'] = true;
        }
        return $truncated;
    }

    private static function absDecimalOperand(array $operand): array
    {
        $operand['negative'] = false;
        return $operand;
    }

    private static function ensureSupportedMagnitude(array $value): void
    {
        if (self::decimalIntegerDigitCount($value) > 21) {
            throw MF2Error::badOperand('Number core numeric value is outside the supported magnitude.');
        }
    }

    private static function decimalIntegerDigitCount(array $operand): int
    {
        if ($operand['scale'] <= 0) {
            return strlen($operand['digits']) - $operand['scale'];
        }
        return max(strlen($operand['digits']) - $operand['scale'], 0);
    }

    private static function parseCurrency(mixed $value): string
    {
        $currency = self::stringOption($value, 'currency');
        if (preg_match('/^[A-Za-z]{3}$/', $currency) !== 1) {
            throw MF2Error::badOption('currency must be a three-letter ISO 4217 code.');
        }
        return strtoupper($currency);
    }

    private static function nonNegativeIntegerOption(mixed $value, int $fallback, string $name): int
    {
        if ($value === null) {
            return $fallback;
        }
        if (is_int($value)) {
            if ($value < 0 || $value > self::MAX_FRACTION_DIGITS) {
                throw MF2Error::badOption("{$name} must be a non-negative integer.");
            }
            return $value;
        }
        $text = trim(self::stringOption($value, $name));
        if (strlen($text) > self::MAX_OPTION_LENGTH || preg_match('/^\d+$/', $text) !== 1) {
            throw MF2Error::badOption("{$name} must be a non-negative integer.");
        }
        $parsed = (int) $text;
        if ($parsed > self::MAX_FRACTION_DIGITS) {
            throw MF2Error::badOption("{$name} must be a non-negative integer.");
        }
        return $parsed;
    }

    private static function optionOneOf(string $value, array $allowed, string $name): string
    {
        if (!in_array($value, $allowed, true)) {
            throw MF2Error::badOption("{$name} must be one of " . implode(', ', $allowed) . '.');
        }
        return $value;
    }

    private static function stringOperand(mixed $value): string
    {
        try {
            return (string) $value;
        } catch (\Throwable) {
            throw MF2Error::badOperand('Number core requires a finite numeric value.');
        }
    }

    private static function stringOption(mixed $value, string $name): string
    {
        if (is_array($value)) {
            throw MF2Error::badOption("{$name} must be coercible to a string.");
        }
        try {
            $text = (string) $value;
        } catch (\Throwable) {
            throw MF2Error::badOption("{$name} must be coercible to a string.");
        }
        if (strlen($text) > self::MAX_OPTION_LENGTH) {
            throw MF2Error::badOption("{$name} must not exceed 256 characters.");
        }
        return $text;
    }

    private static function localeOption(mixed $value): string
    {
        $locale = self::stringOption($value, 'locale');
        if (strlen($locale) > self::MAX_LOCALE_LENGTH) {
            throw MF2Error::badOption('locale must not exceed 256 characters.');
        }
        return $locale;
    }

    private static function booleanOption(mixed $value, bool $fallback): bool
    {
        if ($value === null) {
            return $fallback;
        }
        if (is_bool($value)) {
            return $value;
        }
        return match (self::stringOption($value, 'useGrouping')) {
            'true' => true,
            'false' => false,
            default => throw MF2Error::badOption('useGrouping must be true or false.'),
        };
    }

    private static function numberPattern(string $pattern): string
    {
        return preg_match('/[#0,.]+/u', $pattern, $matches) === 1 ? $matches[0] : '';
    }

    private static function placeholderCount(string $pattern): int
    {
        return substr_count($pattern, '#') + substr_count($pattern, '0');
    }

    private static function localizeDigits(string $value, ?string $digits): string
    {
        if ($digits === null || $digits === '0123456789') {
            return $value;
        }
        $digitChars = preg_split('//u', $digits, -1, PREG_SPLIT_NO_EMPTY) ?: [];
        if (count($digitChars) < 10) {
            return $value;
        }
        $output = '';
        for ($index = 0, $length = strlen($value); $index < $length; $index += 1) {
            $ch = $value[$index];
            $output .= $ch >= '0' && $ch <= '9' ? $digitChars[ord($ch) - ord('0')] : $ch;
        }
        return $output;
    }

    private static function callOption(array $call, string $name, mixed $fallback = null): mixed
    {
        $optionValue = $call['optionValue'] ?? static fn(string $name, mixed $fallback): mixed => $fallback;
        return $optionValue($name, $fallback);
    }
}
