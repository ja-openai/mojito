import { describe, expect, it } from 'vitest';

import type { ApiReviewProjectDetail } from '../../api/review-projects';
import {
  applyReviewProjectFindReplacePlan,
  buildReviewProjectFindReplacePlan,
  buildReviewProjectFindReplaceRows,
  countChangedReviewProjectFindReplaceRows,
  countLiteralMatches,
  getReviewProjectFindReplaceIntegrityIssues,
  getReviewProjectFindReplaceVisibleRows,
  type ReviewProjectFindReplaceRow,
} from './review-project-find-replace';

function buildProject(overrides: Partial<ApiReviewProjectDetail> = {}): ApiReviewProjectDetail {
  return {
    id: 7,
    type: 'NORMAL',
    status: 'OPEN',
    locale: { id: 3, bcp47Tag: 'fr' },
    reviewProjectTextUnits: [
      {
        id: 101,
        tmTextUnit: {
          id: 2001,
          name: 'chat.status.thinking',
          content: 'Thinking...',
          comment: 'Loading label',
          asset: { assetPath: 'web/app.json', repository: { id: 1, name: 'web' } },
        },
        baselineTmTextUnitVariant: {
          id: 3001,
          content: 'Réflexionbam...',
          status: 'REVIEW_NEEDED',
          includedInLocalizedFile: true,
        },
        currentTmTextUnitVariant: null,
      },
      {
        id: 102,
        tmTextUnit: {
          id: 2002,
          name: 'chat.status.done',
          content: 'Done',
        },
        baselineTmTextUnitVariant: {
          id: 3002,
          content: 'Terminé',
          status: 'APPROVED',
          includedInLocalizedFile: true,
        },
        currentTmTextUnitVariant: {
          id: 4002,
          content: 'Fini',
          status: 'APPROVED',
          includedInLocalizedFile: true,
        },
      },
    ],
    ...overrides,
  };
}

function buildRow(
  overrides: Partial<ReviewProjectFindReplaceRow> = {},
): ReviewProjectFindReplaceRow {
  return {
    id: 101,
    textUnitId: 2001,
    name: 'chat.status.thinking',
    locale: 'fr',
    source: 'Thinking...',
    sourceComment: null,
    originalTarget: 'Réflexionbam...',
    workingTarget: 'Réflexionbam...',
    status: 'REVIEW_NEEDED',
    targetStatus: 'REVIEW_NEEDED',
    targetComment: null,
    existingDecisionNotes: null,
    includedInLocalizedFile: true,
    expectedCurrentTmTextUnitVariantId: null,
    assetPath: null,
    hasStagedSuggestion: false,
    ...overrides,
  };
}

describe('review project find/replace helpers', () => {
  it('builds a local working dataset from the effective review-project target', () => {
    const rows = buildReviewProjectFindReplaceRows(buildProject());

    expect(rows).toMatchObject([
      {
        id: 101,
        textUnitId: 2001,
        name: 'chat.status.thinking',
        locale: 'fr',
        source: 'Thinking...',
        sourceComment: 'Loading label',
        originalTarget: 'Réflexionbam...',
        workingTarget: 'Réflexionbam...',
        status: 'REVIEW_NEEDED',
        assetPath: 'web/app.json',
      },
      {
        id: 102,
        originalTarget: 'Fini',
        workingTarget: 'Fini',
        status: 'APPROVED',
      },
    ]);
  });

  it('uses staged find-replace metadata as the working target only', () => {
    const rows = buildReviewProjectFindReplaceRows(
      buildProject({
        reviewProjectTextUnits: [
          {
            id: 101,
            tmTextUnit: {
              id: 2001,
              name: 'chat.status.thinking',
              content: 'Thinking...',
            },
            baselineTmTextUnitVariant: {
              id: 3001,
              content: 'Réflexionbam...',
              status: 'REVIEW_NEEDED',
              includedInLocalizedFile: true,
            },
            currentTmTextUnitVariant: null,
            reviewProjectTextUnitSuggestion: {
              id: 9001,
              target: 'Raisonnement...',
              source: 'FIND_REPLACE',
              previousTarget: 'Réflexionbam...',
            },
          },
        ],
      }),
    );

    expect(rows[0]).toMatchObject({
      originalTarget: 'Réflexionbam...',
      workingTarget: 'Raisonnement...',
      status: 'REVIEW_NEEDED',
    });
    expect(countChangedReviewProjectFindReplaceRows(rows)).toBe(1);
  });

  it('plans and applies literal replacements to the current working target', () => {
    const rows = [
      buildRow(),
      buildRow({
        id: 102,
        originalTarget: 'Réflexionbam légère',
        workingTarget: 'Réflexionbam légère',
      }),
    ];

    const plan = buildReviewProjectFindReplacePlan(rows, {
      findText: 'Réflexionbam',
      replaceText: 'Raisonnement',
      matchCase: true,
    });

    expect(plan.totalMatches).toBe(2);
    expect(plan.targets).toHaveLength(2);

    const nextRows = applyReviewProjectFindReplacePlan(rows, plan);
    expect(nextRows.map((row) => row.workingTarget)).toEqual([
      'Raisonnement...',
      'Raisonnement légère',
    ]);
    expect(countChangedReviewProjectFindReplaceRows(nextRows)).toBe(2);
  });

  it('uses case-insensitive matching when match case is off', () => {
    expect(
      countLiteralMatches('Thinking thinking THINKING', {
        findText: 'thinking',
        matchCase: false,
      }),
    ).toBe(3);
    expect(
      countLiteralMatches('Thinking thinking THINKING', {
        findText: 'thinking',
        matchCase: true,
      }),
    ).toBe(1);
  });

  it('uses whole-word and regex options when counting current matches', () => {
    expect(
      countLiteralMatches('chat chatty chat_thing chat.', {
        findText: 'chat',
        matchCase: true,
        wholeWord: true,
      }),
    ).toBe(2);
    expect(
      countLiteralMatches('v1 v22 version', {
        findText: 'v\\d+',
        matchCase: true,
        regex: true,
      }),
    ).toBe(2);
  });

  it('applies preserve-case replacements to the working target', () => {
    const rows = [
      buildRow({
        originalTarget: 'Chat chat CHAT',
        workingTarget: 'Chat chat CHAT',
      }),
    ];

    const plan = buildReviewProjectFindReplacePlan(rows, {
      findText: 'chat',
      replaceText: 'message',
      matchCase: false,
      preserveCase: true,
    });

    expect(plan.totalMatches).toBe(3);
    expect(applyReviewProjectFindReplacePlan(rows, plan)[0].workingTarget).toBe(
      'Message message MESSAGE',
    );
  });

  it('keeps changed rows visible after the find term has been replaced', () => {
    const row = buildRow({
      originalTarget: 'Réflexionbam...',
      workingTarget: 'Raisonnement...',
    });

    expect(
      getReviewProjectFindReplaceVisibleRows([row], {
        findText: 'Réflexionbam',
        replaceText: 'Raisonnement',
        matchCase: true,
      }),
    ).toHaveLength(1);
  });

  it('shows the whole review project dataset before a find term is entered', () => {
    const rows = [
      buildRow(),
      buildRow({
        id: 102,
        originalTarget: 'Terminé',
        workingTarget: 'Terminé',
      }),
    ];

    expect(
      getReviewProjectFindReplaceVisibleRows(rows, {
        findText: '',
        replaceText: '',
        matchCase: false,
      }).map(({ row }) => row.id),
    ).toEqual([101, 102]);
  });

  it('skips literal replacements that would alter protected placeholders', () => {
    const rows = [
      buildRow({
        originalTarget: 'Bonjour {name}',
        workingTarget: 'Bonjour {name}',
      }),
    ];

    const plan = buildReviewProjectFindReplacePlan(rows, {
      findText: 'name',
      replaceText: 'user',
      matchCase: true,
    });

    expect(plan.targets).toHaveLength(0);
    expect(plan.blockedTargets).toEqual([
      {
        rowId: 101,
        message:
          'Changing this text would alter protected placeholders, markup, or platform syntax.',
      },
    ]);
    expect(plan.blockedMatches).toBe(1);
    expect(applyReviewProjectFindReplacePlan(rows, plan)[0].workingTarget).toBe('Bonjour {name}');
  });

  it('reports changed rows that no longer preserve protected syntax', () => {
    const rows = [
      buildRow({
        originalTarget: 'Bonjour {name}',
        workingTarget: 'Bonjour user',
      }),
    ];

    expect(getReviewProjectFindReplaceIntegrityIssues(rows)).toEqual([
      {
        rowId: 101,
        message:
          'Changing this text would alter protected placeholders, markup, or platform syntax.',
      },
    ]);
  });
});
