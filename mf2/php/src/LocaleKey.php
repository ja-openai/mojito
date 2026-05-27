<?php

declare(strict_types=1);

namespace MF2;

function canonical_locale_key(?string $locale): string
{
    $parts = array_values(array_filter(explode('-', str_replace('_', '-', trim((string) $locale))), static fn($part) => $part !== ''));
    $output = [];
    foreach ($parts as $index => $part) {
        $lower = strtolower($part);
        if ($lower === 'u' || $lower === 'x') {
            break;
        }
        if ($index === 0) {
            $output[] = $lower;
        } elseif (strlen($part) === 4 && preg_match('/^[a-zA-Z]+$/', $part) === 1) {
            $output[] = strtoupper($part[0]) . strtolower(substr($part, 1));
        } elseif ((strlen($part) === 2 && preg_match('/^[a-zA-Z]+$/', $part) === 1) || (strlen($part) === 3 && preg_match('/^\d+$/', $part) === 1)) {
            $output[] = strtoupper($part);
        } else {
            $output[] = $part;
        }
    }
    return implode('-', $output);
}

function locale_lookup_chain(?string $locale): array
{
    $key = canonical_locale_key($locale);
    if ($key === '') {
        return [];
    }
    $parts = explode('-', $key);
    $chain = [];
    for ($length = count($parts); $length >= 1; $length -= 1) {
        $chain[] = implode('-', array_slice($parts, 0, $length));
    }
    return $chain;
}

function plural_lookup_chain(?string $locale, array $parents = []): array
{
    $output = [];
    foreach (locale_lookup_chain($locale) as $candidate) {
        $output[] = $candidate;
        $parent = $parents[$candidate] ?? null;
        if ($parent !== null && !in_array($parent, $output, true)) {
            $output[] = $parent;
        }
    }
    return $output;
}
