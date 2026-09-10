from ._locale_key import canonical_locale_key
from . import _locale_direction_data as data

_LTR_SCRIPTS = frozenset(data.LTR_SCRIPTS.split())
_RTL_SCRIPTS = frozenset(data.RTL_SCRIPTS.split())
_LTR_LANGUAGES = frozenset(data.LTR_LANGUAGES.split())
_RTL_LANGUAGES = frozenset(data.RTL_LANGUAGES.split())
_LTR_REGIONS = frozenset(data.LTR_REGION_OVERRIDES.split())
_RTL_REGIONS = frozenset(data.RTL_REGION_OVERRIDES.split())


def locale_is_ltr(locale: str) -> bool | None:
    try:
        parts = canonical_locale_key(locale).split("-")
    except (ValueError, TypeError):
        return None
    language, region = parts[0], None
    for part in parts[1:]:
        if len(part) == 4 and part.isalpha():
            return True if part in _LTR_SCRIPTS else False if part in _RTL_SCRIPTS else None
        if len(part) == 2 and part.isalpha() or len(part) == 3 and part.isdigit():
            region = part
    if region:
        key = f"{language}-{region}"
        if key in _LTR_REGIONS:
            return True
        if key in _RTL_REGIONS:
            return False
    if language == "und":
        return None
    return True if language in _LTR_LANGUAGES else False if language in _RTL_LANGUAGES else None
