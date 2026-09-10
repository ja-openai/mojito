<?php
declare(strict_types=1);
namespace Mojito\MessageFormat2\Internal;

function locale_is_ltr(string $locale): bool
{
    $subtags = explode('-', str_replace('_', '-', $locale));
    $language = strtolower(array_shift($subtags));
    if (!preg_match('/^[a-z]{2,8}$/D', $language)) return false;
    $script = $region = null;
    foreach ($subtags as $subtag) {
        if (strlen($subtag) === 1) break;
        if (!preg_match('/^[A-Za-z0-9]{2,8}$/D', $subtag)) return false;
        if ($script === null && preg_match('/^[A-Za-z]{4}$/D', $subtag)) {
            $script = ucfirst(strtolower($subtag));
        } elseif ($region === null && preg_match('/^(?:[A-Za-z]{2}|[0-9]{3})$/D', $subtag)) {
            $region = strtoupper($subtag);
        }
    }
    $contains = static fn(string $table, string $value): bool => str_contains($table, " $value ");
    if ($script !== null) return $contains(LocaleDirectionData::LTR_SCRIPTS, $script);
    if ($region !== null) {
        $key = "$language-$region";
        if ($contains(LocaleDirectionData::RTL_REGION_OVERRIDES, $key)) return false;
        if ($contains(LocaleDirectionData::LTR_REGION_OVERRIDES, $key)) return true;
    }
    return !($language === 'und' && $region === null) && $contains(LocaleDirectionData::LTR_LANGUAGES, $language);
}
