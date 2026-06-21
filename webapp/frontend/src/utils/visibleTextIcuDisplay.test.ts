import { describe, expect, it } from 'vitest';

import { getIcuFormOptions } from './protectedTextTokens';
import {
  getVisibleTextIcuMessagesForValue,
  getVisibleTextIcuMessagesFromControls,
  visibleIcuSyntaxDisplay,
  visibleProtectedTokenText,
} from './visibleTextIcuDisplay';

describe('visibleTextIcuDisplay', () => {
  it('formats ICU plural syntax without exposing braces or the plural keyword', () => {
    expect(visibleIcuSyntaxDisplay('{count, plural, one {')).toEqual({
      argument: 'count',
      form: 'one',
      kind: 'argument-form',
      text: 'count one',
    });
    expect(visibleIcuSyntaxDisplay('} other {')).toEqual({
      form: 'other',
      kind: 'form',
      text: 'other',
    });
    expect(visibleIcuSyntaxDisplay('}}')).toEqual({
      kind: 'empty',
      text: '',
    });
    expect(visibleProtectedTokenText('icu-placeholder', '{count}')).toBe('count');
    expect(visibleProtectedTokenText('mf2-demo', '{$count}')).toBe('count');
  });

  it('builds one shared message model from ICU form controls', () => {
    const value = '{count, plural, one {# file} =15 {# files} other {# files}}';
    const messages = getVisibleTextIcuMessagesFromControls(getIcuFormOptions(value));

    expect(messages).toEqual([
      expect.objectContaining({
        ariaLabel: 'count plural forms: 3/7',
        checkedCount: 3,
        key: `plural:0:${value.length}`,
        label: 'count plural forms',
        messageEnd: value.length,
        messageStart: 0,
        messageType: 'plural',
        summary: '3/7',
        totalCount: 7,
      }),
    ]);
  });

  it('uses the outer ICU message for passive nested rendering', () => {
    const value =
      '{count, plural, one {{inner, plural, one {# item} other {# items}}} other {# groups}}';

    expect(getVisibleTextIcuMessagesForValue(value)).toEqual([
      expect.objectContaining({
        key: `plural:0:${value.length}`,
        messageEnd: value.length,
        messageStart: 0,
      }),
    ]);
  });
});
