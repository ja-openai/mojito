<?php

declare(strict_types=1);

namespace Mojito\MessageFormat2;

final class IntlFunctions
{
    private const UTC = 'UTC';
    private const MAX_FRACTION_DIGITS = 100;

    private function __construct()
    {
    }

    public static function registry(): FunctionRegistry
    {
        return FunctionRegistry::portable()
            ->withFunction('number', self::formatNumber(...))
            ->withFunction('percent', self::formatPercent(...))
            ->withFunction('integer', self::formatInteger(...))
            ->withFunction('currency', self::formatCurrency(...))
            ->withFunction('date', self::formatDate(...))
            ->withFunction('time', self::formatTime(...))
            ->withFunction('datetime', self::formatDateTime(...));
    }

    private static function formatNumber(array $call): string
    {
        $value = self::numericValue($call, 'Number function requires a numeric operand.');
        $formatter = self::numberFormatter($call, \NumberFormatter::DECIMAL);
        self::setOptionalFractionDigits($formatter, $call);
        return self::applySignDisplay(self::formatNumeric($formatter, $value), $value, $call);
    }

    private static function formatPercent(array $call): string
    {
        $value = self::numericValue($call, 'Percent function requires a numeric operand.');
        $formatter = self::numberFormatter($call, \NumberFormatter::PERCENT);
        self::setOptionalFractionDigits($formatter, $call);
        return self::applySignDisplay(self::formatNumeric($formatter, $value), $value, $call);
    }

    private static function formatInteger(array $call): string
    {
        $value = self::numericValue($call, 'Integer function requires a numeric operand.');
        $formatter = self::numberFormatter($call, \NumberFormatter::DECIMAL);
        $formatter->setAttribute(\NumberFormatter::FRACTION_DIGITS, 0);
        $formatter->setAttribute(\NumberFormatter::ROUNDING_MODE, \NumberFormatter::ROUND_DOWN);
        return self::applySignDisplay(self::formatNumeric($formatter, $value), $value, $call);
    }

    private static function formatCurrency(array $call): string
    {
        $value = self::numericValue($call, 'Currency function requires a numeric operand.');
        $currency = self::currencyCode($call);
        if ($currency === null || preg_match('/^[A-Za-z]{3}$/', $currency) !== 1) {
            throw MF2Error::badOption('Currency function requires a three-letter currency option.');
        }
        $formatter = self::numberFormatter($call, \NumberFormatter::CURRENCY);
        $fractionDigits = self::nonNegativeOption($call, 'fractionDigits');
        if ($fractionDigits !== null) {
            $formatter->setAttribute(\NumberFormatter::MIN_FRACTION_DIGITS, $fractionDigits);
            $formatter->setAttribute(\NumberFormatter::MAX_FRACTION_DIGITS, $fractionDigits);
        }
        $formatted = $formatter->formatCurrency($value, strtoupper($currency));
        if ($formatted === false) {
            throw MF2Error::badOperand('Currency function could not format this operand.');
        }
        return $formatted;
    }

    private static function formatDate(array $call): string
    {
        $timeZone = self::timeZone($call);
        $value = self::dateTimeValue($call, 'Date function requires a date or datetime operand.', $timeZone);
        return self::dateFormatter($call, self::dateStyle($call, 'medium'), \IntlDateFormatter::NONE, $timeZone)->format($value)
            ?: throw MF2Error::badOperand('Date function could not format this operand.');
    }

    private static function formatTime(array $call): string
    {
        $timeZone = self::timeZone($call);
        $value = self::dateTimeValue($call, 'Datetime and time functions require a datetime operand.', $timeZone);
        return self::dateFormatter($call, \IntlDateFormatter::NONE, self::timeStyle($call, 'medium'), $timeZone)->format($value)
            ?: throw MF2Error::badOperand('Time function could not format this operand.');
    }

    private static function formatDateTime(array $call): string
    {
        $timeZone = self::timeZone($call);
        $value = self::dateTimeValue($call, 'Datetime function requires a date or datetime operand.', $timeZone);
        $sharedStyle = self::option($call, 'style', null);
        $defaultStyle = $sharedStyle ?? 'medium';
        $dateStyle = self::dateStyle($call, $defaultStyle, 'dateStyle', 'dateLength');
        $timeStyle = self::timeStyle($call, $defaultStyle, 'timeStyle', 'timePrecision');
        return self::dateFormatter($call, $dateStyle, $timeStyle, $timeZone)->format($value)
            ?: throw MF2Error::badOperand('Datetime function could not format this operand.');
    }

    private static function numberFormatter(array $call, int $style): \NumberFormatter
    {
        return new \NumberFormatter((string) ($call['locale'] ?? 'en'), $style);
    }

    private static function dateFormatter(
        array $call,
        int $dateStyle,
        int $timeStyle,
        \DateTimeZone $timeZone,
    ): \IntlDateFormatter
    {
        return new \IntlDateFormatter(
            (string) ($call['locale'] ?? 'en'),
            $dateStyle,
            $timeStyle,
            $timeZone->getName(),
            \IntlDateFormatter::GREGORIAN,
        );
    }

    private static function numericValue(array $call, string $message): float
    {
        $rawValue = $call['rawValue'] ?? null;
        if (is_int($rawValue) || is_float($rawValue)) {
            $value = (float) $rawValue;
        } elseif (is_numeric($call['value'] ?? null)) {
            $value = (float) $call['value'];
        } elseif (($sourceValue = Internal\parse_source_decimal($call['inheritedSource'] ?? null)) !== null) {
            $value = $sourceValue;
        } else {
            throw MF2Error::badOperand($message);
        }
        if (!is_finite($value)) {
            throw MF2Error::badOperand($message);
        }
        return $value;
    }

    private static function dateTimeValue(array $call, string $message, \DateTimeZone $timeZone): \DateTimeImmutable
    {
        $value = self::dateTimeValueOrNull($call['rawValue'] ?? null, $call['value'] ?? null, $timeZone)
            ?? self::sourceDateTimeValue($call['inheritedSource'] ?? null, $timeZone);
        if ($value === null) {
            throw MF2Error::badOperand($message);
        }
        return $value;
    }

    private static function dateTimeValueOrNull(mixed $rawValue, mixed $value, \DateTimeZone $timeZone): ?\DateTimeImmutable
    {
        if ($rawValue instanceof \DateTimeInterface) {
            return \DateTimeImmutable::createFromInterface($rawValue);
        }
        $text = (string) ($value ?? '');
        if ($text === '') {
            return null;
        }
        try {
            return new \DateTimeImmutable($text, $timeZone);
        } catch (\Exception) {
            return null;
        }
    }

    private static function sourceDateTimeValue(?array $source, \DateTimeZone $timeZone): ?\DateTimeImmutable
    {
        for ($current = $source; $current !== null; $current = $current['inherited'] ?? null) {
            if (!in_array($current['function']['name'] ?? null, ['date', 'time', 'datetime'], true)) {
                continue;
            }
            $value = self::dateTimeValueOrNull(null, $current['value'] ?? null, $timeZone);
            if ($value !== null) {
                return $value;
            }
        }
        return null;
    }

    private static function setOptionalFractionDigits(\NumberFormatter $formatter, array $call): void
    {
        $minimum = self::nonNegativeOption($call, 'minimumFractionDigits');
        if ($minimum !== null) {
            $formatter->setAttribute(\NumberFormatter::MIN_FRACTION_DIGITS, $minimum);
        }
        $maximum = self::nonNegativeOption($call, 'maximumFractionDigits');
        if ($maximum !== null) {
            $formatter->setAttribute(\NumberFormatter::MAX_FRACTION_DIGITS, $maximum);
        }
    }

    private static function applySignDisplay(string $formatted, float $value, array $call): string
    {
        return $value >= 0 && self::option($call, 'signDisplay', null) === 'always' ? '+' . $formatted : $formatted;
    }

    private static function formatNumeric(\NumberFormatter $formatter, float $value): string
    {
        $formatted = $formatter->format($value);
        if ($formatted === false) {
            throw MF2Error::badOperand('Numeric function could not format this operand.');
        }
        return $formatted;
    }

    private static function currencyCode(array $call): ?string
    {
        return self::option($call, 'currency', null) ?? self::sourceOption($call['inheritedSource'] ?? null, 'currency');
    }

    private static function option(array $call, string $name, ?string $fallback): ?string
    {
        $value = ($call['optionValue'] ?? static fn(string $name, mixed $fallback): mixed => $fallback)($name, $fallback);
        return $value === null ? null : (string) $value;
    }

    private static function sourceOption(?array $source, string $name): ?string
    {
        if ($source === null) {
            return null;
        }
        if (($source['function']['name'] ?? null) === 'currency') {
            $value = ($source['optionValue'] ?? static fn(string $name, mixed $fallback): mixed => $fallback)($name, null);
            if ($value !== null) {
                return (string) $value;
            }
        }
        return self::sourceOption($source['inherited'] ?? null, $name);
    }

    private static function nonNegativeOption(array $call, string $name): ?int
    {
        $value = self::option($call, $name, null);
        if ($value === null || $value === 'auto') {
            return null;
        }
        if (preg_match('/^\d+$/', $value) !== 1) {
            throw MF2Error::badOption("{$name} option must be auto or a non-negative integer.");
        }
        $parsed = (int) $value;
        if ($parsed > self::MAX_FRACTION_DIGITS) {
            throw MF2Error::badOption("{$name} option must be auto or a non-negative integer.");
        }
        return $parsed;
    }

    private static function dateStyle(array $call, string $fallback, string ...$optionNames): int
    {
        return self::style(self::firstOption($call, $optionNames ?: ['dateStyle', 'length', 'style'], $fallback), true);
    }

    private static function timeStyle(array $call, string $fallback, string ...$optionNames): int
    {
        return self::style(self::firstOption($call, $optionNames ?: ['timeStyle', 'precision', 'style'], $fallback), false);
    }

    private static function firstOption(array $call, array $names, string $fallback): string
    {
        foreach ($names as $name) {
            $value = self::option($call, $name, null);
            if ($value !== null) {
                return $value;
            }
        }
        return $fallback;
    }

    private static function timeZone(array $call): \DateTimeZone
    {
        $value = self::option($call, 'timeZone', self::UTC) ?? self::UTC;
        try {
            return new \DateTimeZone($value);
        } catch (\Exception) {
            throw MF2Error::badOption('timeZone option must be a valid time zone identifier.');
        }
    }

    private static function style(string $value, bool $date): int
    {
        return match ($value) {
            'short' => \IntlDateFormatter::SHORT,
            'medium' => \IntlDateFormatter::MEDIUM,
            'long' => \IntlDateFormatter::LONG,
            'full' => \IntlDateFormatter::FULL,
            default => throw MF2Error::badOption(($date ? 'Date' : 'Time') . ' style must be short, medium, long, or full.'),
        };
    }
}
