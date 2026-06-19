# Locale Grammar Profiles

Locale profiles declare which grammar features a locale adapter understands,
which term-form dimensions it supports, and which form/metadata fields are
required by each grammar function.

The core formatter should not hardcode language inventories. It should ask the
profile and adapter:

```text
function + options -> required term forms and metadata fields
```

Profiles in this directory are intentionally small but cover the current
fixture families:

- `fr-v1`: usage/number/count term forms, articles, plural/agreement, optional
  gender metadata.
- `de-v1`: gender and case-sensitive articles.
- `ru-v1`: case forms and animacy.
- `ar-v1`: person/gender/number verb agreement.
- `sw-v1`: noun class agreement.
- `ja-v1`: classifier counts.
- `ko-v1`: politeness-controlled verb forms.
- `cy-v1`: mutation.

Profiles also own compact flag layouts where a compact runtime representation is
defined.
