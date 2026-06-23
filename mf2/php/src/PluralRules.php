<?php

declare(strict_types=1);

namespace Mojito\MessageFormat2\Internal;

final class NumberOperands
{
    private const MAX_OPERAND_LENGTH = 256;
    private const MAX_SAFE_PLURAL_INTEGER = 9_007_199_254_740_991;

    public float $n;
    public int $i;
    public int $v;
    public int $w;
    public int $f;
    public int $t;
    public int $e = 0;
    public int $c = 0;

    public function __construct(mixed $value)
    {
        if (is_int($value)) {
            if (abs($value) > self::MAX_SAFE_PLURAL_INTEGER) {
                throw new \RangeException('Unsupported plural operand value');
            }
            $this->n = abs($value);
            $this->i = abs($value);
            $this->v = 0;
            $this->w = 0;
            $this->f = 0;
            $this->t = 0;
            return;
        }
        $raw = trim(value_to_string($value));
        if (strlen($raw) > self::MAX_OPERAND_LENGTH) {
            throw new \RangeException('Unsupported plural operand value');
        }
        if (preg_match('/^[+-]?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/', $raw) !== 1) {
            throw new \RangeException("Unsupported plural operand value: {$raw}");
        }
        $n = abs((float) $raw);
        if (!is_finite($n)) {
            throw new \RangeException("Unsupported plural operand value: {$raw}");
        }
        if ($n > self::MAX_SAFE_PLURAL_INTEGER) {
            throw new \RangeException('Unsupported plural operand value');
        }
        $normalized = strtolower(preg_replace('/^[+-]+/', '', $raw) ?? $raw);
        $base = explode('e', $normalized, 2)[0];
        $fraction = str_contains($base, '.') ? explode('.', $base, 2)[1] : '';
        $trimmed = rtrim($fraction, '0');
        $this->n = $n;
        $this->i = (int) floor($n);
        $this->v = strlen($fraction);
        $this->w = strlen($trimmed);
        $this->f = self::parsePluralDigits($fraction);
        $this->t = self::parsePluralDigits($trimmed);
    }

    public function operand(string $name): float|int
    {
        return match ($name) {
            'n' => $this->n,
            'i' => $this->i,
            'v' => $this->v,
            'w' => $this->w,
            'f' => $this->f,
            't' => $this->t,
            'e' => $this->e,
            'c' => $this->c,
            default => 0,
        };
    }

    private static function parsePluralDigits(string $digits): int
    {
        if ($digits === '') {
            return 0;
        }
        $normalized = ltrim($digits, '0');
        if ($normalized === '') {
            return 0;
        }
        $max = (string) self::MAX_SAFE_PLURAL_INTEGER;
        if (strlen($normalized) > strlen($max) || (strlen($normalized) === strlen($max) && strcmp($normalized, $max) > 0)) {
            throw new \RangeException('Unsupported plural operand value');
        }
        return (int) $normalized;
    }
}

function select_cardinal(?string $locale, mixed $value): string
{
    $operands = $value instanceof NumberOperands ? $value : new NumberOperands($value);
    return generated_select_cardinal($locale, $operands);
}

function select_ordinal(?string $locale, mixed $value): string
{
    $operands = $value instanceof NumberOperands ? $value : new NumberOperands($value);
    return generated_select_ordinal($locale, $operands);
}
