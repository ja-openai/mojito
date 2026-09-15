import { describe, expect, it } from 'vitest';

import {
  formatReviewAutomationBatchRow,
  parseReviewAutomationBatchInput,
} from './reviewAutomationBatchParsing';

const featureIdsByName = new Map([
  ['billing, accounts and payments', 1],
  ['mobile', 2],
  ['billing', 3],
  ['checkout', 4],
  ['catalog, search and tools', 5],
]);

const featureDisplayNamesByName = new Map([
  ['billing, accounts and payments', 'Billing, Accounts and Payments'],
  ['mobile', 'Mobile'],
  ['billing', 'Billing'],
  ['checkout', 'Checkout'],
  ['catalog, search and tools', 'Catalog, Search and Tools'],
]);

const existingAutomationsByName = new Map([
  ['billing, accounts and payments', { id: 10 }],
  ['catalog, search and tools', { id: 11 }],
]);

const teamIdsByName = new Map([['example team', 20]]);
const teamDisplayNamesByName = new Map([['example team', 'Example Team']]);

const parseRows = (features: string) =>
  parseReviewAutomationBatchInput(
    `Billing, Accounts and Payments | enabled | 0 0 10 ? * MON-SAT | America/Los_Angeles | Example Team | no-translator | 1 | 2000 | ${features}`,
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
        name: 'Billing, Accounts and Payments',
        enabled: true,
        cronExpression: '0 0 10 ? * MON-SAT',
        timeZone: 'America/Los_Angeles',
        teamName: 'Example Team',
        assignTranslator: false,
        dueDateOffsetDays: 1,
        maxWordCountPerProject: 2000,
        featureNames: ['Billing, Accounts and Payments', 'Mobile'],
      }),
    ).toBe(
      'Billing, Accounts and Payments | enabled | 0 0 10 ? * MON-SAT | America/Los_Angeles | Example Team | no-translator | 1 | 2000 | Billing, Accounts and Payments; Mobile',
    );
  });
});

describe('parseReviewAutomationBatchInput', () => {
  it('round-trips explicit all-incident scope and preserves omitted legacy scope', () => {
    expect(
      parseRows('Mobile | | INCIDENTS | TRANSLATION_QUALITY')[0].incidentScope,
    ).toBeUndefined();
    const [row] = parseRows('Mobile | he | INCIDENTS | TRANSLATION_QUALITY | ALL');
    expect(row.errors).toEqual([]);
    expect(row.incidentScope).toBe('ALL');
    expect(formatReviewAutomationBatchRow(row)).toContain(
      '| INCIDENTS | TRANSLATION_QUALITY | ALL',
    );
    expect(parseRows('Mobile | | INCIDENTS | | UNKNOWN')[0].errors).toContain(
      'Incident scope must be ALL or REVIEW_FEATURES',
    );
  });

  it('preserves omitted source and round-trips incident selection with exclusions', () => {
    expect(parseRows('Mobile')[0].reviewSource).toBeUndefined();
    const [row] = parseRows('Mobile | he; fr-CA | INCIDENTS | TERMINOLOGY');
    expect(row.errors).toEqual([]);
    expect(row.reviewSource).toBe('INCIDENTS');
    expect(row.incidentReviewType).toBe('TERMINOLOGY');
    expect(row.excludedLocaleTags).toEqual(['he', 'fr-CA']);
    expect(formatReviewAutomationBatchRow(row)).toContain('| he; fr-CA | INCIDENTS | TERMINOLOGY');
  });

  it('rejects unknown incident source and malformed review type', () => {
    expect(parseRows('Mobile | | UNKNOWN | bad type')[0].errors).toHaveLength(2);
  });

  it('preserves existing exclusions when the optional column is omitted', () => {
    const [row] = parseRows('Mobile');

    expect(row.action).toBe('update');
    expect(row.errors).toEqual([]);
    expect(row.excludedLocaleTags).toBeUndefined();
    expect(JSON.parse(JSON.stringify(row))).not.toHaveProperty('excludedLocaleTags');
  });

  it('clears exclusions only when the optional column is explicitly empty', () => {
    const [row] = parseRows('Mobile | ');

    expect(row.errors).toEqual([]);
    expect(row.excludedLocaleTags).toEqual([]);
  });

  it('round-trips exported exclusions and canonical locale tags', () => {
    const text = formatReviewAutomationBatchRow({
      name: 'Billing, Accounts and Payments',
      enabled: true,
      cronExpression: '0 0 10 ? * MON-SAT',
      timeZone: 'America/Los_Angeles',
      teamName: 'Example Team',
      assignTranslator: false,
      dueDateOffsetDays: 1,
      maxWordCountPerProject: 2000,
      featureNames: ['Billing, Accounts and Payments', 'Mobile'],
      excludedLocaleTags: ['he', 'fr-CA'],
    });
    const [row] = parseReviewAutomationBatchInput(
      text,
      featureIdsByName,
      featureDisplayNamesByName,
      existingAutomationsByName,
      teamIdsByName,
      teamDisplayNamesByName,
    );

    expect(row.errors).toEqual([]);
    expect(row.featureIds).toEqual([1, 2]);
    expect(row.assignTranslator).toBe(false);
    expect(row.excludedLocaleTags).toEqual(['he', 'fr-CA']);
  });

  it('round-trips an empty exclusion list from an export', () => {
    const text = formatReviewAutomationBatchRow({
      name: 'Billing, Accounts and Payments',
      enabled: true,
      cronExpression: '0 0 10 ? * MON-SAT',
      timeZone: 'America/Los_Angeles',
      teamName: 'Example Team',
      dueDateOffsetDays: 1,
      maxWordCountPerProject: 2000,
      featureNames: ['Mobile'],
      excludedLocaleTags: [],
    });
    const [row] = parseReviewAutomationBatchInput(
      text,
      featureIdsByName,
      featureDisplayNamesByName,
      existingAutomationsByName,
      teamIdsByName,
      teamDisplayNamesByName,
    );

    expect(row.errors).toEqual([]);
    expect(row.excludedLocaleTags).toEqual([]);
  });

  it('preserves the legacy format without translator assignment or exclusions', () => {
    const [row] = parseReviewAutomationBatchInput(
      'Billing, Accounts and Payments | enabled | 0 0 10 ? * MON-SAT | America/Los_Angeles | Example Team | 1 | 2000 | Mobile',
      featureIdsByName,
      featureDisplayNamesByName,
      existingAutomationsByName,
      teamIdsByName,
      teamDisplayNamesByName,
    );

    expect(row.errors).toEqual([]);
    expect(row.assignTranslator).toBe(true);
    expect(row.featureIds).toEqual([2]);
    expect(row.excludedLocaleTags).toBeUndefined();
  });

  it('keeps an exact review feature name that contains a comma', () => {
    const [row] = parseRows('Billing, Accounts and Payments');

    expect(row.errors).toEqual([]);
    expect(row.featureIds).toEqual([1]);
    expect(row.featureNames).toEqual(['Billing, Accounts and Payments']);
  });

  it('supports semicolon-separated feature lists with comma-containing names', () => {
    const [row] = parseRows('Billing, Accounts and Payments; Mobile');

    expect(row.errors).toEqual([]);
    expect(row.featureIds).toEqual([1, 2]);
    expect(row.featureNames).toEqual(['Billing, Accounts and Payments', 'Mobile']);
  });

  it('keeps legacy comma-separated feature lists for names without commas', () => {
    const [row] = parseRows('Billing, Checkout');

    expect(row.errors).toEqual([]);
    expect(row.featureIds).toEqual([3, 4]);
    expect(row.featureNames).toEqual(['Billing', 'Checkout']);
  });
});
