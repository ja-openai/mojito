# ICU/CLDR Plural Policy

## Context

Mojito uses ICU4J plural keywords as the CLDR-facing plural form set for locales. Those
keywords drive locale plural-form setup, PO import completion, and statistics that compare
target locales with the six source plural forms.

ICU4J upgrades can change CLDR keyword sets even when gettext PO plural formulas stay the
same. In a trial bump from ICU4J 64.2 to 78.3:

- Catalan, Spanish, French, Italian, Portuguese (`pt`, `pt-BR`, `pt-PT`), and Sicilian gained
  CLDR `many`.
- Maltese gained CLDR `two`.
- Hebrew (`he`, legacy `iw`) dropped CLDR `many`.

Current gettext 0.26 still generates two-form PO formulas for French, Portuguese, Spanish,
Italian, Catalan, and Hebrew. That means blindly following new ICU keywords can create extra
Mojito plural forms that do not exist in standard generated PO files.

## Decision

Introduce `PluralRuleService` as the single wrapper for CLDR plural keyword lookup. ICU4J can be
upgraded behind this boundary while Mojito pins the keyword sets that changed between ICU4J 64.2
and 78.3. This keeps the dependency update separate from any product decision to expose new CLDR
forms in the database, PO import/export, or statistics.

## Tradeoffs

Following ICU exactly keeps Mojito aligned with current CLDR and avoids maintaining overrides.
The downside is that a dependency upgrade can silently change database plural-form setup,
PO import completion, exported file shape, and statistics.

Pinning old keyword sets for changed locales keeps behavior stable during a security/runtime
upgrade. The downside is that Mojito intentionally diverges from current CLDR until the product
decision is revisited.

Adding PO-specific copy semantics such as `other -> many` preserves internal CLDR completeness
while keeping gettext-compatible PO formulas. The downside is that it can look like a backfill
unless the import behavior is documented and tested carefully.

Changing gettext formulas to add new forms is technically possible because PO headers can carry
custom `nplurals` formulas, but it would diverge from gettext-generated defaults and force
translators/vendors to handle forms that may not appear in their normal PO tooling.

## Open Questions

- Should Mojito pin old CLDR keyword sets for locales where gettext still emits fewer PO forms?
- Should PO import synthesize missing CLDR forms from `other`, and only when the file lacks the
  form?
- Does Hebrew need a data migration or export compatibility path if Mojito moves from four
  CLDR-backed forms to ICU 78.3's three forms?
- Which tests should lock the policy: wrapper keyword tests, PO import/export tests, and/or
  locale plural-form generation tests?
