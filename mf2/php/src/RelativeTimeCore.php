<?php

declare(strict_types=1);

namespace Mojito\MessageFormat2;

final class RelativeTimeCore
{
    private const DEFAULT_LOCALE = 'en';
    private const MAX_LOCALE_LENGTH = 256;
    private const MAX_OPTION_LENGTH = 256;
    private const MAX_OPERAND_LENGTH = 256;
    private const MAX_RELATIVE_TIME_QUANTITY = 1_000_000_000;
    private const DECIMAL_NUMBER_PATTERN = '/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/';
    private const STYLE_LONG = 'long';
    private const STYLE_SHORT = 'short';
    private const STYLE_NARROW = 'narrow';
    private const NUMERIC_ALWAYS = 'always';
    private const NUMERIC_AUTO = 'auto';
    private const POLICY_PRECISE = 'precise';
    private const POLICY_COMPACT = 'compact';
    private const POLICY_CHAT = 'chat';
    private const UNIT_AUTO = 'auto';
    private const UNIT_SECONDS = [
        'second' => 1,
        'minute' => 60,
        'hour' => 3_600,
        'day' => 86_400,
        'week' => 604_800,
        'month' => 2_592_000,
        'quarter' => 7_776_000,
        'year' => 31_536_000,
    ];
    private const POLICIES = [
        self::POLICY_PRECISE => [
            [60, 'second'],
            [3_600, 'minute'],
            [86_400, 'hour'],
            [604_800, 'day'],
            [2_592_000, 'week'],
            [31_536_000, 'month'],
            [INF, 'year'],
        ],
        self::POLICY_COMPACT => [
            [60, 'second'],
            [3_600, 'minute'],
            [86_400, 'hour'],
            [INF, 'day'],
        ],
        self::POLICY_CHAT => [
            [45, 'second'],
            [2_700, 'minute'],
            [79_200, 'hour'],
            [604_800, 'day'],
            [INF, 'week'],
        ],
    ];

    private function __construct(
        private array $localeMap,
        private array $patternSets,
    ) {
    }

    public static function create(array $data): self
    {
        $localeMap = self::prepareLocaleMap($data['localeMap'] ?? null);
        $rawPatternSets = $data['patternSets'] ?? null;
        if ($localeMap === null || !is_array($rawPatternSets) || $rawPatternSets === []) {
            throw new MF2Error('missing-locale-data', 'Relative-time core data has an unsupported shape.');
        }

        $patternSets = [];
        foreach ($rawPatternSets as $item) {
            if (is_array($item) && is_string($item['id'] ?? null) && $item['id'] !== '' && is_array($item['data'] ?? null) && $item['data'] !== []) {
                $patternSets[$item['id']] = $item['data'];
            }
        }
        if ($patternSets === []) {
            throw new MF2Error('missing-locale-data', 'Relative-time core data has an unsupported shape.');
        }
        return new self($localeMap, $patternSets);
    }

    private static function prepareLocaleMap(mixed $localeMap): ?array
    {
        if (!is_array($localeMap) || $localeMap === []) {
            return null;
        }
        foreach ($localeMap as $locale => $setId) {
            if (!is_string($locale) || !is_string($setId)) {
                return null;
            }
        }
        return $localeMap;
    }

    public static function registry(array $data): FunctionRegistry
    {
        return self::create($data)->functionRegistry();
    }

    public static function format(mixed $value, array $data, array $options = []): string
    {
        return self::create($data)->formatValue($value, $options);
    }

    public static function formatToParts(mixed $value, array $data, array $options = []): array
    {
        return self::create($data)->formatValueToParts($value, $options);
    }

    public function functionRegistry(): FunctionRegistry
    {
        return FunctionRegistry::portable()
            ->withFunction('relativeTime', fn(array $call): string => $this->formatCall($call));
    }

    public function formatValue(mixed $value, array $options = []): string
    {
        $style = self::optionOneOf(self::stringOption($options['style'] ?? self::STYLE_SHORT, 'style'), [self::STYLE_LONG, self::STYLE_SHORT, self::STYLE_NARROW], 'style');
        $numeric = self::optionOneOf(self::stringOption($options['numeric'] ?? self::NUMERIC_ALWAYS, 'numeric'), [self::NUMERIC_ALWAYS, self::NUMERIC_AUTO], 'numeric');
        $policy = self::optionOneOf(self::stringOption($options['policy'] ?? self::POLICY_PRECISE, 'policy'), [self::POLICY_PRECISE, self::POLICY_COMPACT, self::POLICY_CHAT], 'policy');
        $unit = self::optionOneOf(self::stringOption($options['unit'] ?? self::UNIT_AUTO, 'unit'), array_merge([self::UNIT_AUTO], array_keys(self::UNIT_SECONDS)), 'unit');
        $seconds = self::parseFiniteNumber($value);
        $selectedUnit = $unit === self::UNIT_AUTO ? self::selectUnit($seconds, $policy) : $unit;
        $quantity = self::quantity($seconds, $selectedUnit);
        $locale = self::localeOption($options['locale'] ?? self::DEFAULT_LOCALE);
        if ($locale === '') {
            $locale = self::DEFAULT_LOCALE;
        }

        if (self::useRelativeZero($policy, $numeric, $seconds)) {
            $relative = $this->relativeTerm($locale, $style, $selectedUnit, '0');
            if ($relative !== null) {
                return $relative;
            }
        }
        if ($numeric === self::NUMERIC_AUTO) {
            $offset = self::relativeOffset($seconds, $selectedUnit, $quantity);
            if ($offset !== null) {
                $relative = $this->relativeTerm($locale, $style, $selectedUnit, $offset);
                if ($relative !== null) {
                    return $relative;
                }
            }
        }

        $direction = self::isNegativeRelativeTime($seconds) ? 'past' : 'future';
        $category = Internal\select_cardinal($locale, $quantity);
        $pattern = $this->relativeTimePattern($locale, $style, $selectedUnit, $direction, $category);
        return str_replace('{0}', (string) $quantity, $pattern);
    }

    public function formatValueToParts(mixed $value, array $options = []): array
    {
        return [['type' => 'text', 'value' => $this->formatValue($value, $options)]];
    }

    private function formatCall(array $call): string
    {
        return $this->formatValue($call['rawValue'] ?? $call['value'], [
            'locale' => $call['locale'] ?? self::DEFAULT_LOCALE,
            'style' => self::callOption($call, 'style', self::STYLE_SHORT),
            'numeric' => self::callOption($call, 'numeric', self::NUMERIC_ALWAYS),
            'policy' => self::callOption($call, 'policy', self::POLICY_PRECISE),
            'unit' => self::callOption($call, 'unit', self::UNIT_AUTO),
        ]);
    }

    private static function parseFiniteNumber(mixed $value): float
    {
        if (is_bool($value) || $value === null || is_array($value)) {
            throw MF2Error::badOperand('Relative-time core requires a finite numeric value.');
        }
        if (is_object($value) && !method_exists($value, '__toString')) {
            throw MF2Error::badOperand('Relative-time core requires a finite numeric value.');
        }
        $text = trim(self::stringOperand($value));
        if ($text === '') {
            throw MF2Error::badOperand('Relative-time core requires a finite numeric value.');
        }
        if (strlen($text) > self::MAX_OPERAND_LENGTH) {
            throw MF2Error::badOperand('Relative-time core requires a finite numeric value.');
        }
        if (preg_match(self::DECIMAL_NUMBER_PATTERN, $text) !== 1) {
            throw MF2Error::badOperand('Relative-time core requires a finite numeric value.');
        }
        $parsed = (float) $text;
        if (!is_numeric($text) || !is_finite($parsed)) {
            throw MF2Error::badOperand('Relative-time core requires a finite numeric value.');
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
            throw MF2Error::badOperand('Relative-time core requires a finite numeric value.');
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

    private static function selectUnit(float $seconds, string $policy): string
    {
        $absolute = abs($seconds);
        foreach (self::POLICIES[$policy] as [$upper, $unit]) {
            if ($absolute < $upper) {
                return $unit;
            }
        }
        return 'year';
    }

    private static function quantity(float $seconds, string $unit): int
    {
        $absolute = abs($seconds);
        if ($absolute === 0.0) {
            return 0;
        }
        $quantity = max(1.0, floor($absolute / self::UNIT_SECONDS[$unit] + 0.5));
        if ($quantity > self::MAX_RELATIVE_TIME_QUANTITY) {
            throw MF2Error::badOperand('Relative-time core quantity is outside the supported range.');
        }
        return (int) $quantity;
    }

    private static function useRelativeZero(string $policy, string $numeric, float $seconds): bool
    {
        return $policy === self::POLICY_CHAT && $numeric === self::NUMERIC_AUTO && abs($seconds) < 45;
    }

    private static function relativeOffset(float $seconds, string $unit, int $quantity): ?string
    {
        if ($quantity === 0) {
            return '0';
        }
        if (abs($seconds) !== ((float) $quantity) * self::UNIT_SECONDS[$unit]) {
            return null;
        }
        return self::isNegativeRelativeTime($seconds) ? '-' . $quantity : (string) $quantity;
    }

    private static function isNegativeRelativeTime(float $seconds): bool
    {
        return $seconds < 0 || ($seconds === 0.0 && json_encode($seconds) === '-0');
    }

    private function relativeTerm(string $locale, string $style, string $unit, string $offset): ?string
    {
        $relative = $this->unitData($locale, $style, $unit)['relative'] ?? [];
        return is_array($relative) && is_string($relative[$offset] ?? null) ? $relative[$offset] : null;
    }

    private function relativeTimePattern(string $locale, string $style, string $unit, string $direction, string $category): string
    {
        $patterns = $this->unitData($locale, $style, $unit)[$direction] ?? null;
        if (is_array($patterns)) {
            $pattern = $patterns[$category] ?? $patterns['other'] ?? null;
            if (is_string($pattern)) {
                return $pattern;
            }
        }
        throw new MF2Error('missing-locale-data', "Missing relative-time pattern for {$locale}/{$style}/{$unit}/{$direction}.");
    }

    private function unitData(string $locale, string $style, string $unit): array
    {
        $patternSet = $this->patternSetFor($locale);
        $unitData = $patternSet[$style][$unit] ?? null;
        if (is_array($unitData)) {
            return $unitData;
        }
        throw new MF2Error('missing-locale-data', "Missing relative-time unit data for {$locale}/{$style}/{$unit}.");
    }

    private function patternSetFor(string $locale): array
    {
        foreach (Internal\locale_lookup_chain($locale) as $candidate) {
            $setId = $this->localeMap[$candidate] ?? null;
            if (!is_string($setId)) {
                continue;
            }
            $patternSet = $this->patternSets[$setId] ?? null;
            if (!is_array($patternSet)) {
                throw new MF2Error('missing-locale-data', "Missing relative-time pattern set {$setId}.");
            }
            return $patternSet;
        }
        throw new MF2Error('missing-locale-data', "Missing relative-time locale data for {$locale}.");
    }

    private static function callOption(array $call, string $name, mixed $fallback = null): mixed
    {
        $optionValue = $call['optionValue'] ?? static fn(string $name, mixed $fallback): mixed => $fallback;
        return $optionValue($name, $fallback);
    }
}
