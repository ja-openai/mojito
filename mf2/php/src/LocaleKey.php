<?php

declare(strict_types=1);

namespace Mojito\MessageFormat2\Internal;

const MAX_LOCALE_KEY_LENGTH = 256;

function canonical_locale_key(?string $locale): string
{
    $value = (string) $locale;
    if (strlen($value) > MAX_LOCALE_KEY_LENGTH) {
        return '';
    }
    $parts = array_values(array_filter(explode('-', str_replace('_', '-', trim($value))), static fn($part) => $part !== ''));
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
    return feature_lookup_chain($locale, $parents);
}

function feature_lookup_chain(?string $locale, array $parents = []): array
{
    $output = [];
    append_feature_lookup_chain(canonical_locale_key($locale), $parents, $output);
    return $output;
}

function append_feature_lookup_chain(string $locale, array $parents, array &$output): void
{
    $current = $locale;
    while ($current !== '') {
        if (strlen($current) > MAX_LOCALE_KEY_LENGTH) {
            return;
        }
        if (in_array($current, $output, true)) {
            return;
        }
        $output[] = $current;
        $parent = $parents[$current] ?? null;
        if ($parent !== null) {
            append_feature_lookup_chain($parent, $parents, $output);
        }
        $current = structural_parent($current);
    }
}

function structural_parent(string $locale): string
{
    $index = strrpos($locale, '-');
    return $index === false ? '' : substr($locale, 0, $index);
}
