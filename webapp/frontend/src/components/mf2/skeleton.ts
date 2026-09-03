import type { MF2Message, MF2Pattern, MF2VariantKey } from '@mojito-mf2/core';

import { isOptionalCounterExpression, optionalCounterNames } from './optionalCounter';

type SkeletonIssue = { code: string; message: string; formLabel?: string; severity: 'error' };
const pluralCategories = new Set(['zero', 'one', 'two', 'few', 'many', 'other']);

// Compare parsed values, not source spelling: select=exact and select=|exact|
// have the same model. Property order in option/attribute maps is immaterial.
function signature(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(signature).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.entries(value)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([key, item]) => `${JSON.stringify(key)}:${signature(item)}`)
      .join(',')}}`;
  }
  return JSON.stringify(value) ?? '';
}

function pluralSelectors(model: MF2Message) {
  return new Set(
    model.declarations
      .filter(({ value }) => {
        const fn = value.function;
        if (!fn || !['number', 'integer', 'offset'].includes(fn.name)) return false;
        const select = fn.options?.select;
        return (
          !select || (select.type === 'literal' && ['plural', 'ordinal'].includes(select.value))
        );
      })
      .map(({ name }) => name),
  );
}

function patterns(model: MF2Message) {
  return model.type === 'select' ? model.variants : [{ keys: [], value: model.pattern }];
}

function label(keys: MF2VariantKey[], selectors: string[]) {
  return (
    keys
      .map((key, index) => `${selectors[index]}: ${key.type === '*' ? 'fallback' : key.value}`)
      .join(' / ') || 'message'
  );
}

function protectedParts(pattern: MF2Pattern, optionalCounters: Set<string>) {
  return pattern
    .filter(
      (part) => typeof part !== 'string' && !isOptionalCounterExpression(part, optionalCounters),
    )
    .map(signature)
    .sort();
}

function protectedPartsMatch(
  source: MF2Pattern,
  target: MF2Pattern,
  optionalCounters: Set<string>,
  selectorCounters: Set<string>,
) {
  const sourceParts = protectedParts(source, optionalCounters);
  const targetParts = protectedParts(target, optionalCounters);
  // A source singular form may spell out its count, while the same category in
  // another locale covers several quantities. Allow its bare selector counter
  // to be added once without making existing variable counters optional.
  for (const name of selectorCounters) {
    const counter = signature({ type: 'expression', arg: { type: 'variable', name } });
    if (sourceParts.includes(counter)) continue;
    const index = targetParts.indexOf(counter);
    if (index >= 0) targetParts.splice(index, 1);
  }
  return signature(sourceParts) === signature(targetParts);
}

function markupOrder(pattern: MF2Pattern) {
  return pattern
    .filter((part) => typeof part !== 'string' && part.type === 'markup')
    .map(signature);
}

export function mf2SkeletonDiagnostics(
  source: MF2Message,
  target: MF2Message,
  locale: string,
): SkeletonIssue[] {
  const issues: SkeletonIssue[] = [];
  if (signature(source.declarations) !== signature(target.declarations)) {
    issues.push({
      code: 'mf2-declarations-changed',
      severity: 'error',
      message:
        'MF2 structure is protected: preserve the source declarations, input variables, functions, options, and attributes.',
    });
  }

  const sourceSelectors = source.type === 'select' ? source.selectors.map(({ name }) => name) : [];
  const targetSelectors = target.type === 'select' ? target.selectors.map(({ name }) => name) : [];
  const counters = pluralSelectors(source);
  const canPromotePlural =
    source.type === 'message' && targetSelectors.every((name) => counters.has(name));
  if (signature(sourceSelectors) !== signature(targetSelectors) && !canPromotePlural) {
    issues.push({
      code: 'mf2-selectors-changed',
      severity: 'error',
      message: 'MF2 structure is protected: preserve the source selectors and their order.',
    });
    return issues;
  }

  const selectors = targetSelectors;
  const selectorCounters = new Set(selectors.filter((name) => counters.has(name)));
  const sourcePatterns = patterns(source);
  const targetPatterns = patterns(target);
  const projectKeys = (keys: MF2VariantKey[]) =>
    selectors.map((name, index) => {
      const key = keys[index] ?? { type: '*' as const };
      return counters.has(name) && key.type === 'literal' && pluralCategories.has(key.value)
        ? { type: '*' }
        : key;
    });
  // CLDR categories may change with the locale. Fixed status/gender/exact-number
  // branches must remain present, even when a catch-all could hide their removal.
  const sourceBranches = new Set(sourcePatterns.map(({ keys }) => signature(projectKeys(keys))));
  const targetBranches = new Set(targetPatterns.map(({ keys }) => signature(projectKeys(keys))));
  // Keeping a singular category must not disguise removing its status fallback.
  const fixedBranches = (forms: ReturnType<typeof patterns>) =>
    forms
      .filter(({ keys }) => signature(keys) === signature(projectKeys(keys)))
      .map(({ keys }) => signature(keys))
      .sort();
  if (
    signature([...sourceBranches].sort()) !== signature([...targetBranches].sort()) ||
    (source.type === 'select' &&
      signature(fixedBranches(sourcePatterns)) !== signature(fixedBranches(targetPatterns)))
  ) {
    issues.push({
      code: 'mf2-branches-changed',
      severity: 'error',
      message:
        'MF2 structure is protected: preserve fixed selector values, exact-number forms, and fallback branches. Locale plural forms may differ.',
    });
  }

  for (const targetPattern of targetPatterns) {
    const candidates = sourcePatterns.filter(
      ({ keys }) =>
        source.type === 'message' ||
        keys.every(
          (key, index) =>
            key.type === '*' || signature(key) === signature(targetPattern.keys[index]),
        ),
    );
    // Prefer exact keys over wildcards, in selector order, matching the editor's
    // source-form comparison. Added locale categories use the source fallback.
    candidates.sort((a, b) => {
      for (let index = 0; index < selectors.length; index++) {
        const delta = Number(b.keys[index]?.type !== '*') - Number(a.keys[index]?.type !== '*');
        if (delta) return delta;
      }
      return 0;
    });
    const original = candidates[0];
    if (!original) continue; // A selector/branch diagnostic explains this case.
    const optionalCounters = optionalCounterNames(target, targetPattern.keys, locale);
    if (
      !protectedPartsMatch(
        original.value,
        targetPattern.value,
        optionalCounters,
        selectorCounters,
      ) ||
      signature(markupOrder(original.value)) !== signature(markupOrder(targetPattern.value))
    ) {
      const formLabel = label(targetPattern.keys, selectors);
      issues.push({
        code: 'mf2-placeholders-changed',
        severity: 'error',
        formLabel,
        message: `MF2 structure is protected in ${formLabel}: preserve placeholders, formatting options, and markup. You can move placeholders within the translated text.`,
      });
    }
  }
  return issues;
}
