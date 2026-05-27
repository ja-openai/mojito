<?php

declare(strict_types=1);

namespace MF2;

final class NumberOperands
{
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
            $this->n = abs($value);
            $this->i = abs($value);
            $this->v = 0;
            $this->w = 0;
            $this->f = 0;
            $this->t = 0;
            return;
        }
        $raw = trim(value_to_string($value));
        if (preg_match('/^[+-]?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/', $raw) !== 1) {
            throw new \RangeException("Unsupported plural operand value: {$raw}");
        }
        $n = abs((float) $raw);
        if (!is_finite($n)) {
            throw new \RangeException("Unsupported plural operand value: {$raw}");
        }
        $normalized = strtolower(preg_replace('/^[+-]+/', '', $raw) ?? $raw);
        $base = explode('e', $normalized, 2)[0];
        $fraction = str_contains($base, '.') ? explode('.', $base, 2)[1] : '';
        $trimmed = rtrim($fraction, '0');
        $this->n = $n;
        $this->i = (int) floor($n);
        $this->v = strlen($fraction);
        $this->w = strlen($trimmed);
        $this->f = $fraction === '' ? 0 : (int) $fraction;
        $this->t = $trimmed === '' ? 0 : (int) $trimmed;
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
