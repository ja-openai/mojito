<?php

declare(strict_types=1);

namespace Mojito\MessageFormat2;

final class DateTimeCore
{
    private const DEFAULT_LOCALE = 'en-US';
    private const UTC = 'UTC';
    private const MIN_TIMESTAMP_MS = -62135596800000.0;
    private const MAX_TIMESTAMP_MS = 253402300799999.0;
    private const MAX_LOCALE_LENGTH = 256;
    private const MAX_OPTION_LENGTH = 256;
    private const MAX_OPERAND_LENGTH = 256;
    private const MAX_SKELETON_FIELD_WIDTH = 32;
    private const MAX_SKELETON_LENGTH = 256;
    private const STYLES = ['full', 'long', 'medium', 'short'];
    private const SEMANTIC_SKELETON_PREFIX = 'semantic:';
    private const SEMANTIC_FIELD_ORDER = ['era', 'year', 'quarter', 'month', 'weekofmonth', 'day', 'dayofyear', 'dayofweekinmonth', 'modifiedjulianday', 'weekday', 'weekofyear', 'dayperiod', 'hour', 'minute', 'second', 'fractionalsecond', 'millisecondsinday', 'time', 'zone'];
    private const SEMANTIC_DATE_FIELD_ORDER = ['era', 'year', 'quarter', 'month', 'weekofmonth', 'day', 'dayofyear', 'dayofweekinmonth', 'modifiedjulianday', 'weekday', 'weekofyear'];
    private const SEMANTIC_TIME_FIELD_ORDER = ['hour', 'minute', 'second', 'fractionalsecond', 'millisecondsinday'];
    private const SEMANTIC_OPTION_KEYS = ['fields', 'length', 'alignment', 'yearstyle', 'erastyle', 'monthstyle', 'quarterstyle', 'daystyle', 'weekdaystyle', 'dayperiodstyle', 'hourstyle', 'minutestyle', 'secondstyle', 'timeprecision', 'timestyle', 'fractionalsecond', 'hourcycle', 'zonestyle'];
    private const SEMANTIC_DIRECT_STYLE_OPTION_KEYS = ['fields', 'length', 'timestyle'];
    private const SEMANTIC_STYLE_OPTION_KEYS = ['yearstyle', 'erastyle', 'monthstyle', 'quarterstyle', 'daystyle', 'weekdaystyle', 'dayperiodstyle', 'hourstyle', 'minutestyle', 'secondstyle'];
    private const SEMANTIC_DATE_STYLE_VALUES = ['auto', 'numeric', '2-digit', 'short', 'long', 'narrow'];
    private const SEMANTIC_NUMERIC_STYLE_VALUES = ['auto', 'numeric', '2-digit'];
    private const SEMANTIC_TEXT_STYLE_VALUES = ['auto', 'short', 'long', 'narrow'];
    private const SEMANTIC_DATE_FIELD_SETS = ['day', 'weekday', 'day,weekday', 'month,day', 'month,day,weekday', 'era,year,month,day', 'era,year,month,day,weekday', 'year,month,day', 'year,month,day,weekday'];
    private const SEMANTIC_CALENDAR_PERIOD_FIELD_SETS = ['era', 'year', 'quarter', 'month', 'era,year', 'era,year,quarter', 'era,year,month', 'era,year,weekofyear', 'era,year,month,weekofmonth', 'year,quarter', 'year,month', 'year,weekofyear', 'month,weekofmonth', 'year,month,weekofmonth', 'dayofyear', 'dayofweekinmonth', 'modifiedjulianday'];
    private const SEMANTIC_TIME_FIELD_SETS = ['hour', 'minute', 'second', 'millisecondsinday', 'hour,minute', 'hour,minute,second', 'hour,minute,second,fractionalsecond', 'minute,second', 'minute,second,fractionalsecond', 'second,fractionalsecond'];
    private const SKELETON_FIELD_ORDER = 'GyYuUrQqMLlwWdDFgEecabBhHkKJmsSAzZOvVXx';
    private const SKELETON_TIME_FIELDS = 'abBhHkKJmsSAzZOvVXx';
    private const SKELETON_HOUR_FIELDS = 'hHkK';
    private const WEEKDAY_KEYS = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'];

    private function __construct()
    {
    }

    public static function registry(): FunctionRegistry
    {
        return FunctionRegistry::portable()
            ->withFunction('date', self::formatCallDate(...))
            ->withFunction('time', self::formatCallTime(...))
            ->withFunction('datetime', self::formatCallDateTime(...));
    }

    public static function formatDate(mixed $value, array $options = []): string
    {
        $locale = self::localeOption($options['locale'] ?? self::DEFAULT_LOCALE);
        $localeData = self::resolveNumberingSystemData(self::resolveLocaleData($locale), $locale);
        self::validateOptions($options, $locale);
        $preserveSameFamilyHourCycle = self::firstNonEmpty($options['hourCycle'] ?? null) !== null;
        $hourCycle = self::validateHourCycle(self::firstNonEmpty($options['hourCycle'] ?? null, self::localeUnicodeExtension($locale, 'hc')));
        $date = self::parseDateTime($value)->setTimezone(self::parseTimeZone($options['timeZone'] ?? self::UTC));
        if (($options['skeleton'] ?? null) !== null) {
            return self::formatSkeleton(self::stringOption($options['skeleton'], 'skeleton'), $date, $localeData, $hourCycle, $preserveSameFamilyHourCycle);
        }
        $style = self::styleOption(
            self::firstOption($options, ['dateStyle', 'length', 'style'], 'medium'),
            'dateStyle',
        );
        return self::formatPattern((string) $localeData['dateFormats'][$style], $date, $localeData);
    }

    public static function formatTime(mixed $value, array $options = []): string
    {
        $locale = self::localeOption($options['locale'] ?? self::DEFAULT_LOCALE);
        $localeData = self::resolveNumberingSystemData(self::resolveLocaleData($locale), $locale);
        self::validateOptions($options, $locale);
        $preserveSameFamilyHourCycle = self::firstNonEmpty($options['hourCycle'] ?? null) !== null;
        $hourCycle = self::validateHourCycle(self::firstNonEmpty($options['hourCycle'] ?? null, self::localeUnicodeExtension($locale, 'hc')));
        $date = self::parseDateTime($value)->setTimezone(self::parseTimeZone($options['timeZone'] ?? self::UTC));
        if (($options['skeleton'] ?? null) !== null) {
            return self::formatSkeleton(self::stringOption($options['skeleton'], 'skeleton'), $date, $localeData, $hourCycle, $preserveSameFamilyHourCycle);
        }
        $style = self::timeStyleOption($options, 'medium');
        return self::formatTimeStylePattern((string) $localeData['timeFormats'][$style], $date, $localeData, $hourCycle, $preserveSameFamilyHourCycle);
    }

    public static function formatDateTime(mixed $value, array $options = []): string
    {
        $locale = self::localeOption($options['locale'] ?? self::DEFAULT_LOCALE);
        $localeData = self::resolveNumberingSystemData(self::resolveLocaleData($locale), $locale);
        self::validateOptions($options, $locale);
        $preserveSameFamilyHourCycle = self::firstNonEmpty($options['hourCycle'] ?? null) !== null;
        $hourCycle = self::validateHourCycle(self::firstNonEmpty($options['hourCycle'] ?? null, self::localeUnicodeExtension($locale, 'hc')));
        $date = self::parseDateTime($value)->setTimezone(self::parseTimeZone($options['timeZone'] ?? self::UTC));
        if (($options['skeleton'] ?? null) !== null) {
            return self::formatSkeleton(self::stringOption($options['skeleton'], 'skeleton'), $date, $localeData, $hourCycle, $preserveSameFamilyHourCycle);
        }
        $dateStyle = self::styleOption(
            self::firstOption($options, ['dateStyle', 'dateLength', 'length', 'style'], 'medium'),
            'dateStyle',
        );
        $timeStyle = self::timeStyleOption($options, 'medium');
        $datePart = self::formatPattern((string) $localeData['dateFormats'][$dateStyle], $date, $localeData);
        $timePart = self::formatTimeStylePattern((string) $localeData['timeFormats'][$timeStyle], $date, $localeData, $hourCycle, $preserveSameFamilyHourCycle);
        return str_replace(
            ['{1}', '{0}'],
            [$datePart, $timePart],
            self::dateTimeStyleJoinPattern($localeData, $dateStyle),
        );
    }

    public static function formatDateToParts(mixed $value, array $options = []): array
    {
        return [['type' => 'text', 'value' => self::formatDate($value, $options)]];
    }

    public static function formatTimeToParts(mixed $value, array $options = []): array
    {
        return [['type' => 'text', 'value' => self::formatTime($value, $options)]];
    }

    public static function formatDateTimeToParts(mixed $value, array $options = []): array
    {
        return [['type' => 'text', 'value' => self::formatDateTime($value, $options)]];
    }

    private static function formatCallDate(array $call): string
    {
        return self::formatDate(self::callValue($call), [
            'locale' => $call['locale'] ?? self::DEFAULT_LOCALE,
            'dateStyle' => self::callStyle($call, 'dateStyle', 'length', 'medium', false),
            'skeleton' => self::nonEmptyCallOption($call, 'skeleton', null),
            'hourCycle' => self::nonEmptyCallOption($call, 'hourCycle', null),
            'timeZone' => self::nonEmptyCallOption($call, 'timeZone', self::UTC),
            'calendar' => self::nonEmptyCallOption($call, 'calendar', null),
        ]);
    }

    private static function formatCallTime(array $call): string
    {
        return self::formatTime(self::callValue($call), [
            'locale' => $call['locale'] ?? self::DEFAULT_LOCALE,
            'timeStyle' => self::callStyle($call, 'timeStyle', 'precision', 'medium', true),
            'skeleton' => self::nonEmptyCallOption($call, 'skeleton', null),
            'hourCycle' => self::nonEmptyCallOption($call, 'hourCycle', null),
            'timeZone' => self::nonEmptyCallOption($call, 'timeZone', self::UTC),
            'calendar' => self::nonEmptyCallOption($call, 'calendar', null),
        ]);
    }

    private static function formatCallDateTime(array $call): string
    {
        return self::formatDateTime(self::callValue($call), [
            'locale' => $call['locale'] ?? self::DEFAULT_LOCALE,
            'dateStyle' => self::callStyle($call, 'dateStyle', 'dateLength', 'medium', false),
            'timeStyle' => self::callStyle($call, 'timeStyle', 'timePrecision', 'medium', true),
            'skeleton' => self::nonEmptyCallOption($call, 'skeleton', null),
            'hourCycle' => self::nonEmptyCallOption($call, 'hourCycle', null),
            'timeZone' => self::nonEmptyCallOption($call, 'timeZone', self::UTC),
            'calendar' => self::nonEmptyCallOption($call, 'calendar', null),
        ]);
    }

    private static function callValue(array $call): mixed
    {
        if (isset($call['inheritedSource'])) {
            return $call['inheritedSource']['value'];
        }
        return $call['rawValue'] ?? $call['value'];
    }

    private static function resolveLocaleData(string $locale): array
    {
        $locales = Internal\cldr_date_time_data()['locales'];
        foreach (Internal\locale_lookup_chain($locale !== '' ? $locale : self::DEFAULT_LOCALE) as $candidate) {
            if (array_key_exists($candidate, $locales)) {
                return $locales[$candidate];
            }
            foreach ($locales as $localeData) {
                if (($localeData['sourceLocale'] ?? null) === $candidate || ($localeData['numbersSourceLocale'] ?? null) === $candidate) {
                    return $localeData;
                }
            }
        }
        return $locales[self::DEFAULT_LOCALE];
    }

    private static function localeUnicodeExtension(string $locale, string $key): ?string
    {
        $parts = array_values(
            array_filter(
                array_map('strtolower', explode('-', str_replace('_', '-', trim($locale)))),
                fn ($part) => $part !== '',
            ),
        );
        $index = array_search('u', $parts, true);
        if ($index === false) {
            return null;
        }
        $index += 1;
        while ($index < count($parts)) {
            $part = $parts[$index];
            if (strlen($part) === 1) {
                return null;
            }
            if (strlen($part) !== 2) {
                $index += 1;
                continue;
            }
            $end = $index + 1;
            while ($end < count($parts) && strlen($parts[$end]) > 2) {
                $end += 1;
            }
            if ($part === $key) {
                return $end > $index + 1 ? $parts[$index + 1] : null;
            }
            $index = $end;
        }
        return null;
    }

    private static function resolveNumberingSystemData(array $localeData, string $locale): array
    {
        $numberingSystem = self::localeUnicodeExtension($locale, 'nu');
        if ($numberingSystem === null || $numberingSystem === '') {
            return $localeData;
        }
        $digits = self::numberingSystemDigits($numberingSystem);
        if ($digits === null) {
            throw MF2Error::badOption('Date/time core does not include data for the requested numbering system.');
        }
        $localeData['numberingSystemDigits'] = $digits;
        return $localeData;
    }

    private static function numberingSystemDigits(string $numberingSystem): ?string
    {
        if ($numberingSystem === 'latn') {
            return '0123456789';
        }
        foreach (Internal\cldr_date_time_data()['locales'] as $localeData) {
            if (($localeData['numberingSystem'] ?? null) === $numberingSystem && ($localeData['numberingSystemDigits'] ?? null) !== null) {
                return (string) $localeData['numberingSystemDigits'];
            }
        }
        return null;
    }

    private static function firstNonEmpty(mixed ...$values): mixed
    {
        foreach ($values as $value) {
            if ($value !== null && $value !== '') {
                return $value;
            }
        }
        return null;
    }

    private static function validateOptions(array $options, string $locale): void
    {
        $calendar = self::firstNonEmpty($options['calendar'] ?? null, self::localeUnicodeExtension($locale, 'ca'));
        if ($calendar !== null) {
            $calendar = self::stringOption($calendar, 'calendar');
        }
        if ($calendar !== null && $calendar !== 'gregorian' && $calendar !== 'gregory') {
            throw MF2Error::badOption('Date/time core currently supports only the gregorian/gregory calendar.');
        }
    }

    private static function validateHourCycle(mixed $value): ?string
    {
        if ($value === null || $value === '') {
            return null;
        }
        $text = self::stringOption($value, 'hourCycle');
        if ($text === 'h11' || $text === 'h12' || $text === 'h23' || $text === 'h24') {
            return $text;
        }
        throw MF2Error::badOption('hourCycle must be one of h11, h12, h23, h24.');
    }

    private static function parseTimeZone(mixed $value): \DateTimeZone
    {
        $text = trim(self::stringOption($value ?? self::UTC, 'timeZone'));
        if ($text === '' || $text === self::UTC || $text === 'Etc/UTC' || $text === 'Z' || $text === 'GMT' || $text === 'Etc/GMT') {
            return new \DateTimeZone(self::UTC);
        }
        $etcGmtOffsetMinutes = self::parseEtcGmtOffsetMinutes($text);
        if ($etcGmtOffsetMinutes !== null) {
            return new \DateTimeZone(self::extendedOffset($etcGmtOffsetMinutes, true));
        }
        if (str_starts_with($text, 'UTC') || str_starts_with($text, 'GMT')) {
            $text = substr($text, 3);
        }
        $offsetMinutes = self::parseOffsetMinutes($text);
        if ($offsetMinutes === null) {
            throw MF2Error::badOption('Date/time core supports only UTC or fixed-offset time zones.');
        }
        return new \DateTimeZone(self::extendedOffset($offsetMinutes, true));
    }

    private static function parseEtcGmtOffsetMinutes(string $value): ?int
    {
        if (preg_match('/^Etc\/GMT([+-]\d{1,2})$/', $value, $matches) !== 1) {
            return null;
        }
        $hours = (int) $matches[1];
        if (abs($hours) > 14) {
            return null;
        }
        return -$hours * 60;
    }

    private static function parseOffsetMinutes(string $value): ?int
    {
        if (preg_match('/^([+-])(\d{1,2})(?::?(\d{2}))?$/', $value, $matches) !== 1) {
            return null;
        }
        $hours = (int) $matches[2];
        $minutes = (int) ($matches[3] ?? '0');
        if ($hours > 18 || $minutes > 59 || ($hours === 18 && $minutes !== 0)) {
            return null;
        }
        $total = $hours * 60 + $minutes;
        return $matches[1] === '-' ? -$total : $total;
    }

    private static function parseDateTime(mixed $value): \DateTimeImmutable
    {
        $utc = new \DateTimeZone(self::UTC);
        if ($value instanceof \DateTimeInterface) {
            return \DateTimeImmutable::createFromInterface($value)->setTimezone($utc);
        }
        if ((is_int($value) || is_float($value)) && is_finite((float) $value)) {
            $timestamp = (float) $value;
            if ($timestamp >= self::MIN_TIMESTAMP_MS && $timestamp <= self::MAX_TIMESTAMP_MS) {
                $parsed = \DateTimeImmutable::createFromFormat('U.u', sprintf('%.6F', $timestamp / 1000), $utc);
                if ($parsed !== false) {
                    return $parsed->setTimezone($utc);
                }
            }
        }

        $text = is_string($value) || is_numeric($value) ? trim((string) $value) : '';
        if (strlen($text) > self::MAX_OPERAND_LENGTH) {
            throw MF2Error::badOperand('Date/time core requires a valid host date/time value or ISO date string.');
        }
        if (preg_match('/^\d{4}-\d{2}-\d{2}$/', $text) === 1) {
            return self::parseStrictDateTime('!Y-m-d', $text, $utc);
        }
        if (preg_match(
            '/^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:\d{2})?$/',
            $text,
            $matches,
        ) === 1) {
            $seconds = $matches[4] ?? '00';
            $fraction = str_pad(substr($matches[5] ?? '', 0, 6), 6, '0');
            $zone = $matches[6] ?? '';
            $normalized = "{$matches[1]}T{$matches[2]}:{$matches[3]}:{$seconds}.{$fraction}";
            if ($zone !== '') {
                if ($zone !== 'Z' && self::parseOffsetMinutes($zone) === null) {
                    throw MF2Error::badOperand('Date/time core requires a valid host date/time value or ISO date string.');
                }
                $normalized .= $zone === 'Z' ? '+00:00' : $zone;
                return self::parseStrictDateTime('!Y-m-d\TH:i:s.uP', $normalized, $utc)->setTimezone($utc);
            }
            return self::parseStrictDateTime('!Y-m-d\TH:i:s.u', $normalized, $utc)->setTimezone($utc);
        }

        throw MF2Error::badOperand('Date/time core requires a valid host date/time value or ISO date string.');
    }

    private static function parseStrictDateTime(string $format, string $value, \DateTimeZone $timeZone): \DateTimeImmutable
    {
        $parsed = \DateTimeImmutable::createFromFormat($format, $value, $timeZone);
        $errors = \DateTimeImmutable::getLastErrors();
        if ($parsed === false || ($errors !== false && ($errors['warning_count'] > 0 || $errors['error_count'] > 0))) {
            throw MF2Error::badOperand('Date/time core requires a valid host date/time value or ISO date string.');
        }
        return $parsed;
    }

    private static function formatSkeleton(string $skeleton, \DateTimeImmutable $value, array $localeData, ?string $hourCycle, bool $preserveSameFamilyHourCycle): string
    {
        if (strlen($skeleton) > self::MAX_SKELETON_LENGTH) {
            throw MF2Error::badOption('Date/time skeleton is too large.');
        }
        $semanticStyle = self::formatSemanticStyleSkeleton($skeleton, $value, $localeData, $hourCycle, $preserveSameFamilyHourCycle);
        if ($semanticStyle !== null) {
            return $semanticStyle;
        }
        $canonical = self::canonicalSkeleton($skeleton, $localeData, $hourCycle, $value);
        $suppressDayPeriod = self::shouldSuppressDayPeriod($skeleton);
        $dateTimeJoinStyle = self::skeletonDateTimeJoinStyle($skeleton);
        $pattern = self::skeletonPattern($canonical, $localeData);
        if ($pattern !== null) {
            if ($suppressDayPeriod) {
                $pattern = self::stripDayPeriodPatternFields($pattern);
            }
            return self::formatPattern($pattern, $value, $localeData);
        }
        return self::formatComposedSkeleton($skeleton, $canonical, $value, $localeData, $suppressDayPeriod, $dateTimeJoinStyle);
    }

    private static function skeletonDateTimeJoinStyle(string $skeleton): string
    {
        if (!str_starts_with($skeleton, self::SEMANTIC_SKELETON_PREFIX)) {
            return 'medium';
        }
        $options = self::parseSemanticSkeletonOptions(substr($skeleton, strlen(self::SEMANTIC_SKELETON_PREFIX)));
        return self::semanticOption($options, 'length', 'medium', ['full', 'long', 'medium', 'short']);
    }

    private static function formatSemanticStyleSkeleton(string $skeleton, \DateTimeImmutable $value, array $localeData, ?string $hourCycle, bool $preserveSameFamilyHourCycle): ?string
    {
        if (!str_starts_with($skeleton, self::SEMANTIC_SKELETON_PREFIX)) {
            return null;
        }
        $options = self::parseSemanticSkeletonOptions(substr($skeleton, strlen(self::SEMANTIC_SKELETON_PREFIX)));
        $fields = self::parseSemanticSkeletonFields($options);
        self::validateSemanticSkeleton($fields, $options);
        foreach (array_keys($options) as $key) {
            if (!in_array($key, self::SEMANTIC_DIRECT_STYLE_OPTION_KEYS, true)) {
                return null;
            }
        }

        $length = self::semanticOption($options, 'length', 'medium', ['full', 'long', 'medium', 'short']);
        $timeStyle = self::semanticOption($options, 'timestyle', 'auto', ['auto', 'short', 'medium', 'long', 'full']);
        $dateKey = self::semanticFieldSetKey($fields, self::SEMANTIC_DATE_FIELD_ORDER);
        $expectedDateKey = $length === 'full' ? 'year,month,day,weekday' : 'year,month,day';
        $hasDate = $dateKey !== '';
        $hasTime = in_array('time', $fields, true);
        $hasZone = in_array('zone', $fields, true);
        if (self::semanticFieldSetKey($fields, self::SEMANTIC_TIME_FIELD_ORDER) !== '') {
            return null;
        }
        if ($hasDate && $dateKey !== $expectedDateKey) {
            return null;
        }
        if ($hasTime && !array_key_exists('timestyle', $options)) {
            return null;
        }
        if (!$hasTime && ($hasZone || $timeStyle !== 'auto')) {
            return null;
        }
        if ($hasTime && $hasZone !== self::semanticTimeStyleHasZone($timeStyle)) {
            return null;
        }
        $expectedFieldCount = ($hasDate ? count(explode(',', $expectedDateKey)) : 0) + ($hasTime ? 1 : 0) + ($hasZone ? 1 : 0);
        if (count($fields) !== $expectedFieldCount) {
            return null;
        }

        if ($hasDate && $hasTime) {
            $datePart = self::formatPattern((string) $localeData['dateFormats'][$length], $value, $localeData);
            $timePart = self::formatTimeStylePattern((string) $localeData['timeFormats'][$timeStyle], $value, $localeData, $hourCycle, $preserveSameFamilyHourCycle);
            $joinPattern = self::dateTimeStyleJoinPattern($localeData, $length);
            return str_replace(['{1}', '{0}'], [$datePart, $timePart], $joinPattern);
        }
        if ($hasDate) {
            return self::formatPattern((string) $localeData['dateFormats'][$length], $value, $localeData);
        }
        if ($hasTime) {
            return self::formatTimeStylePattern((string) $localeData['timeFormats'][$timeStyle], $value, $localeData, $hourCycle, $preserveSameFamilyHourCycle);
        }
        return null;
    }

    private static function formatTimeStylePattern(string $pattern, \DateTimeImmutable $value, array $localeData, ?string $hourCycle, bool $preserveSameFamilyHourCycle): string
    {
        if ($hourCycle === null) {
            return self::formatPattern($pattern, $value, $localeData);
        }
        $hourSymbol = self::preferredHourSymbol($localeData, $hourCycle);
        $patternHourSymbol = self::timeStylePatternHourSymbol($pattern);
        if ($preserveSameFamilyHourCycle && $patternHourSymbol !== null && self::isHour12Field($patternHourSymbol) === self::isHour12Field($hourSymbol)) {
            return self::formatPattern(self::replaceTimeStylePatternHourSymbol($pattern, $hourSymbol), $value, $localeData);
        }
        $skeleton = self::timeStylePatternSkeleton($pattern, $localeData, $hourCycle);
        if ($skeleton === null) {
            return self::formatPattern($pattern, $value, $localeData);
        }
        $canonical = self::canonicalStandardSkeleton($skeleton, $localeData, null);
        return self::formatPattern(self::skeletonPattern($canonical, $localeData) ?? $pattern, $value, $localeData);
    }

    private static function dateTimeStyleJoinPattern(array $localeData, string $style): string
    {
        return (string) (
            $localeData['dateTimeStyleJoinFormats'][$style]
            ?? $localeData['dateTimeFormats'][$style]
            ?? $localeData['dateTimeFormats']['medium']
            ?? '{1} {0}'
        );
    }

    private static function timeStylePatternHourSymbol(string $pattern): ?string
    {
        $chars = preg_split('//u', $pattern, -1, PREG_SPLIT_NO_EMPTY) ?: [];
        for ($index = 0, $length = count($chars); $index < $length;) {
            $symbol = $chars[$index];
            if ($symbol === "'") {
                [, $index] = self::readQuotedPattern($chars, $index);
            } elseif (self::isAsciiLetter($symbol)) {
                $end = $index + 1;
                while ($end < $length && $chars[$end] === $symbol) {
                    $end += 1;
                }
                if (self::isHourField($symbol)) {
                    return $symbol;
                }
                $index = $end;
            } else {
                $index += 1;
            }
        }
        return null;
    }

    private static function replaceTimeStylePatternHourSymbol(string $pattern, string $hourSymbol): string
    {
        $chars = preg_split('//u', $pattern, -1, PREG_SPLIT_NO_EMPTY) ?: [];
        $output = '';
        for ($index = 0, $length = count($chars); $index < $length;) {
            $symbol = $chars[$index];
            if ($symbol === "'") {
                [, $nextIndex] = self::readQuotedPattern($chars, $index);
                $output .= implode('', array_slice($chars, $index, $nextIndex - $index));
                $index = $nextIndex;
            } elseif (self::isAsciiLetter($symbol)) {
                $end = $index + 1;
                while ($end < $length && $chars[$end] === $symbol) {
                    $end += 1;
                }
                $output .= self::isHourField($symbol)
                    ? str_repeat($hourSymbol, $end - $index)
                    : implode('', array_slice($chars, $index, $end - $index));
                $index = $end;
            } else {
                $output .= $symbol;
                $index += 1;
            }
        }
        return $output;
    }

    private static function timeStylePatternSkeleton(string $pattern, array $localeData, string $hourCycle): ?string
    {
        $chars = preg_split('//u', $pattern, -1, PREG_SPLIT_NO_EMPTY) ?: [];
        $widths = [];
        $hourSymbol = self::preferredHourSymbol($localeData, $hourCycle);
        $hasHour = false;
        for ($index = 0, $length = count($chars); $index < $length;) {
            $symbol = $chars[$index];
            if ($symbol === "'") {
                [, $index] = self::readQuotedPattern($chars, $index);
            } elseif (self::isAsciiLetter($symbol)) {
                $end = $index + 1;
                while ($end < $length && $chars[$end] === $symbol) {
                    $end += 1;
                }
                if (self::isHourField($symbol)) {
                    self::setSkeletonWidth($widths, $hourSymbol, $end - $index);
                    $hasHour = true;
                } elseif (!self::isDayPeriodField($symbol) && str_contains(self::SKELETON_TIME_FIELDS, $symbol)) {
                    self::setSkeletonWidth($widths, $symbol, $end - $index);
                }
                $index = $end;
            } else {
                $index += 1;
            }
        }
        if (!$hasHour) {
            return null;
        }
        $skeleton = '';
        foreach (str_split(self::SKELETON_FIELD_ORDER) as $symbol) {
            if (array_key_exists($symbol, $widths)) {
                $skeleton .= str_repeat($symbol, $widths[$symbol]);
            }
        }
        return $skeleton;
    }

    private static function skeletonPattern(string $canonical, array $localeData): ?string
    {
        $pattern = self::skeletonPatternWithoutAppend($canonical, $localeData);
        if ($pattern !== null) {
            return $pattern;
        }
        return self::hasDateAndTimeFields($canonical) ? null : self::appendedSkeletonPattern($canonical, $localeData);
    }

    private static function skeletonPatternWithoutAppend(string $canonical, array $localeData): ?string
    {
        if (isset($localeData['availableFormats'][$canonical])) {
            return (string) $localeData['availableFormats'][$canonical];
        }
        $requestedFields = self::skeletonFieldSet($canonical);
        $bestCandidate = null;
        $bestPattern = null;
        $bestDistance = null;
        foreach (($localeData['availableFormats'] ?? []) as $candidate => $pattern) {
            $candidate = (string) $candidate;
            if (self::skeletonFieldSet($candidate) !== $requestedFields) {
                continue;
            }
            $distance = self::skeletonDistance($canonical, $candidate);
            if ($bestDistance === null || $distance < $bestDistance || ($distance === $bestDistance && $candidate < $bestCandidate)) {
                $bestCandidate = $candidate;
                $bestPattern = (string) $pattern;
                $bestDistance = $distance;
            }
        }
        return $bestPattern === null ? self::syntheticSkeletonPattern($canonical, $localeData) : self::adjustPatternWidths($bestPattern, $canonical, (string) $bestCandidate);
    }

    private static function appendedSkeletonPattern(string $canonical, array $localeData): ?string
    {
        $requestedFields = self::skeletonFieldSet($canonical);
        $bestCandidate = null;
        $bestPattern = null;
        $bestFieldCount = -1;
        $bestDistance = null;
        foreach (($localeData['availableFormats'] ?? []) as $candidate => $pattern) {
            $candidate = (string) $candidate;
            $candidateFields = self::skeletonFieldSet($candidate);
            if ($candidateFields === '' || $candidateFields === $requestedFields) {
                continue;
            }
            if (!self::fieldSetContains($requestedFields, $candidateFields)) {
                continue;
            }
            $fieldCount = strlen($candidateFields);
            $distance = self::skeletonDistance($canonical, $candidate);
            if (
                $fieldCount > $bestFieldCount
                || $bestDistance === null
                || ($fieldCount === $bestFieldCount && ($distance < $bestDistance || ($distance === $bestDistance && $candidate < $bestCandidate)))
            ) {
                $bestCandidate = $candidate;
                $bestPattern = (string) $pattern;
                $bestFieldCount = $fieldCount;
                $bestDistance = $distance;
            }
        }
        if ($bestPattern === null || $bestCandidate === null) {
            return null;
        }
        $output = self::adjustPatternWidths($bestPattern, $canonical, $bestCandidate);
        $currentFields = [];
        foreach ((preg_split('//u', $bestCandidate, -1, PREG_SPLIT_NO_EMPTY) ?: []) as $symbol) {
            $currentFields[self::fieldSetSymbol($symbol)] = true;
        }
        $requestedWidths = self::skeletonWidths($canonical);
        foreach (str_split(self::SKELETON_FIELD_ORDER) as $symbol) {
            if (!array_key_exists($symbol, $requestedWidths)) {
                continue;
            }
            $field = self::fieldSetSymbol($symbol);
            if (isset($currentFields[$field])) {
                continue;
            }
            $key = self::appendItemKey($symbol);
            $fieldSkeleton = str_repeat($symbol, $requestedWidths[$symbol]);
            $fieldPattern = self::skeletonPatternWithoutAppend($fieldSkeleton, $localeData) ?? $fieldSkeleton;
            if ($key === null) {
                return null;
            }
            $output = self::applyAppendItemPattern(
                self::appendItemTemplate($localeData, $key),
                $output,
                $fieldPattern,
                (string) ($localeData['fieldNames'][$key] ?? $key),
            );
            $currentFields[$field] = true;
        }
        return $output;
    }

    private static function fieldSetContains(string $container, string $subset): bool
    {
        foreach (str_split($subset) as $field) {
            if (!str_contains($container, $field)) {
                return false;
            }
        }
        return true;
    }

    private static function applyAppendItemPattern(string $template, string $basePattern, string $fieldPattern, string $fieldName): string
    {
        return str_replace(['{0}', '{1}', '{2}'], [$basePattern, $fieldPattern, self::quotePatternLiteral($fieldName)], $template);
    }

    private static function quotePatternLiteral(string $value): string
    {
        return "'" . str_replace("'", "''", $value) . "'";
    }

    private static function appendItemTemplate(array $localeData, string $key): string
    {
        return (string) ($localeData['appendItems'][$key] ?? self::defaultAppendItemTemplate($key));
    }

    private static function defaultAppendItemTemplate(string $key): string
    {
        return match ($key) {
            'Quarter', 'Month', 'Week', 'Day', 'Hour', 'Minute', 'Second' => '{0} ({2}: {1})',
            default => '{0} {1}',
        };
    }

    private static function hasDateAndTimeFields(string $canonical): bool
    {
        [$dateSkeleton, $timeSkeleton] = self::splitDateTimeSkeleton($canonical);
        return $dateSkeleton !== '' && $timeSkeleton !== '';
    }

    private static function appendItemKey(string $symbol): ?string
    {
        if ($symbol === 'G') {
            return 'Era';
        }
        if (self::isYearField($symbol)) {
            return 'Year';
        }
        if (self::isQuarterField($symbol)) {
            return 'Quarter';
        }
        if (self::isMonthField($symbol)) {
            return 'Month';
        }
        if ($symbol === 'w' || $symbol === 'W') {
            return 'Week';
        }
        if ($symbol === 'd' || $symbol === 'D' || $symbol === 'F' || $symbol === 'g') {
            return 'Day';
        }
        if (self::isWeekdayField($symbol)) {
            return 'Day-Of-Week';
        }
        if (self::isHourField($symbol)) {
            return 'Hour';
        }
        if ($symbol === 'm') {
            return 'Minute';
        }
        if ($symbol === 's' || $symbol === 'S' || $symbol === 'A') {
            return 'Second';
        }
        if (self::isTimeZoneField($symbol)) {
            return 'Timezone';
        }
        return null;
    }

    private static function syntheticSkeletonPattern(string $canonical, array $localeData): ?string
    {
        $widths = self::skeletonWidths($canonical);
        if (count($widths) === 1) {
            $symbol = (string) array_key_first($widths);
            if ($symbol === 'G') {
                return str_repeat($symbol, $widths[$symbol]);
            }
            if (self::isDayPeriodField($symbol)) {
                return str_repeat($symbol, $widths[$symbol]);
            }
            if (self::isQuarterField($symbol)) {
                return str_repeat($symbol, $widths[$symbol]);
            }
            if (self::isSyntheticNumericField($symbol)) {
                return str_repeat($symbol, $widths[$symbol]);
            }
            if ($symbol === 'S') {
                return str_repeat($symbol, $widths[$symbol]);
            }
            if (self::isTimeZoneField($symbol)) {
                return str_repeat($symbol, $widths[$symbol]);
            }
        }
        return self::syntheticFractionalSecondPattern($canonical, $localeData, $widths);
    }

    private static function syntheticFractionalSecondPattern(string $canonical, array $localeData, array $widths): ?string
    {
        $fractionWidth = $widths['S'] ?? null;
        if ($fractionWidth === null || !array_key_exists('s', $widths)) {
            return null;
        }
        $baseSkeleton = self::skeletonWithoutField($canonical, 'S');
        $basePattern = self::skeletonPattern($baseSkeleton, $localeData) ?? self::syntheticSecondsPattern($baseSkeleton);
        return $basePattern === null ? null : self::insertFractionalSecond($basePattern, $fractionWidth, $localeData['decimalSeparator'] ?? '.');
    }

    private static function syntheticSecondsPattern(string $canonical): ?string
    {
        $widths = self::skeletonWidths($canonical);
        return count($widths) === 1 && array_key_exists('s', $widths) ? str_repeat('s', $widths['s']) : null;
    }

    private static function skeletonWithoutField(string $skeleton, string $removedSymbol): string
    {
        $chars = preg_split('//u', $skeleton, -1, PREG_SPLIT_NO_EMPTY) ?: [];
        $output = '';
        for ($index = 0, $length = count($chars); $index < $length;) {
            $symbol = $chars[$index];
            $end = $index + 1;
            while ($end < $length && $chars[$end] === $symbol) {
                $end += 1;
            }
            if ($symbol !== $removedSymbol) {
                $output .= implode('', array_slice($chars, $index, $end - $index));
            }
            $index = $end;
        }
        return $output;
    }

    private static function insertFractionalSecond(string $pattern, int $width, string $decimalSeparator): ?string
    {
        $chars = preg_split('//u', $pattern, -1, PREG_SPLIT_NO_EMPTY) ?: [];
        $output = '';
        $inQuote = false;
        for ($index = 0, $length = count($chars); $index < $length;) {
            $ch = $chars[$index];
            if ($ch === "'") {
                $output .= $ch;
                if (($chars[$index + 1] ?? null) === "'") {
                    $output .= "'";
                    $index += 2;
                } else {
                    $inQuote = !$inQuote;
                    $index += 1;
                }
            } elseif (!$inQuote && $ch === 's') {
                $end = $index + 1;
                while ($end < $length && $chars[$end] === $ch) {
                    $end += 1;
                }
                return $output
                    . implode('', array_slice($chars, $index, $end - $index))
                    . $decimalSeparator
                    . str_repeat('S', $width)
                    . implode('', array_slice($chars, $end));
            } else {
                $output .= $ch;
                $index += 1;
            }
        }
        return null;
    }

    private static function formatComposedSkeleton(string $rawSkeleton, string $canonical, \DateTimeImmutable $value, array $localeData, bool $suppressDayPeriod, string $dateTimeJoinStyle): string
    {
        [$dateSkeleton, $timeSkeleton] = self::splitDateTimeSkeleton($canonical);
        if ($dateSkeleton === '' || $timeSkeleton === '') {
            throw self::unsupportedSkeleton($rawSkeleton);
        }
        $datePattern = self::skeletonPattern($dateSkeleton, $localeData);
        if ($datePattern === null) {
            throw self::unsupportedSkeleton($rawSkeleton);
        }
        $timePattern = self::skeletonPattern($timeSkeleton, $localeData);
        if ($timePattern === null) {
            throw self::unsupportedSkeleton($rawSkeleton);
        }
        if ($suppressDayPeriod) {
            $timePattern = self::stripDayPeriodPatternFields($timePattern);
        }
        $datePart = self::formatPattern($datePattern, $value, $localeData);
        $timePart = self::formatPattern($timePattern, $value, $localeData);
        $joinPattern = $localeData['dateTimeFormats'][$dateTimeJoinStyle] ?? $localeData['dateTimeFormats']['medium'] ?? '{1} {0}';
        return str_replace(['{1}', '{0}'], [$datePart, $timePart], (string) $joinPattern);
    }

    private static function unsupportedSkeleton(string $skeleton): MF2Error
    {
        return MF2Error::badOption("Unsupported CLDR date/time skeleton: {$skeleton}.");
    }

    private static function canonicalSkeleton(string $skeleton, array $localeData, ?string $hourCycle, \DateTimeImmutable $value): string
    {
        if (str_starts_with($skeleton, self::SEMANTIC_SKELETON_PREFIX)) {
            $standard = self::semanticSkeletonToStandard(substr($skeleton, strlen(self::SEMANTIC_SKELETON_PREFIX)), $localeData, $value);
            return self::canonicalStandardSkeleton($standard, $localeData, $hourCycle);
        }
        return self::canonicalStandardSkeleton($skeleton, $localeData, $hourCycle);
    }

    private static function canonicalStandardSkeleton(string $skeleton, array $localeData, ?string $hourCycle): string
    {
        $chars = preg_split('//u', $skeleton, -1, PREG_SPLIT_NO_EMPTY) ?: [];
        $widths = [];
        for ($index = 0, $length = count($chars); $index < $length;) {
            $symbol = $chars[$index];
            if (!self::isAsciiLetter($symbol)) {
                throw MF2Error::badOption('Date/time skeleton must contain only ASCII pattern letters.');
            }
            $end = $index + 1;
            while ($end < $length && $chars[$end] === $symbol) {
                $end += 1;
            }
            $width = $end - $index;
            if ($width > self::MAX_SKELETON_FIELD_WIDTH) {
                throw MF2Error::badOption('Date/time skeleton field width is too large.');
            }
            if ($symbol === 'C') {
                self::applyCHourFormat($widths, $localeData, $hourCycle, $width);
            } else {
                $normalized = self::normalizeSkeletonSymbol($symbol, $localeData, $hourCycle);
                self::setSkeletonWidth($widths, $normalized, $width);
            }
            $index = $end;
        }
        $canonical = '';
        foreach (str_split(self::SKELETON_FIELD_ORDER) as $symbol) {
            $canonical .= str_repeat($symbol, $widths[$symbol] ?? 0);
        }
        if ($canonical === '') {
            throw MF2Error::badOption('Date/time skeleton must not be empty.');
        }
        return $canonical;
    }

    private static function semanticSkeletonToStandard(string $body, array $localeData, \DateTimeImmutable $value): string
    {
        $options = self::parseSemanticSkeletonOptions($body);
        $fields = self::parseSemanticSkeletonFields($options);
        self::validateSemanticSkeleton($fields, $options);
        $length = self::semanticOption($options, 'length', 'medium', ['full', 'long', 'medium', 'short']);
        $alignment = self::semanticOption($options, 'alignment', 'inline', ['inline', 'column']);
        $yearStyle = self::semanticOption($options, 'yearstyle', 'auto', ['auto', 'full', 'with-era', 'numeric', '2-digit']);
        $eraStyle = self::semanticOption($options, 'erastyle', 'auto', self::SEMANTIC_TEXT_STYLE_VALUES);
        $monthStyle = self::semanticOption($options, 'monthstyle', 'auto', self::SEMANTIC_DATE_STYLE_VALUES);
        $quarterStyle = self::semanticOption($options, 'quarterstyle', 'auto', self::SEMANTIC_DATE_STYLE_VALUES);
        $dayStyle = self::semanticOption($options, 'daystyle', 'auto', self::SEMANTIC_NUMERIC_STYLE_VALUES);
        $weekdayStyle = self::semanticOption($options, 'weekdaystyle', 'auto', self::SEMANTIC_TEXT_STYLE_VALUES);
        $dayPeriodStyle = self::semanticOption($options, 'dayperiodstyle', 'auto', self::SEMANTIC_TEXT_STYLE_VALUES);
        self::semanticOption($options, 'hourstyle', 'auto', self::SEMANTIC_NUMERIC_STYLE_VALUES);
        self::semanticOption($options, 'minutestyle', 'auto', self::SEMANTIC_NUMERIC_STYLE_VALUES);
        self::semanticOption($options, 'secondstyle', 'auto', self::SEMANTIC_NUMERIC_STYLE_VALUES);
        $timePrecision = self::semanticOption($options, 'timeprecision', 'second', ['hour', 'minute', 'minute-optional', 'second', 'fractional-second']);
        $timeStyle = self::semanticOption($options, 'timestyle', 'auto', ['auto', 'short', 'medium', 'long', 'full']);
        $effectiveTimePrecision = self::semanticTimeStylePrecision($timeStyle, $timePrecision);
        $semanticHourCycle = self::semanticOption($options, 'hourcycle', 'auto', ['auto', 'h11', 'h12', 'h23', 'h24', 'clock12', 'clock24']);
        $zoneStyle = self::semanticOption($options, 'zonestyle', 'auto', ['auto', 'generic', 'specific', 'location', 'offset']);
        $effectiveZoneStyle = self::semanticTimeStyleZoneStyle($timeStyle, $zoneStyle);
        $effectiveZoneStandalone = count($fields) === 1 || $timeStyle === 'full';
        $effectiveZoneLength = self::semanticTimeStyleHasZone($timeStyle) ? $timeStyle : $length;
        $dateWidths = self::semanticDateFieldWidths($localeData, $length);
        $standard = '';
        if (in_array('era', $fields, true)) {
            $standard .= self::semanticEraSkeleton($dateWidths, $length, $eraStyle);
        }
        if (in_array('year', $fields, true)) {
            $standard .= self::semanticYearSkeleton($dateWidths, $yearStyle, !in_array('era', $fields, true));
        }
        if (in_array('quarter', $fields, true)) {
            $standard .= self::semanticQuarterSkeleton($fields, $length, $alignment, $quarterStyle);
        }
        if (in_array('month', $fields, true)) {
            $standard .= self::semanticMonthSkeleton($fields, $dateWidths, $length, $alignment, $monthStyle);
        }
        if (in_array('weekofmonth', $fields, true)) {
            $standard .= 'W';
        }
        if (in_array('day', $fields, true)) {
            $standard .= self::semanticDaySkeleton($dateWidths, $alignment, $dayStyle);
        }
        if (in_array('dayofyear', $fields, true)) {
            $standard .= str_repeat('D', $alignment === 'column' ? 3 : 1);
        }
        if (in_array('dayofweekinmonth', $fields, true)) {
            $standard .= str_repeat('F', $alignment === 'column' ? 2 : 1);
        }
        if (in_array('modifiedjulianday', $fields, true)) {
            $standard .= str_repeat('g', $alignment === 'column' ? 6 : 1);
        }
        if (in_array('weekday', $fields, true)) {
            $standard .= self::semanticWeekdaySkeleton($fields, $length, $weekdayStyle);
        }
        if (in_array('weekofyear', $fields, true)) {
            $standard .= $alignment === 'column' ? 'ww' : 'w';
        }
        if (in_array('dayperiod', $fields, true)) {
            $standard .= self::semanticDayPeriodSkeleton($length, $dayPeriodStyle);
        }
        if (self::hasSemanticTimeComponents($fields)) {
            $standard .= self::semanticExplicitTimeSkeleton($fields, $semanticHourCycle, $alignment, $options);
        }
        if (in_array('time', $fields, true)) {
            $standard .= self::semanticTimeSkeleton($effectiveTimePrecision, $semanticHourCycle, $alignment, $value, $options);
        }
        if (in_array('zone', $fields, true)) {
            $standard .= self::semanticZoneSkeleton($effectiveZoneStyle, $effectiveZoneStandalone, $effectiveZoneLength);
        }
        if ($standard === '') {
            throw MF2Error::badOption('Date/time semantic skeleton must include at least one field.');
        }
        return $standard;
    }

    private static function parseSemanticSkeletonOptions(string $body): array
    {
        $options = [];
        $seenParts = 0;
        $implicitDateStyle = null;
        $implicitTimeFields = false;
        foreach (explode(';', $body) as $rawPart) {
            $part = trim($rawPart);
            if ($part === '') {
                continue;
            }
            $equals = strpos($part, '=');
            $rawKey = $equals === false ? ($seenParts === 0 ? 'fields' : '') : substr($part, 0, $equals);
            $rawValue = $equals === false ? $part : substr($part, $equals + 1);
            $rawKeyAlias = self::semanticNormalize($rawKey);
            $key = self::semanticNormalizeOptionKey($rawKey);
            $value = self::semanticNormalizeOptionValue($key, $rawValue);
            if ($key === '' || $value === '' || !in_array($key, self::SEMANTIC_OPTION_KEYS, true) || array_key_exists($key, $options)) {
                throw MF2Error::badOption('Invalid date/time semantic skeleton option.');
            }
            if ($rawKeyAlias === 'style' || $rawKeyAlias === 'datestyle' || $rawKeyAlias === 'datelength') {
                $implicitDateStyle = $value;
            }
            if ($rawKeyAlias === 'timestyle') {
                $implicitTimeFields = true;
            }
            $options[$key] = $value;
            $seenParts += 1;
        }
        if ($seenParts === 0) {
            throw MF2Error::badOption('Date/time semantic skeleton must include fields.');
        }
        if (!array_key_exists('fields', $options)) {
            $fields = self::implicitSemanticFields($implicitDateStyle, $implicitTimeFields, $options['timestyle'] ?? null);
            if ($fields !== '') {
                $options['fields'] = $fields;
            }
        }
        return $options;
    }

    private static function implicitSemanticFields(?string $dateStyle, bool $hasTimeStyle, ?string $timeStyle): string
    {
        $dateFields = $dateStyle === 'full' ? 'date,weekday' : 'date';
        if ($dateStyle !== null && $hasTimeStyle) {
            return $timeStyle === 'long' || $timeStyle === 'full' ? "{$dateFields},time,zone" : "{$dateFields},time";
        }
        if ($dateStyle !== null) {
            return $dateFields;
        }
        if ($hasTimeStyle) {
            return $timeStyle === 'long' || $timeStyle === 'full' ? 'time,zone' : 'time';
        }
        return '';
    }

    private static function semanticNormalizeOptionKey(string $value): string
    {
        $normalized = self::semanticNormalize($value);
        if ($normalized === 'style' || $normalized === 'datestyle' || $normalized === 'datelength') {
            return 'length';
        }
        if ($normalized === 'precision') {
            return 'timeprecision';
        }
        if ($normalized === 'timestyle') {
            return 'timestyle';
        }
        if ($normalized === 'hour12') {
            return 'hourcycle';
        }
        if ($normalized === 'zone' || $normalized === 'timezonename' || $normalized === 'timezonestyle') {
            return 'zonestyle';
        }
        if ($normalized === 'fractionalseconddigits') {
            return 'fractionalsecond';
        }
        if ($normalized === 'era') {
            return 'erastyle';
        }
        if ($normalized === 'year') {
            return 'yearstyle';
        }
        if ($normalized === 'month') {
            return 'monthstyle';
        }
        if ($normalized === 'quarter') {
            return 'quarterstyle';
        }
        if ($normalized === 'day') {
            return 'daystyle';
        }
        if ($normalized === 'weekday') {
            return 'weekdaystyle';
        }
        if ($normalized === 'dayperiod') {
            return 'dayperiodstyle';
        }
        if ($normalized === 'hour') {
            return 'hourstyle';
        }
        if ($normalized === 'minute') {
            return 'minutestyle';
        }
        if ($normalized === 'second') {
            return 'secondstyle';
        }
        return $normalized;
    }

    private static function semanticNormalizeOptionValue(string $key, string $value): string
    {
        if ($key === 'fields') {
            return strtolower(trim($value));
        }
        $normalized = self::semanticNormalize($value);
        if ($key === 'yearstyle' && $normalized === 'withera') {
            return 'with-era';
        }
        if (in_array($key, self::SEMANTIC_STYLE_OPTION_KEYS, true) && ($normalized === '2digit' || $normalized === 'twodigit')) {
            return '2-digit';
        }
        if (in_array($key, self::SEMANTIC_STYLE_OPTION_KEYS, true) && $normalized === 'wide') {
            return 'long';
        }
        if (in_array($key, self::SEMANTIC_STYLE_OPTION_KEYS, true) && $normalized === 'abbreviated') {
            return 'short';
        }
        if ($key === 'timeprecision' && $normalized === 'short') {
            return 'minute';
        }
        if ($key === 'timeprecision' && $normalized === 'medium') {
            return 'second';
        }
        if ($key === 'timeprecision' && $normalized === 'minuteoptional') {
            return 'minute-optional';
        }
        if ($key === 'timeprecision' && $normalized === 'fractionalsecond') {
            return 'fractional-second';
        }
        if ($key === 'zonestyle' && ($normalized === 'shortoffset' || $normalized === 'longoffset')) {
            return 'offset';
        }
        if ($key === 'zonestyle' && ($normalized === 'shortgeneric' || $normalized === 'longgeneric')) {
            return 'generic';
        }
        if ($key === 'zonestyle' && ($normalized === 'short' || $normalized === 'long')) {
            return 'specific';
        }
        if ($key === 'hourcycle' && $normalized === 'true') {
            return 'clock12';
        }
        if ($key === 'hourcycle' && $normalized === 'false') {
            return 'clock24';
        }
        return $normalized;
    }

    private static function parseSemanticSkeletonFields(array $options): array
    {
        $fieldsText = $options['fields'] ?? null;
        if ($fieldsText === null) {
            throw MF2Error::badOption('Date/time semantic skeleton must include fields.');
        }
        $fields = [];
        foreach (explode(',', $fieldsText) as $field) {
            $normalized = self::semanticNormalizeField($field);
            $canonicalFields = match ($normalized) {
                'date', 'yearmonthday' => ['year', 'month', 'day'],
                'eradate', 'erayearmonthday' => ['era', 'year', 'month', 'day'],
                'eradateweekday', 'weekdayeradate', 'erayearmonthdayweekday', 'weekdayerayearmonthday' => ['era', 'year', 'month', 'day', 'weekday'],
                'eradatetime', 'erayearmonthdaytime' => ['era', 'year', 'month', 'day', 'time'],
                'eradatetimeweekday', 'weekdayeradatetime', 'erayearmonthdaytimeweekday', 'weekdayerayearmonthdaytime' => ['era', 'year', 'month', 'day', 'weekday', 'time'],
                'datetime', 'yearmonthdaytime' => ['year', 'month', 'day', 'time'],
                'datetimeweekday', 'weekdaydatetime', 'yearmonthdaytimeweekday', 'weekdayyearmonthdaytime' => ['year', 'month', 'day', 'weekday', 'time'],
                'datetimeweekdayzone', 'weekdaydatetimezone', 'zoneddatetimeweekday', 'zonedweekdaydatetime', 'yearmonthdaytimeweekdayzone', 'weekdayyearmonthdaytimezone', 'zonedyearmonthdaytimeweekday', 'zonedweekdayyearmonthdaytime' => ['year', 'month', 'day', 'weekday', 'time', 'zone'],
                'eradatetimezone', 'zonederadatetime', 'erayearmonthdaytimezone', 'zonederayearmonthdaytime' => ['era', 'year', 'month', 'day', 'time', 'zone'],
                'eradatetimeweekdayzone', 'weekdayeradatetimezone', 'zonederadatetimeweekday', 'zonedweekdayeradatetime', 'erayearmonthdaytimeweekdayzone', 'weekdayerayearmonthdaytimezone', 'zonederayearmonthdaytimeweekday', 'zonedweekdayerayearmonthdaytime' => ['era', 'year', 'month', 'day', 'weekday', 'time', 'zone'],
                'dateweekday', 'weekdaydate', 'yearmonthdayweekday', 'weekdayyearmonthday' => ['year', 'month', 'day', 'weekday'],
                'datetimezone', 'zoneddatetime', 'yearmonthdaytimezone', 'zonedyearmonthdaytime' => ['year', 'month', 'day', 'time', 'zone'],
                'yearmonth' => ['year', 'month'],
                'erayearmonth' => ['era', 'year', 'month'],
                'yearquarter' => ['year', 'quarter'],
                'erayearquarter' => ['era', 'year', 'quarter'],
                'yearweek' => ['year', 'weekofyear'],
                'erayearweek' => ['era', 'year', 'weekofyear'],
                'erayear' => ['era', 'year'],
                'monthweek' => ['month', 'weekofmonth'],
                'yearmonthweek' => ['year', 'month', 'weekofmonth'],
                'erayearmonthweek' => ['era', 'year', 'month', 'weekofmonth'],
                'monthday' => ['month', 'day'],
                default => [$normalized],
            };
            foreach ($canonicalFields as $canonical) {
                if (!in_array($canonical, self::SEMANTIC_FIELD_ORDER, true) || in_array($canonical, $fields, true)) {
                    throw MF2Error::badOption('Invalid date/time semantic skeleton field.');
                }
                $fields[] = $canonical;
            }
        }
        if ($fields === []) {
            throw MF2Error::badOption('Date/time semantic skeleton must include fields.');
        }
        return $fields;
    }

    private static function semanticNormalizeField(string $value): string
    {
        $normalized = self::semanticNormalize($value);
        if ($normalized === 'dayofmonth') {
            return 'day';
        }
        if ($normalized === 'dayofweek') {
            return 'weekday';
        }
        if ($normalized === 'monthofyear') {
            return 'month';
        }
        if ($normalized === 'quarterofyear') {
            return 'quarter';
        }
        if ($normalized === 'yearofera') {
            return 'year';
        }
        if ($normalized === 'week') {
            return 'weekofyear';
        }
        if ($normalized === 'weekofyear') {
            return 'weekofyear';
        }
        if ($normalized === 'weekofmonth') {
            return 'weekofmonth';
        }
        if ($normalized === 'dayofyear') {
            return 'dayofyear';
        }
        if ($normalized === 'dayofweekinmonth') {
            return 'dayofweekinmonth';
        }
        if ($normalized === 'modifiedjulianday') {
            return 'modifiedjulianday';
        }
        if ($normalized === 'millisecondsinday') {
            return 'millisecondsinday';
        }
        if ($normalized === 'fractionalseconddigits') {
            return 'fractionalsecond';
        }
        if ($normalized === 'dayperiod') {
            return 'dayperiod';
        }
        if ($normalized === 'hourofday') {
            return 'hour';
        }
        if ($normalized === 'minuteofhour') {
            return 'minute';
        }
        if ($normalized === 'secondofminute') {
            return 'second';
        }
        if ($normalized === 'timezonename') {
            return 'zone';
        }
        if ($normalized === 'timezone') {
            return 'zone';
        }
        return $normalized;
    }

    private static function validateSemanticSkeleton(array $fields, array $options): void
    {
        $dateKey = self::semanticFieldSetKey($fields, self::SEMANTIC_DATE_FIELD_ORDER);
        $timeKey = self::semanticFieldSetKey($fields, self::SEMANTIC_TIME_FIELD_ORDER);
        $hasDateFields = $dateKey !== '';
        $hasExplicitTime = $timeKey !== '';
        $hasTime = in_array('time', $fields, true) || $hasExplicitTime;
        $hasZone = in_array('zone', $fields, true);
        $hasDayPeriod = in_array('dayperiod', $fields, true);
        $validDateFields = $hasTime || $hasZone
            ? !$hasDateFields || in_array($dateKey, self::SEMANTIC_DATE_FIELD_SETS, true)
            : !$hasDateFields || in_array($dateKey, self::SEMANTIC_DATE_FIELD_SETS, true) || in_array($dateKey, self::SEMANTIC_CALENDAR_PERIOD_FIELD_SETS, true);
        $validFieldSet = $hasDayPeriod
            ? $validDateFields && ($hasTime || !$hasZone)
            : ($hasTime || $hasZone
                ? !$hasDateFields || in_array($dateKey, self::SEMANTIC_DATE_FIELD_SETS, true)
                : in_array($dateKey, self::SEMANTIC_DATE_FIELD_SETS, true) || in_array($dateKey, self::SEMANTIC_CALENDAR_PERIOD_FIELD_SETS, true));
        if (!$validFieldSet) {
            throw MF2Error::badOption('Invalid date/time semantic skeleton field set.');
        }
        if (in_array('time', $fields, true) && $hasExplicitTime) {
            throw MF2Error::badOption('time field cannot be combined with explicit time component fields.');
        }
        if (array_key_exists('timestyle', $options) && array_key_exists('timeprecision', $options)) {
            throw MF2Error::badOption('timeStyle cannot be combined with timePrecision.');
        }
        $timeStyle = $options['timestyle'] ?? null;
        if (array_key_exists('timestyle', $options) && !in_array('time', $fields, true)) {
            throw MF2Error::badOption('timeStyle requires the time field.');
        }
        if (self::semanticTimeStyleHasZone($timeStyle) && !$hasZone) {
            throw MF2Error::badOption('timeStyle=long/full requires the zone field.');
        }
        if (self::semanticTimeStyleHasZone($timeStyle) && array_key_exists('zonestyle', $options)) {
            throw MF2Error::badOption('timeStyle=long/full cannot be combined with zoneStyle.');
        }
        if ($hasExplicitTime && !in_array($timeKey, self::SEMANTIC_TIME_FIELD_SETS, true)) {
            throw MF2Error::badOption('Invalid date/time semantic skeleton time field set.');
        }
        if ($hasExplicitTime && array_key_exists('timeprecision', $options)) {
            throw MF2Error::badOption('timePrecision requires the time field.');
        }
        if ($hasExplicitTime && array_key_exists('fractionalsecond', $options) && !in_array('fractionalsecond', $fields, true)) {
            throw MF2Error::badOption('fractionalSecond requires the fractionalSecond field.');
        }
        if (in_array('fractionalsecond', $fields, true)) {
            self::semanticFractionalSecondWidth($options);
        }
        if ($hasExplicitTime && !in_array('hour', $fields, true) && (array_key_exists('hourcycle', $options) || $hasDayPeriod)) {
            throw MF2Error::badOption('hourCycle and dayPeriod require the hour field.');
        }
        if (!in_array('hour', $fields, true) && array_key_exists('hourstyle', $options)) {
            throw MF2Error::badOption('hourStyle requires the hour field.');
        }
        if (!in_array('minute', $fields, true) && array_key_exists('minutestyle', $options)) {
            throw MF2Error::badOption('minuteStyle requires the minute field.');
        }
        if (!in_array('second', $fields, true) && array_key_exists('secondstyle', $options)) {
            throw MF2Error::badOption('secondStyle requires the second field.');
        }
        if (!in_array('year', $fields, true) && array_key_exists('yearstyle', $options)) {
            throw MF2Error::badOption('yearStyle requires the year field.');
        }
        if (!in_array('era', $fields, true) && array_key_exists('erastyle', $options)) {
            throw MF2Error::badOption('eraStyle requires the era field.');
        }
        if (!in_array('month', $fields, true) && array_key_exists('monthstyle', $options)) {
            throw MF2Error::badOption('monthStyle requires the month field.');
        }
        if (!in_array('quarter', $fields, true) && array_key_exists('quarterstyle', $options)) {
            throw MF2Error::badOption('quarterStyle requires the quarter field.');
        }
        if (!in_array('day', $fields, true) && array_key_exists('daystyle', $options)) {
            throw MF2Error::badOption('dayStyle requires the day field.');
        }
        if (!in_array('weekday', $fields, true) && array_key_exists('weekdaystyle', $options)) {
            throw MF2Error::badOption('weekdayStyle requires the weekday field.');
        }
        if (!$hasDayPeriod && array_key_exists('dayperiodstyle', $options)) {
            throw MF2Error::badOption('dayPeriodStyle requires the dayPeriod field.');
        }
        if (!$hasTime && (array_key_exists('timeprecision', $options) || array_key_exists('timestyle', $options) || array_key_exists('fractionalsecond', $options) || array_key_exists('hourcycle', $options))) {
            throw MF2Error::badOption('timePrecision and hourCycle require the time field.');
        }
        if (!$hasZone && array_key_exists('zonestyle', $options)) {
            throw MF2Error::badOption('zoneStyle requires the zone field.');
        }
        if (!(in_array('year', $fields, true) || in_array('quarter', $fields, true) || in_array('month', $fields, true) || in_array('day', $fields, true) || in_array('dayofyear', $fields, true) || in_array('dayofweekinmonth', $fields, true) || in_array('modifiedjulianday', $fields, true) || $hasTime) && array_key_exists('alignment', $options)) {
            throw MF2Error::badOption('alignment requires a date or time field.');
        }
    }

    private static function semanticOption(array $options, string $key, string $fallback, array $allowedValues): string
    {
        $value = $options[$key] ?? $fallback;
        if (!in_array($value, $allowedValues, true)) {
            throw MF2Error::badOption('Date/time semantic skeleton ' . $key . ' must be one of ' . implode(', ', $allowedValues) . '.');
        }
        return $value;
    }

    private static function semanticNormalize(string $value): string
    {
        return strtolower(str_replace(['-', '_'], '', trim($value)));
    }

    private static function semanticFieldSetKey(array $fields, array $order): string
    {
        $output = [];
        foreach ($order as $field) {
            if (in_array($field, $fields, true)) {
                $output[] = $field;
            }
        }
        return implode(',', $output);
    }

    private static function semanticDateFieldWidths(array $localeData, string $length): array
    {
        $widths = [];
        foreach (self::patternFieldRuns((string) ($localeData['dateFormats'][$length] ?? '')) as $symbol => $width) {
            if ($symbol === 'G' || self::isYearField($symbol) || self::isMonthField($symbol) || $symbol === 'd') {
                self::setSkeletonWidth($widths, $symbol, $width);
            }
        }
        $hasYear = false;
        foreach (array_keys($widths) as $symbol) {
            if (self::isYearField($symbol)) {
                $hasYear = true;
                break;
            }
        }
        if (!$hasYear) {
            self::setSkeletonWidth($widths, 'y', $length === 'short' ? 2 : 1);
        }
        $hasMonth = false;
        foreach (array_keys($widths) as $symbol) {
            if (self::isMonthField($symbol)) {
                $hasMonth = true;
                break;
            }
        }
        if (!$hasMonth) {
            self::setSkeletonWidth($widths, 'M', self::isWideLength($length) ? 4 : ($length === 'medium' ? 3 : 1));
        }
        $widths['d'] ??= 1;
        return $widths;
    }

    private static function patternFieldRuns(string $pattern): array
    {
        $chars = preg_split('//u', $pattern, -1, PREG_SPLIT_NO_EMPTY) ?: [];
        $fields = [];
        $inQuote = false;
        for ($index = 0, $length = count($chars); $index < $length;) {
            $symbol = $chars[$index];
            if ($symbol === "'") {
                if ($index + 1 < $length && $chars[$index + 1] === "'") {
                    $index += 2;
                } else {
                    $inQuote = !$inQuote;
                    $index += 1;
                }
            } elseif (!$inQuote && self::isAsciiLetter($symbol)) {
                $end = $index + 1;
                while ($end < $length && $chars[$end] === $symbol) {
                    $end += 1;
                }
                self::setSkeletonWidth($fields, $symbol, $end - $index);
                $index = $end;
            } else {
                $index += 1;
            }
        }
        return $fields;
    }

    private static function semanticEraSkeleton(array $dateWidths, string $length, string $eraStyle): string
    {
        $width = $eraStyle === 'auto' ? ($dateWidths['G'] ?? (self::isWideLength($length) ? 4 : 1)) : self::eraStyleWidth($eraStyle);
        return str_repeat('G', $width);
    }

    private static function eraStyleWidth(string $style): int
    {
        return $style === 'long' ? 4 : ($style === 'narrow' ? 5 : 1);
    }

    private static function semanticYearSkeleton(array $dateWidths, string $yearStyle, bool $includeEra = true): string
    {
        $yearSymbol = array_key_exists('y', $dateWidths) ? 'y' : (array_key_exists('u', $dateWidths) ? 'u' : (array_key_exists('r', $dateWidths) ? 'r' : 'y'));
        $sourceWidth = $dateWidths[$yearSymbol] ?? 1;
        $yearWidth = self::semanticYearWidth($sourceWidth, $yearStyle);
        $skeleton = $includeEra && array_key_exists('G', $dateWidths) ? str_repeat('G', $dateWidths['G']) : '';
        if ($includeEra && $yearStyle === 'with-era' && !array_key_exists('G', $dateWidths)) {
            $skeleton .= 'G';
        }
        return $skeleton . str_repeat($yearSymbol, $yearWidth);
    }

    private static function semanticYearWidth(int $sourceWidth, string $yearStyle): int
    {
        return match ($yearStyle) {
            'auto' => $sourceWidth,
            '2-digit' => 2,
            'numeric' => 1,
            default => $sourceWidth === 2 ? 1 : $sourceWidth,
        };
    }

    private static function semanticQuarterSkeleton(array $fields, string $length, string $alignment, string $quarterStyle): string
    {
        $symbol = count($fields) === 1 ? 'q' : 'Q';
        $width = $quarterStyle === 'auto' ? self::lengthStyleWidth($length) : self::dateFieldStyleWidth($quarterStyle);
        if ($alignment === 'column' && $width < 3) {
            $width = max($width, 2);
        }
        return str_repeat($symbol, $width);
    }

    private static function semanticMonthSkeleton(array $fields, array $dateWidths, string $length, string $alignment, string $monthStyle): string
    {
        if (count($fields) === 1) {
            $symbol = 'L';
            $width = $monthStyle === 'auto' ? self::lengthStyleWidth($length) : self::dateFieldStyleWidth($monthStyle);
        } else {
            $symbol = array_key_exists('M', $dateWidths) ? 'M' : (array_key_exists('L', $dateWidths) ? 'L' : 'M');
            $width = $monthStyle === 'auto' ? ($dateWidths[$symbol] ?? self::lengthStyleWidth($length)) : self::dateFieldStyleWidth($monthStyle);
        }
        if ($alignment === 'column' && $width < 3) {
            $width = max($width, 2);
        }
        return str_repeat($symbol, $width);
    }

    private static function lengthStyleWidth(string $length): int
    {
        return self::isWideLength($length) ? 4 : ($length === 'medium' ? 3 : 1);
    }

    private static function isWideLength(string $length): bool
    {
        return $length === 'full' || $length === 'long';
    }

    private static function dateFieldStyleWidth(string $style): int
    {
        return match ($style) {
            'numeric' => 1,
            '2-digit' => 2,
            'short' => 3,
            'long' => 4,
            default => 5,
        };
    }

    private static function semanticDaySkeleton(array $dateWidths, string $alignment, string $dayStyle): string
    {
        $width = $dayStyle === 'auto' ? ($dateWidths['d'] ?? 1) : self::dateFieldStyleWidth($dayStyle);
        if ($alignment === 'column' && $width < 3) {
            $width = max($width, 2);
        }
        return str_repeat('d', $width);
    }

    private static function semanticWeekdaySkeleton(array $fields, string $length, string $weekdayStyle): string
    {
        if ($weekdayStyle === 'short') {
            return 'EEE';
        }
        if ($weekdayStyle === 'long') {
            return 'EEEE';
        }
        if ($weekdayStyle === 'narrow') {
            return 'EEEEE';
        }
        if (count($fields) === 1 && $length === 'short') {
            return 'EEEEE';
        }
        return self::isWideLength($length) ? 'EEEE' : 'EEE';
    }

    private static function semanticDayPeriodSkeleton(string $length, string $dayPeriodStyle): string
    {
        $style = $dayPeriodStyle === 'auto' ? $length : $dayPeriodStyle;
        return str_repeat('B', self::isWideLength($style) ? 4 : ($style === 'narrow' || ($dayPeriodStyle === 'auto' && $length === 'short') ? 5 : 1));
    }

    private static function hasSemanticTimeComponents(array $fields): bool
    {
        return in_array('hour', $fields, true) || in_array('minute', $fields, true) || in_array('second', $fields, true) || in_array('fractionalsecond', $fields, true) || in_array('millisecondsinday', $fields, true);
    }

    private static function semanticExplicitTimeSkeleton(array $fields, string $hourCycle, string $alignment, array $options): string
    {
        $hasHour = in_array('hour', $fields, true);
        $hasMinute = in_array('minute', $fields, true);
        $hasSecond = in_array('second', $fields, true);
        $hasFractionalSecond = in_array('fractionalsecond', $fields, true);
        $hasMillisecondsInDay = in_array('millisecondsinday', $fields, true);
        $skeleton = '';
        if ($hasHour) {
            $skeleton .= str_repeat(self::semanticHourSymbol($hourCycle), self::semanticNumericFieldWidth($options, 'hourstyle', $alignment === 'column' ? 2 : 1));
        }
        if ($hasMinute) {
            $skeleton .= str_repeat('m', self::semanticNumericFieldWidth($options, 'minutestyle', !$hasHour && !$hasSecond && $alignment === 'column' ? 2 : 1));
        }
        if ($hasSecond) {
            $skeleton .= str_repeat('s', self::semanticNumericFieldWidth($options, 'secondstyle', !$hasHour && !$hasMinute && $alignment === 'column' ? 2 : 1));
        }
        if ($hasFractionalSecond) {
            $skeleton .= str_repeat('S', self::semanticFractionalSecondWidth($options));
        }
        if ($hasMillisecondsInDay) {
            $skeleton .= str_repeat('A', $alignment === 'column' ? 8 : 1);
        }
        return $skeleton;
    }

    private static function semanticNumericFieldWidth(array $options, string $key, int $fallbackWidth): int
    {
        return match ($options[$key] ?? 'auto') {
            'auto' => $fallbackWidth,
            '2-digit' => 2,
            default => 1,
        };
    }

    private static function semanticFractionalSecondWidth(array $options): int
    {
        $width = filter_var($options['fractionalsecond'] ?? null, FILTER_VALIDATE_INT);
        if (!is_int($width) || $width < 1 || $width > 9) {
            throw MF2Error::badOption('Date/time semantic skeleton fractionalSecond must be an integer from 1 to 9.');
        }
        return $width;
    }

    private static function semanticTimeSkeleton(string $timePrecision, string $hourCycle, string $alignment, \DateTimeImmutable $value, array $options): string
    {
        $skeleton = str_repeat(self::semanticHourSymbol($hourCycle), $alignment === 'column' ? 2 : 1);
        if (in_array($timePrecision, ['minute', 'second', 'fractional-second'], true)) {
            $skeleton .= 'm';
        }
        if ($timePrecision === 'minute-optional' && (int) $value->format('i') !== 0) {
            $skeleton .= 'm';
        }
        if (in_array($timePrecision, ['second', 'fractional-second'], true)) {
            $skeleton .= 's';
        }
        if ($timePrecision === 'fractional-second') {
            $skeleton .= str_repeat('S', self::semanticFractionalSecondWidth($options));
        } elseif (array_key_exists('fractionalsecond', $options)) {
            throw MF2Error::badOption('fractionalSecond requires timePrecision=fractional-second.');
        }
        return $skeleton;
    }

    private static function semanticTimeStylePrecision(string $timeStyle, string $timePrecision): string
    {
        return match ($timeStyle) {
            'short' => 'minute',
            'medium', 'long', 'full' => 'second',
            default => $timePrecision,
        };
    }

    private static function semanticTimeStyleZoneStyle(string $timeStyle, string $zoneStyle): string
    {
        return self::semanticTimeStyleHasZone($timeStyle) ? 'specific' : $zoneStyle;
    }

    private static function semanticTimeStyleHasZone(?string $timeStyle): bool
    {
        return $timeStyle === 'long' || $timeStyle === 'full';
    }

    private static function semanticHourSymbol(string $hourCycle): string
    {
        return match ($hourCycle) {
            'h11' => 'K',
            'h12', 'clock12' => 'h',
            'h23', 'clock24' => 'H',
            'h24' => 'k',
            default => 'C',
        };
    }

    private static function semanticZoneSkeleton(string $zoneStyle, bool $standalone, string $length): string
    {
        $style = $zoneStyle === 'auto' ? 'generic' : $zoneStyle;
        return match ($style) {
            'specific' => $standalone && $length !== 'short' ? 'zzzz' : 'z',
            'location' => 'VVVV',
            'offset' => 'O',
            default => $standalone && $length !== 'short' ? 'vvvv' : 'v',
        };
    }

    private static function applyCHourFormat(array &$widths, array $localeData, ?string $hourCycle, int $width): void
    {
        if ($hourCycle !== null) {
            $hourSymbol = self::preferredHourSymbol($localeData, $hourCycle);
            self::setSkeletonWidth($widths, $hourSymbol, self::cHourWidth($width));
            if (self::isHour12Field($hourSymbol)) {
                self::setSkeletonWidth($widths, 'B', self::dayPeriodWidthForC($width));
            }
            return;
        }
        $tokens = preg_split('/\s+/', (string) ($localeData['allowedHourFormats'] ?? ''), -1, PREG_SPLIT_NO_EMPTY) ?: [];
        foreach ($tokens as $token) {
            if (!self::isCHourFormatToken($token)) {
                continue;
            }
            self::setSkeletonWidth($widths, $token[0], self::cHourWidth($width));
            if (strlen($token) > 1) {
                self::setSkeletonWidth($widths, $token[1], self::dayPeriodWidthForC($width));
            }
            return;
        }
        self::setSkeletonWidth($widths, self::preferredHourSymbol($localeData, $hourCycle), self::cHourWidth($width));
    }

    private static function isCHourFormatToken(string $token): bool
    {
        return preg_match('/^[hHkK][bB]?$/', $token) === 1;
    }

    private static function setSkeletonWidth(array &$widths, string $symbol, int $width): void
    {
        $widths[$symbol] = max($widths[$symbol] ?? 0, $width);
    }

    private static function normalizeSkeletonSymbol(string $symbol, array $localeData, ?string $hourCycle): string
    {
        if ($symbol === 'l') {
            return 'L';
        }
        if ($symbol === 'j' || $symbol === 'J') {
            return self::preferredHourSymbol($localeData, $hourCycle);
        }
        return $symbol;
    }

    private static function cHourWidth(int $width): int
    {
        return $width % 2 === 0 ? 2 : 1;
    }

    private static function dayPeriodWidthForC(int $width): int
    {
        if ($width >= 5) {
            return 5;
        }
        return $width >= 3 ? 4 : 1;
    }

    private static function shouldSuppressDayPeriod(string $skeleton): bool
    {
        return str_contains($skeleton, 'J')
            && !str_contains($skeleton, 'a')
            && !str_contains($skeleton, 'b')
            && !str_contains($skeleton, 'B')
            && !str_contains($skeleton, 'C');
    }

    private static function stripDayPeriodPatternFields(string $pattern): string
    {
        $chars = preg_split('//u', $pattern, -1, PREG_SPLIT_NO_EMPTY) ?: [];
        $output = '';
        $pendingWhitespace = '';
        for ($index = 0, $length = count($chars); $index < $length;) {
            $ch = $chars[$index];
            if ($ch === "'") {
                [, $nextIndex] = self::readQuotedPattern($chars, $index);
                $output .= $pendingWhitespace . implode('', array_slice($chars, $index, $nextIndex - $index));
                $pendingWhitespace = '';
                $index = $nextIndex;
            } elseif (self::isAsciiLetter($ch)) {
                $end = $index + 1;
                while ($end < $length && $chars[$end] === $ch) {
                    $end += 1;
                }
                if (self::isDayPeriodField($ch)) {
                    $pendingWhitespace = '';
                } else {
                    $output .= $pendingWhitespace . implode('', array_slice($chars, $index, $end - $index));
                    $pendingWhitespace = '';
                }
                $index = $end;
            } elseif (self::isPatternWhitespace($ch)) {
                $pendingWhitespace .= $ch;
                $index += 1;
            } else {
                $output .= $pendingWhitespace . $ch;
                $pendingWhitespace = '';
                $index += 1;
            }
        }
        return (string) preg_replace('/^[\s\x{00A0}\x{202F}]+|[\s\x{00A0}\x{202F}]+$/u', '', $output . $pendingWhitespace);
    }

    private static function isPatternWhitespace(string $value): bool
    {
        return $value === ' ' || $value === "\u{00A0}" || $value === "\u{202F}" || preg_match('/^\s$/u', $value) === 1;
    }

    private static function preferredHourSymbol(array $localeData, ?string $hourCycle): string
    {
        if ($hourCycle === 'h11') {
            return 'K';
        }
        if ($hourCycle === 'h12') {
            return 'h';
        }
        if ($hourCycle === 'h23') {
            return 'H';
        }
        if ($hourCycle === 'h24') {
            return 'k';
        }
        $shortTime = (string) ($localeData['timeFormats']['short'] ?? '');
        if (str_contains($shortTime, 'H')) {
            return 'H';
        }
        if (str_contains($shortTime, 'k')) {
            return 'k';
        }
        if (str_contains($shortTime, 'K')) {
            return 'K';
        }
        return 'h';
    }

    private static function skeletonFieldSet(string $skeleton): string
    {
        $normalized = '';
        foreach (self::skeletonWidths($skeleton) as $symbol => $_) {
            $normalized .= self::fieldSetSymbol((string) $symbol);
        }
        $output = '';
        foreach (str_split(self::SKELETON_FIELD_ORDER) as $symbol) {
            if (str_contains($normalized, $symbol)) {
                $output .= $symbol;
            }
        }
        return $output;
    }

    private static function fieldSetSymbol(string $symbol): string
    {
        if (self::isYearField($symbol)) {
            return 'y';
        }
        if (self::isHourField($symbol)) {
            return 'J';
        }
        if (self::isMonthField($symbol)) {
            return 'M';
        }
        if (self::isQuarterField($symbol)) {
            return 'Q';
        }
        if (self::isDayPeriodField($symbol)) {
            return 'B';
        }
        if (self::isWeekdayField($symbol)) {
            return 'E';
        }
        if (self::isTimeZoneField($symbol)) {
            return 'v';
        }
        return $symbol;
    }

    private static function skeletonDistance(string $requested, string $candidate): int
    {
        $requestedWidths = self::skeletonWidths($requested);
        $candidateWidths = self::skeletonWidths($candidate);
        $distance = 0;
        foreach ($requestedWidths as $symbol => $requestedWidth) {
            $candidateSymbol = self::candidateSymbolForRequested((string) $symbol, $candidateWidths);
            $candidateWidth = $candidateSymbol === null ? 0 : $candidateWidths[$candidateSymbol];
            $distance += abs($requestedWidth - $candidateWidth);
            if (self::isTextWidth($requestedWidth) !== self::isTextWidth($candidateWidth)) {
                $distance += 8;
            }
            $distance += self::hourFieldDistance((string) $symbol, $candidateSymbol);
        }
        return $distance;
    }

    private static function skeletonWidths(string $skeleton): array
    {
        $chars = preg_split('//u', $skeleton, -1, PREG_SPLIT_NO_EMPTY) ?: [];
        $widths = [];
        for ($index = 0, $length = count($chars); $index < $length;) {
            $symbol = $chars[$index];
            $end = $index + 1;
            while ($end < $length && $chars[$end] === $symbol) {
                $end += 1;
            }
            $widths[$symbol] = max($widths[$symbol] ?? 0, $end - $index);
            $index = $end;
        }
        return $widths;
    }

    private static function isTextWidth(int $width): bool
    {
        return $width >= 3;
    }

    private static function isHourField(?string $symbol): bool
    {
        return $symbol !== null && str_contains(self::SKELETON_HOUR_FIELDS, $symbol);
    }

    private static function isYearField(?string $symbol): bool
    {
        return $symbol === 'y' || $symbol === 'u' || $symbol === 'r';
    }

    private static function isWeekdayField(string $symbol): bool
    {
        return $symbol === 'E' || $symbol === 'e' || $symbol === 'c';
    }

    private static function isMonthField(string $symbol): bool
    {
        return $symbol === 'M' || $symbol === 'L';
    }

    private static function isQuarterField(string $symbol): bool
    {
        return $symbol === 'Q' || $symbol === 'q';
    }

    private static function isDayPeriodField(string $symbol): bool
    {
        return $symbol === 'a' || $symbol === 'b' || $symbol === 'B';
    }

    private static function isSyntheticNumericField(string $symbol): bool
    {
        return $symbol === 'D' || $symbol === 'F' || $symbol === 'g' || $symbol === 'm' || $symbol === 's' || $symbol === 'A';
    }

    private static function isTimeZoneField(string $symbol): bool
    {
        return str_contains('zZOvVXx', $symbol);
    }

    private static function candidateSymbolForRequested(string $symbol, array $candidateWidths): ?string
    {
        if (array_key_exists($symbol, $candidateWidths)) {
            return $symbol;
        }
        if (self::isYearField($symbol)) {
            foreach (['y', 'u', 'r'] as $yearSymbol) {
                if (array_key_exists($yearSymbol, $candidateWidths)) {
                    return $yearSymbol;
                }
            }
        }
        if (self::isHourField($symbol)) {
            foreach (str_split(self::SKELETON_HOUR_FIELDS) as $hourSymbol) {
                if (array_key_exists($hourSymbol, $candidateWidths)) {
                    return $hourSymbol;
                }
            }
            return null;
        }
        if (self::isQuarterField($symbol)) {
            foreach (['Q', 'q'] as $quarterSymbol) {
                if (array_key_exists($quarterSymbol, $candidateWidths)) {
                    return $quarterSymbol;
                }
            }
        }
        if (self::isMonthField($symbol)) {
            foreach (['M', 'L'] as $monthSymbol) {
                if (array_key_exists($monthSymbol, $candidateWidths)) {
                    return $monthSymbol;
                }
            }
        }
        if (self::isDayPeriodField($symbol)) {
            foreach (['B', 'b', 'a'] as $dayPeriodSymbol) {
                if (array_key_exists($dayPeriodSymbol, $candidateWidths)) {
                    return $dayPeriodSymbol;
                }
            }
        }
        if (self::isWeekdayField($symbol)) {
            foreach (['E', 'e', 'c'] as $weekdaySymbol) {
                if (array_key_exists($weekdaySymbol, $candidateWidths)) {
                    return $weekdaySymbol;
                }
            }
        }
        if (self::isTimeZoneField($symbol)) {
            foreach (['v', 'z', 'O', 'Z', 'X', 'x', 'V'] as $timeZoneSymbol) {
                if (array_key_exists($timeZoneSymbol, $candidateWidths)) {
                    return $timeZoneSymbol;
                }
            }
        }
        return null;
    }

    private static function hourFieldDistance(string $requestedSymbol, ?string $candidateSymbol): int
    {
        if (
            $candidateSymbol === null
            || $requestedSymbol === $candidateSymbol
            || !self::isHourField($requestedSymbol)
            || !self::isHourField($candidateSymbol)
        ) {
            return 0;
        }
        return self::isHour12Field($requestedSymbol) === self::isHour12Field($candidateSymbol) ? 1 : 4;
    }

    private static function isHour12Field(string $symbol): bool
    {
        return $symbol === 'h' || $symbol === 'K';
    }

    private static function requestedSymbolForPattern(string $symbol, array $requestedWidths, array $candidateWidths): string
    {
        if (self::isYearField($symbol)) {
            return self::candidateSymbolForRequested($symbol, $candidateWidths) === null
                ? $symbol
                : self::candidateSymbolForRequested($symbol, $requestedWidths) ?? $symbol;
        }
        if (self::isWeekdayField($symbol)) {
            return self::candidateSymbolForRequested($symbol, $candidateWidths) === null
                ? $symbol
                : self::requestedWeekdaySymbolForPattern($symbol, $requestedWidths);
        }
        if (self::isDayPeriodField($symbol)) {
            return self::candidateSymbolForRequested($symbol, $candidateWidths) === null
                ? $symbol
                : self::requestedDayPeriodSymbolForPattern($symbol, $requestedWidths);
        }
        if (self::isTimeZoneField($symbol)) {
            return self::candidateSymbolForRequested($symbol, $candidateWidths) === null
                ? $symbol
                : self::requestedTimeZoneSymbolForPattern($symbol, $requestedWidths);
        }
        if ((!self::isYearField($symbol) && !self::isHourField($symbol) && !self::isMonthField($symbol) && !self::isQuarterField($symbol) && !self::isDayPeriodField($symbol) && !self::isTimeZoneField($symbol)) || self::candidateSymbolForRequested($symbol, $candidateWidths) === null) {
            return $symbol;
        }
        return self::candidateSymbolForRequested($symbol, $requestedWidths) ?? $symbol;
    }

    private static function requestedWeekdaySymbolForPattern(string $symbol, array $requestedWidths): string
    {
        if (array_key_exists('c', $requestedWidths)) {
            return 'c';
        }
        if (array_key_exists('e', $requestedWidths)) {
            return 'e';
        }
        if (array_key_exists('E', $requestedWidths)) {
            return 'E';
        }
        return $symbol;
    }

    private static function requestedDayPeriodSymbolForPattern(string $symbol, array $requestedWidths): string
    {
        if (array_key_exists('a', $requestedWidths)) {
            return 'a';
        }
        if (array_key_exists('b', $requestedWidths)) {
            return 'b';
        }
        if (array_key_exists('B', $requestedWidths)) {
            return 'B';
        }
        return $symbol;
    }

    private static function requestedTimeZoneSymbolForPattern(string $symbol, array $requestedWidths): string
    {
        foreach (['z', 'Z', 'O', 'v', 'V', 'X', 'x'] as $timeZoneSymbol) {
            if (array_key_exists($timeZoneSymbol, $requestedWidths)) {
                return $timeZoneSymbol;
            }
        }
        return $symbol;
    }

    private static function widthForPatternSymbol(string $symbol, array $widths): ?int
    {
        if (array_key_exists($symbol, $widths)) {
            return $widths[$symbol];
        }
        if (self::isYearField($symbol)) {
            foreach (['y', 'u', 'r'] as $yearSymbol) {
                if (array_key_exists($yearSymbol, $widths)) {
                    return $widths[$yearSymbol];
                }
            }
        }
        if (self::isWeekdayField($symbol)) {
            foreach (['E', 'e', 'c'] as $weekdaySymbol) {
                if (array_key_exists($weekdaySymbol, $widths)) {
                    return $widths[$weekdaySymbol];
                }
            }
        }
        if (self::isMonthField($symbol)) {
            foreach (['M', 'L'] as $monthSymbol) {
                if (array_key_exists($monthSymbol, $widths)) {
                    return $widths[$monthSymbol];
                }
            }
        }
        if (self::isDayPeriodField($symbol)) {
            foreach (['B', 'b', 'a'] as $dayPeriodSymbol) {
                if (array_key_exists($dayPeriodSymbol, $widths)) {
                    return $widths[$dayPeriodSymbol];
                }
            }
        }
        if (self::isQuarterField($symbol)) {
            foreach (['Q', 'q'] as $quarterSymbol) {
                if (array_key_exists($quarterSymbol, $widths)) {
                    return $widths[$quarterSymbol];
                }
            }
        }
        if (self::isTimeZoneField($symbol)) {
            foreach (['z', 'Z', 'O', 'v', 'V', 'X', 'x'] as $timeZoneSymbol) {
                if (array_key_exists($timeZoneSymbol, $widths)) {
                    return $widths[$timeZoneSymbol];
                }
            }
        }
        return null;
    }

    private static function adjustPatternWidths(string $pattern, string $requestedSkeleton, string $candidateSkeleton): string
    {
        $requestedWidths = self::skeletonWidths($requestedSkeleton);
        $candidateWidths = self::skeletonWidths($candidateSkeleton);
        $chars = preg_split('//u', $pattern, -1, PREG_SPLIT_NO_EMPTY) ?: [];
        $output = '';
        $inQuote = false;
        for ($index = 0, $length = count($chars); $index < $length;) {
            $ch = $chars[$index];
            if ($ch === "'") {
                $output .= $ch;
                if (($chars[$index + 1] ?? null) === "'") {
                    $output .= "'";
                    $index += 2;
                } else {
                    $inQuote = !$inQuote;
                    $index += 1;
                }
            } elseif (!$inQuote && self::isAsciiLetter($ch)) {
                $end = $index + 1;
                while ($end < $length && $chars[$end] === $ch) {
                    $end += 1;
                }
                $patternWidth = $end - $index;
                $width = $patternWidth;
                $requestedSymbol = self::requestedSymbolForPattern($ch, $requestedWidths, $candidateWidths);
                $requestedWidth = self::widthForPatternSymbol($ch, $requestedWidths);
                $candidateWidth = self::widthForPatternSymbol($ch, $candidateWidths);
                if (
                    $requestedWidth !== null
                    && $candidateWidth !== null
                    && self::shouldAdjustPatternWidth($requestedSymbol, $requestedWidth, $candidateWidth, $patternWidth)
                ) {
                    $width = $requestedWidth;
                }
                $output .= str_repeat($requestedSymbol, $width);
                $index = $end;
            } else {
                $output .= $ch;
                $index += 1;
            }
        }
        return $output;
    }

    private static function shouldAdjustPatternWidth(string $symbol, int $requestedWidth, int $candidateWidth, int $patternWidth): bool
    {
        if (($symbol === 'e' || $symbol === 'c') && $patternWidth >= 3 && $requestedWidth <= 2) {
            return true;
        }
        if (self::isWeekdayField($symbol) && $patternWidth >= 3 && $requestedWidth >= 4) {
            return true;
        }
        return $patternWidth === $candidateWidth;
    }

    private static function splitDateTimeSkeleton(string $skeleton): array
    {
        $dateSkeleton = '';
        $timeSkeleton = '';
        foreach (preg_split('//u', $skeleton, -1, PREG_SPLIT_NO_EMPTY) ?: [] as $symbol) {
            if (str_contains(self::SKELETON_TIME_FIELDS, $symbol)) {
                $timeSkeleton .= $symbol;
            } else {
                $dateSkeleton .= $symbol;
            }
        }
        return [$dateSkeleton, $timeSkeleton];
    }

    private static function formatPattern(string $pattern, \DateTimeImmutable $value, array $localeData): string
    {
        $chars = preg_split('//u', $pattern, -1, PREG_SPLIT_NO_EMPTY) ?: [];
        $output = '';
        for ($index = 0, $length = count($chars); $index < $length;) {
            $ch = $chars[$index];
            if ($ch === "'") {
                [$quoted, $index] = self::readQuotedPattern($chars, $index);
                $output .= $quoted;
            } elseif (self::isAsciiLetter($ch)) {
                $end = $index + 1;
                while ($end < $length && $chars[$end] === $ch) {
                    $end += 1;
                }
                $output .= self::formatField($ch, $end - $index, $value, $localeData);
                $index = $end;
            } else {
                $output .= $ch;
                $index += 1;
            }
        }
        return $output;
    }

    private static function readQuotedPattern(array $pattern, int $start): array
    {
        if (($pattern[$start + 1] ?? null) === "'") {
            return ["'", $start + 2];
        }
        $output = '';
        $index = $start + 1;
        while ($index < count($pattern)) {
            if ($pattern[$index] === "'") {
                if (($pattern[$index + 1] ?? null) === "'") {
                    $output .= "'";
                    $index += 2;
                } else {
                    return [$output, $index + 1];
                }
            } else {
                $output .= $pattern[$index];
                $index += 1;
            }
        }
        return [$output, $index];
    }

    private static function formatField(string $symbol, int $count, \DateTimeImmutable $value, array $localeData): string
    {
        return match ($symbol) {
            'G' => self::eraName($value, $localeData, $count),
            'y' => self::yearValue($value, $localeData, $count),
            'u' => self::extendedYearValue($value, $localeData, $count),
            'r' => self::extendedYearValue($value, $localeData, $count),
            'Y' => self::weekYearValue($value, $localeData, $count),
            'Q', 'q' => self::quarterValue($value, $localeData, $count, $symbol === 'q'),
            'M', 'L' => self::monthValue($value, $localeData, $count, $symbol === 'L'),
            'd' => self::integerValue((int) $value->format('j'), $localeData, $count),
            'D' => self::integerValue(self::dayOfYear($value), $localeData, $count),
            'F' => self::integerValue(self::dayOfWeekInMonth($value), $localeData, $count),
            'g' => self::integerValue(self::modifiedJulianDay($value), $localeData, $count),
            'w' => self::integerValue(self::weekOfYear($value, $localeData), $localeData, $count),
            'W' => self::integerValue(self::weekOfMonth($value, $localeData), $localeData, $count),
            'E' => self::weekdayName($value, $localeData, $count),
            'e' => self::localWeekdayValue($value, $localeData, $count, false),
            'c' => self::localWeekdayValue($value, $localeData, $count, true),
            'a', 'b', 'B' => self::dayPeriodName($value, $localeData, $count, $symbol),
            'H' => self::integerValue((int) $value->format('G'), $localeData, $count),
            'k' => self::integerValue((int) $value->format('G') === 0 ? 24 : (int) $value->format('G'), $localeData, $count),
            'h' => self::integerValue(self::hour12($value), $localeData, $count),
            'K' => self::integerValue((int) $value->format('G') % 12, $localeData, $count),
            'm' => self::integerValue((int) $value->format('i'), $localeData, $count),
            's' => self::integerValue((int) $value->format('s'), $localeData, $count),
            'S' => self::fractionValue($value, $localeData, $count),
            'A' => self::integerValue(self::millisecondsInDay($value), $localeData, $count),
            'z', 'Z', 'O', 'v', 'V', 'X', 'x' => self::timeZoneValue($symbol, $count, $value, $localeData),
            default => throw MF2Error::badOption("Unsupported CLDR date/time pattern field: {$symbol}."),
        };
    }

    private static function timeZoneValue(string $symbol, int $count, \DateTimeImmutable $value, array $localeData): string
    {
        $names = $localeData['timeZoneNames'] ?? [];
        $offsetMinutes = intdiv($value->getOffset(), 60);
        if ($offsetMinutes !== 0) {
            return match ($symbol) {
                'X' => self::isoOffset($offsetMinutes, $count, true),
                'x' => self::isoOffset($offsetMinutes, $count, false),
                'V' => match ($count) {
                    1 => 'unk',
                    2 => self::fixedOffsetGmtId($offsetMinutes, $localeData),
                    3 => 'Unknown Location',
                    default => self::localizedGmtOffset($names, $offsetMinutes, $count, $localeData),
                },
                'Z' => $count <= 3
                    ? self::basicOffset($offsetMinutes)
                    : ($count === 5
                        ? self::isoOffset($offsetMinutes, 3, true)
                        : self::localizedGmtOffset($names, $offsetMinutes, $count, $localeData)),
                default => self::localizedGmtOffset($names, $offsetMinutes, $count, $localeData),
            };
        }
        return match ($symbol) {
            'z' => $count >= 4
                ? (string) ($names['utcLong'] ?? $names['utcShort'] ?? self::UTC)
                : (string) ($names['utcShort'] ?? self::UTC),
            'O', 'v' => self::localizedGmtZero($names),
            'V' => self::localizedGmtZero($names),
            'Z' => $count <= 3 ? '+0000' : ($count === 5 ? 'Z' : self::localizedGmtZero($names)),
            'X' => 'Z',
            'x' => $count === 1 ? '+00' : ($count === 2 || $count === 4 ? '+0000' : '+00:00'),
            default => self::UTC,
        };
    }

    private static function localizedGmtZero(array $names): string
    {
        return (string) ($names['gmtZeroFormat'] ?? str_replace('{0}', '', (string) ($names['gmtFormat'] ?? 'GMT{0}')));
    }

    private static function localizedGmtOffset(array $names, int $offsetMinutes, int $count, array $localeData): string
    {
        $formatted = $count >= 4 ? self::extendedOffset($offsetMinutes, true) : self::shortOffset($offsetMinutes);
        return str_replace(
            '{0}',
            self::localizeDigits($formatted, $localeData['numberingSystemDigits'] ?? null),
            (string) ($names['gmtFormat'] ?? 'GMT{0}'),
        );
    }

    private static function fixedOffsetGmtId(int $offsetMinutes, array $localeData): string
    {
        return 'GMT' . self::localizeDigits(self::extendedOffset($offsetMinutes, true), $localeData['numberingSystemDigits'] ?? null);
    }

    private static function isoOffset(int $offsetMinutes, int $count, bool $useZeroZ): string
    {
        if ($offsetMinutes === 0 && $useZeroZ) {
            return 'Z';
        }
        if ($count === 1) {
            return self::shortIsoOffset($offsetMinutes);
        }
        if ($count === 2 || $count === 4) {
            return self::basicOffset($offsetMinutes);
        }
        return self::extendedOffset($offsetMinutes, true);
    }

    private static function shortIsoOffset(int $offsetMinutes): string
    {
        [$sign, $hours, $minutes] = self::offsetParts($offsetMinutes);
        return $minutes === 0 ? sprintf('%s%02d', $sign, $hours) : sprintf('%s%02d%02d', $sign, $hours, $minutes);
    }

    private static function shortOffset(int $offsetMinutes): string
    {
        [$sign, $hours, $minutes] = self::offsetParts($offsetMinutes);
        return $minutes === 0 ? sprintf('%s%d', $sign, $hours) : sprintf('%s%d:%02d', $sign, $hours, $minutes);
    }

    private static function basicOffset(int $offsetMinutes): string
    {
        [$sign, $hours, $minutes] = self::offsetParts($offsetMinutes);
        return sprintf('%s%02d%02d', $sign, $hours, $minutes);
    }

    private static function extendedOffset(int $offsetMinutes, bool $paddedHour): string
    {
        [$sign, $hours, $minutes] = self::offsetParts($offsetMinutes);
        return $paddedHour ? sprintf('%s%02d:%02d', $sign, $hours, $minutes) : sprintf('%s%d:%02d', $sign, $hours, $minutes);
    }

    private static function offsetParts(int $offsetMinutes): array
    {
        $sign = $offsetMinutes < 0 ? '-' : '+';
        $absolute = abs($offsetMinutes);
        return [$sign, intdiv($absolute, 60), $absolute % 60];
    }

    private static function eraName(\DateTimeImmutable $value, array $localeData, int $count): string
    {
        $era = (int) $value->format('Y') <= 0 ? '0' : '1';
        return self::nameByWidth($localeData['eras'], self::widthForText($count), $era);
    }

    private static function yearValue(\DateTimeImmutable $value, array $localeData, int $count): string
    {
        $year = (int) $value->format('Y');
        $yearOfEra = $year <= 0 ? 1 - $year : $year;
        if ($count === 2) {
            return self::integerText($yearOfEra % 100, $localeData, 2);
        }
        return self::localizeDigits((string) $yearOfEra, $localeData['numberingSystemDigits'] ?? null);
    }

    private static function extendedYearValue(\DateTimeImmutable $value, array $localeData, int $count): string
    {
        return self::integerValue((int) $value->format('Y'), $localeData, $count);
    }

    private static function weekYearValue(\DateTimeImmutable $value, array $localeData, int $count): string
    {
        [$year] = self::weekYearInfo($value, $localeData);
        if ($count === 2) {
            return self::integerText(self::modulo($year, 100), $localeData, 2);
        }
        return self::localizeDigits((string) $year, $localeData['numberingSystemDigits'] ?? null);
    }

    private static function dayOfYear(\DateTimeImmutable $value): int
    {
        return (int) $value->format('z') + 1;
    }

    private static function dayOfWeekInMonth(\DateTimeImmutable $value): int
    {
        return intdiv((int) $value->format('j') - 1, 7) + 1;
    }

    private static function millisecondsInDay(\DateTimeImmutable $value): int
    {
        return ((((int) $value->format('G') * 60) + (int) $value->format('i')) * 60 + (int) $value->format('s')) * 1000
            + (int) $value->format('v');
    }

    private static function modifiedJulianDay(\DateTimeImmutable $value): int
    {
        return self::ordinalDay((int) $value->format('Y'), (int) $value->format('n'), (int) $value->format('j'))
            - self::ordinalDay(1858, 11, 17);
    }

    private static function weekOfYear(\DateTimeImmutable $value, array $localeData): int
    {
        [, $week] = self::weekYearInfo($value, $localeData);
        return $week;
    }

    private static function weekYearInfo(\DateTimeImmutable $value, array $localeData): array
    {
        $year = (int) $value->format('Y');
        $ordinal = self::ordinalDay($year, (int) $value->format('n'), (int) $value->format('j'));
        $currentStart = self::firstWeekStartOfYear($year, $localeData);
        if ($ordinal < $currentStart) {
            $previousYear = $year - 1;
            $previousStart = self::firstWeekStartOfYear($previousYear, $localeData);
            return [$previousYear, self::floorDiv($ordinal - $previousStart, 7) + 1];
        }

        $nextStart = self::firstWeekStartOfYear($year + 1, $localeData);
        if ($ordinal >= $nextStart) {
            return [$year + 1, self::floorDiv($ordinal - $nextStart, 7) + 1];
        }

        return [$year, self::floorDiv($ordinal - $currentStart, 7) + 1];
    }

    private static function weekOfMonth(\DateTimeImmutable $value, array $localeData): int
    {
        $year = (int) $value->format('Y');
        $month = (int) $value->format('n');
        $ordinal = self::ordinalDay($year, $month, (int) $value->format('j'));
        $firstStart = self::firstWeekStart(
            self::ordinalDay($year, $month, 1),
            (int) ($localeData['firstDayOfWeek'] ?? 1),
            (int) ($localeData['minDaysInFirstWeek'] ?? 1),
        );
        return self::floorDiv($ordinal - $firstStart, 7) + 1;
    }

    private static function firstWeekStartOfYear(int $year, array $localeData): int
    {
        return self::firstWeekStart(
            self::ordinalDay($year, 1, 1),
            (int) ($localeData['firstDayOfWeek'] ?? 1),
            (int) ($localeData['minDaysInFirstWeek'] ?? 1),
        );
    }

    private static function firstWeekStart(int $periodStart, int $firstDay, int $minDays): int
    {
        $weekStart = self::startOfWeek($periodStart, $firstDay);
        return 7 - ($periodStart - $weekStart) >= $minDays ? $weekStart : $weekStart + 7;
    }

    private static function startOfWeek(int $ordinal, int $firstDay): int
    {
        return $ordinal - self::modulo(self::dayOfWeek($ordinal) - $firstDay, 7);
    }

    private static function dayOfWeek(int $ordinal): int
    {
        return self::modulo($ordinal, 7);
    }

    private static function ordinalDay(int $year, int $month, int $day): int
    {
        return self::daysBeforeYear($year) + self::daysBeforeMonth($year, $month) + $day;
    }

    private static function daysBeforeYear(int $year): int
    {
        $previous = $year - 1;
        return 365 * $previous + intdiv($previous, 4) - intdiv($previous, 100) + intdiv($previous, 400);
    }

    private static function daysBeforeMonth(int $year, int $month): int
    {
        $offsets = [0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334];
        $days = $offsets[$month - 1];
        if ($month > 2 && self::isLeapYear($year)) {
            $days += 1;
        }
        return $days;
    }

    private static function isLeapYear(int $year): bool
    {
        return ($year % 4 === 0 && $year % 100 !== 0) || $year % 400 === 0;
    }

    private static function modulo(int $value, int $divisor): int
    {
        $remainder = $value % $divisor;
        return $remainder < 0 ? $remainder + $divisor : $remainder;
    }

    private static function floorDiv(int $value, int $divisor): int
    {
        $quotient = intdiv($value, $divisor);
        $remainder = $value % $divisor;
        return $remainder !== 0 && (($remainder < 0) !== ($divisor < 0)) ? $quotient - 1 : $quotient;
    }

    private static function monthValue(\DateTimeImmutable $value, array $localeData, int $count, bool $standAlone): string
    {
        $month = (int) $value->format('n');
        if ($count <= 2) {
            return self::integerValue($month, $localeData, $count);
        }
        return self::contextualName(
            $localeData['months'],
            $standAlone ? 'stand-alone' : 'format',
            self::widthForText($count),
            (string) $month,
        );
    }

    private static function quarterValue(\DateTimeImmutable $value, array $localeData, int $count, bool $standAlone): string
    {
        $quarter = intdiv((int) $value->format('n') - 1, 3) + 1;
        if ($count <= 2) {
            return self::integerValue($quarter, $localeData, $count);
        }
        return self::contextualName(
            $localeData['quarters'],
            $standAlone ? 'stand-alone' : 'format',
            self::widthForText($count),
            (string) $quarter,
        );
    }

    private static function weekdayName(\DateTimeImmutable $value, array $localeData, int $count): string
    {
        return self::contextualName(
            $localeData['weekdays'],
            'format',
            self::widthForWeekday($count),
            self::WEEKDAY_KEYS[(int) $value->format('w')],
        );
    }

    private static function localWeekdayValue(\DateTimeImmutable $value, array $localeData, int $count, bool $standAlone): string
    {
        $day = (int) $value->format('w');
        if ($count <= 2) {
            $localDay = self::modulo($day - (int) ($localeData['firstDayOfWeek'] ?? 1), 7) + 1;
            return self::integerValue($localDay, $localeData, $count);
        }
        return self::contextualName(
            $localeData['weekdays'],
            $standAlone ? 'stand-alone' : 'format',
            self::widthForWeekday($count),
            self::WEEKDAY_KEYS[$day],
        );
    }

    private static function dayPeriodName(\DateTimeImmutable $value, array $localeData, int $count, string $symbol): string
    {
        return self::contextualName(
            $localeData['dayPeriods'],
            'format',
            self::widthForDayPeriod($count),
            self::dayPeriodKey($value, $localeData, $symbol),
        );
    }

    private static function dayPeriodKey(\DateTimeImmutable $value, array $localeData, string $symbol): string
    {
        $fallback = (int) $value->format('G') < 12 ? 'am' : 'pm';
        if ($symbol === 'a') {
            return $fallback;
        }
        if ($symbol === 'b') {
            return self::selectDayPeriodRule($value, (string) ($localeData['dayPeriodRules'] ?? ''), true) ?? $fallback;
        }
        return self::selectDayPeriodRule($value, (string) ($localeData['dayPeriodRules'] ?? ''), false) ?? $fallback;
    }

    private static function selectDayPeriodRule(\DateTimeImmutable $value, string $encodedRules, bool $exactOnly): ?string
    {
        if ($encodedRules === '') {
            return null;
        }
        $minute = ((int) $value->format('G')) * 60 + (int) $value->format('i');
        $exactMinute = ((int) $value->format('s') === 0 && (int) $value->format('u') === 0) ? $minute : -1;
        $rangeMatch = null;
        foreach (explode(';', $encodedRules) as $rawRule) {
            [$period, $span] = array_pad(explode('=', $rawRule, 2), 2, null);
            if ($period === null || $span === null) {
                continue;
            }
            if (!str_contains($span, '-')) {
                if ((int) $span === $exactMinute) {
                    return $period;
                }
                continue;
            }
            if (!$exactOnly) {
                [$start, $end] = array_map('intval', explode('-', $span, 2));
                if ($rangeMatch === null && self::minuteInDayPeriodRange($minute, $start, $end)) {
                    $rangeMatch = $period;
                }
            }
        }
        return $exactOnly ? null : $rangeMatch;
    }

    private static function minuteInDayPeriodRange(int $minute, int $start, int $end): bool
    {
        if ($start <= $end) {
            return $minute >= $start && $minute < $end;
        }
        return $minute >= $start || $minute < $end;
    }

    private static function hour12(\DateTimeImmutable $value): int
    {
        $hour = (int) $value->format('G') % 12;
        return $hour === 0 ? 12 : $hour;
    }

    private static function fractionValue(\DateTimeImmutable $value, array $localeData, int $count): string
    {
        $milliseconds = str_pad($value->format('v'), 3, '0', STR_PAD_LEFT);
        return self::localizeDigits(substr($milliseconds . '000000000', 0, $count), $localeData['numberingSystemDigits'] ?? null);
    }

    private static function integerValue(int $value, array $localeData, int $count): string
    {
        return self::integerText($value, $localeData, $count >= 2 ? $count : 0);
    }

    private static function integerText(int $value, array $localeData, int $minimumDigits): string
    {
        $text = str_pad((string) abs($value), $minimumDigits, '0', STR_PAD_LEFT);
        return self::localizeDigits($value < 0 ? "-{$text}" : $text, $localeData['numberingSystemDigits'] ?? null);
    }

    private static function contextualName(array $source, string $context, string $width, string $key): string
    {
        $contextData = $source[$context] ?? $source['format'] ?? $source['stand-alone'] ?? [];
        return self::nameByWidth($contextData, $width, $key);
    }

    private static function nameByWidth(array $source, string $width, string $key): string
    {
        foreach ([$width, 'abbreviated', 'wide', 'short', 'narrow'] as $candidate) {
            if (($source[$candidate][$key] ?? '') !== '') {
                return (string) $source[$candidate][$key];
            }
        }
        return $key;
    }

    private static function widthForText(int $count): string
    {
        return match ($count) {
            4 => 'wide',
            5 => 'narrow',
            default => 'abbreviated',
        };
    }

    private static function widthForWeekday(int $count): string
    {
        if ($count === 4) {
            return 'wide';
        }
        if ($count === 5) {
            return 'narrow';
        }
        if ($count >= 6) {
            return 'short';
        }
        return 'abbreviated';
    }

    private static function widthForDayPeriod(int $count): string
    {
        if ($count === 4) {
            return 'wide';
        }
        if ($count >= 5) {
            return 'narrow';
        }
        return 'abbreviated';
    }

    private static function styleOption(string $value, string $name): string
    {
        if (!in_array($value, self::STYLES, true)) {
            throw MF2Error::badOption("{$name} must be one of full, long, medium, short.");
        }
        return $value;
    }

    private static function timeStyleOption(array $options, string $fallback): string
    {
        $timeStyle = self::firstOption($options, ['timeStyle'], '');
        if ($timeStyle !== '') {
            return self::styleOption($timeStyle, 'timeStyle');
        }
        $timePrecision = self::firstOption($options, ['timePrecision'], '');
        if ($timePrecision !== '') {
            return self::timePrecisionStyleOption($timePrecision, 'timePrecision');
        }
        $precision = self::firstOption($options, ['precision'], '');
        if ($precision !== '') {
            return self::timePrecisionStyleOption($precision, 'precision');
        }
        return self::styleOption(self::firstOption($options, ['style'], $fallback), 'timeStyle');
    }

    private static function timePrecisionStyleOption(string $value, string $name): string
    {
        return $value === 'second' ? 'medium' : self::styleOption($value, $name);
    }

    private static function firstOption(array $options, array $names, string $fallback): string
    {
        foreach ($names as $name) {
            if (array_key_exists($name, $options) && $options[$name] !== null && $options[$name] !== '') {
                return self::stringOption($options[$name], $name);
            }
        }
        return $fallback;
    }

    private static function callStyle(array $call, string $optionName, string $legacyOptionName, string $fallback, bool $legacyTimePrecision): string
    {
        $absent = "\0mf2-absent-date-time-style";
        $shared = self::callOption($call, 'style', $absent);
        $legacy = self::callOption($call, $legacyOptionName, $absent);
        $value = self::callOption($call, $optionName, $absent);
        if ($value !== $absent) {
            return self::styleOption(self::stringOption($value, $optionName), $optionName);
        }
        if ($legacy !== $absent) {
            return $legacyTimePrecision
                ? self::timePrecisionStyleOption(self::stringOption($legacy, $legacyOptionName), $legacyOptionName)
                : self::styleOption(self::stringOption($legacy, $legacyOptionName), $legacyOptionName);
        }
        if ($shared !== $absent) {
            return self::styleOption(self::stringOption($shared, 'style'), 'style');
        }
        return self::styleOption($fallback, $optionName);
    }

    private static function callOption(array $call, string $name, mixed $fallback = null): mixed
    {
        $optionValue = $call['optionValue'] ?? static fn(string $name, mixed $fallback): mixed => $fallback;
        return $optionValue($name, $fallback);
    }

    private static function nonEmptyCallOption(array $call, string $name, mixed $fallback = null): mixed
    {
        $value = self::callOption($call, $name, $fallback);
        if ($value === '') {
            throw MF2Error::badOption("{$name} must not be empty.");
        }
        return $value;
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

    private static function isAsciiLetter(string $value): bool
    {
        return strlen($value) === 1 && (($value >= 'A' && $value <= 'Z') || ($value >= 'a' && $value <= 'z'));
    }

    private static function localizeDigits(string $value, mixed $digits): string
    {
        if (!is_string($digits) || $digits === '' || $digits === '0123456789') {
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
}
