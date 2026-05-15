import { describe, expect, it } from 'vitest';

import {
  formatReviewAutomationBatchRow,
  parseReviewAutomationBatchInput,
} from './reviewAutomationBatchParsing';

const featureIdsByName = new Map([
  ['admin, billing and payments', 1],
  ['android', 2],
  ['billing', 3],
  ['checkout', 4],
  ['codex, general agents and tools', 5],
]);

const featureDisplayNamesByName = new Map([
  ['admin, billing and payments', 'Admin, Billing and Payments'],
  ['android', 'Android'],
  ['billing', 'Billing'],
  ['checkout', 'Checkout'],
  ['codex, general agents and tools', 'Codex, General Agents and Tools'],
]);

const existingAutomationsByName = new Map([
  ['admin, billing and payments', { id: 10 }],
  ['codex, general agents and tools', { id: 11 }],
]);

const teamIdsByName = new Map([['smartling product', 20]]);
const teamDisplayNamesByName = new Map([['smartling product', 'Smartling Product']]);

const parseRows = (features: string) =>
  parseReviewAutomationBatchInput(
    `Admin, Billing and Payments | enabled | 0 0 10 ? * MON-SAT | America/Los_Angeles | Smartling Product | no-translator | 1 | 2000 | ${features}`,
    featureIdsByName,
    featureDisplayNamesByName,
    existingAutomationsByName,
    teamIdsByName,
    teamDisplayNamesByName,
  );

describe('formatReviewAutomationBatchRow', () => {
  it('uses semicolons between feature names so commas in names round-trip', () => {
    expect(
      formatReviewAutomationBatchRow({
        name: 'Admin, Billing and Payments',
        enabled: true,
        cronExpression: '0 0 10 ? * MON-SAT',
        timeZone: 'America/Los_Angeles',
        teamName: 'Smartling Product',
        assignTranslator: false,
        dueDateOffsetDays: 1,
        maxWordCountPerProject: 2000,
        featureNames: ['Admin, Billing and Payments', 'Android'],
      }),
    ).toBe(
      'Admin, Billing and Payments | enabled | 0 0 10 ? * MON-SAT | America/Los_Angeles | Smartling Product | no-translator | 1 | 2000 | Admin, Billing and Payments; Android',
    );
  });
});

describe('parseReviewAutomationBatchInput', () => {
  it('keeps an exact review feature name that contains a comma', () => {
    const [row] = parseRows('Admin, Billing and Payments');

    expect(row.errors).toEqual([]);
    expect(row.featureIds).toEqual([1]);
    expect(row.featureNames).toEqual(['Admin, Billing and Payments']);
  });

  it('supports semicolon-separated feature lists with comma-containing names', () => {
    const [row] = parseRows('Admin, Billing and Payments; Android');

    expect(row.errors).toEqual([]);
    expect(row.featureIds).toEqual([1, 2]);
    expect(row.featureNames).toEqual(['Admin, Billing and Payments', 'Android']);
  });

  it('keeps legacy comma-separated feature lists for names without commas', () => {
    const [row] = parseRows('Billing, Checkout');

    expect(row.errors).toEqual([]);
    expect(row.featureIds).toEqual([3, 4]);
    expect(row.featureNames).toEqual(['Billing', 'Checkout']);
  });
});
