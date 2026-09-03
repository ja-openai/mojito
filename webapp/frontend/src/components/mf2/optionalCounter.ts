import type { MF2Message, MF2Pattern, MF2VariantKey } from '@mojito-mf2/core';

import pluralData from '../../../../../mf2/cldr/generated/all/plural_rules.json';
import { pluralLookupChain } from '../../../../../mf2/javascript/src/locale-key.js';

type Relation = {
  operand: string;
  operator: string;
  ranges: number[][];
  modulo?: number;
};

function fixedOperand(relations: Relation[], operand: string) {
  const relation = relations.find(
    (item) =>
      item.operand === operand &&
      item.operator === '=' &&
      item.modulo == null &&
      item.ranges.length === 1 &&
      item.ranges[0][0] === item.ranges[0][1],
  );
  return relation?.ranges[0][0];
}

function isSingletonCategory(condition: Relation[][] | null) {
  if (!condition?.length) return false;
  const values = condition.map((relations) => {
    const number = fixedOperand(relations, 'n');
    if (number != null) return number;
    // i alone permits fractions. Requiring v=0 also fixes the full quantity.
    return fixedOperand(relations, 'v') === 0 ? fixedOperand(relations, 'i') : undefined;
  });
  return values[0] != null && values.every((value) => value === values[0]);
}

const singletonCategoriesByRule = new Map(
  pluralData.cardinal.rules.map((rule) => [
    rule.id,
    new Set(
      rule.categories
        .filter(({ condition }) => isSingletonCategory(condition))
        .map(({ category }) => category),
    ),
  ]),
);

function singletonCategories(locale: string) {
  const locales: Record<string, string> = pluralData.cardinal.locales;
  for (const candidate of pluralLookupChain(locale, pluralData.cardinal.parents)) {
    const rule = locales[candidate];
    if (rule != null) return singletonCategoriesByRule.get(rule);
  }
  return undefined;
}

/** A fixed quantity can be expressed in words instead of rendering its counter. */
export function optionalCounterNames(
  model: MF2Message,
  targetKeys: MF2VariantKey[],
  locale: string,
): Set<string> {
  const names = new Set<string>();
  if (model.type !== 'select') return names;
  const categories = singletonCategories(locale);
  model.selectors.forEach(({ name }, index) => {
    const key = targetKeys[index];
    if (key?.type !== 'literal') return;
    const declaration = model.declarations.find((item) => item.name === name);
    // Locals may inherit formatting from another declaration even when their
    // own expression has no options. Keep those protected until resolved here.
    if (declaration?.type !== 'input') return;
    const expression = declaration.value;
    const fn = expression?.function;
    if (!fn || !['number', 'integer'].includes(fn.name) || expression?.attributes) return;
    // Custom formatting and dynamic selection need their own contract. Keep
    // their counters protected rather than assuming ordinary numeric behavior.
    if (Object.keys(fn.options ?? {}).some((option) => option !== 'select')) return;
    const select = fn.options?.select;
    if (
      select &&
      (select.type !== 'literal' || !['plural', 'exact', 'ordinal'].includes(select.value))
    )
      return;
    const exact =
      /^-?(?:0|[1-9]\d*)(?:\.\d+)?$/u.test(key.value) && Number.isFinite(Number(key.value));
    const cardinal = !select || (select.type === 'literal' && select.value === 'plural');
    if (exact || (cardinal && categories?.has(key.value))) names.add(name);
  });
  return names;
}

export function isOptionalCounterExpression(part: MF2Pattern[number], names: Set<string>): boolean {
  return (
    typeof part !== 'string' &&
    part.type === 'expression' &&
    part.arg?.type === 'variable' &&
    names.has(part.arg.name) &&
    !part.function &&
    !part.attributes
  );
}
