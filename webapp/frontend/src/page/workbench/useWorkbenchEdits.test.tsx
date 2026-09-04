import { type InfiniteData, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { PropsWithChildren } from 'react';
import { describe, expect, it, vi } from 'vitest';

import type * as TextUnitsApi from '../../api/text-units';
import type { ApiTextUnit, saveTextUnit } from '../../api/text-units';
import type * as IntegrityCheck from '../../utils/integrityCheck';
import type { WorkbenchRow } from './workbench-types';

const mf2TranslationErrorCountMock = vi.hoisted(() => vi.fn(() => 0));
const saveTextUnitMock = vi.hoisted(() => vi.fn<typeof saveTextUnit>());

vi.mock('../../api/text-units', async (importOriginal) => ({
  ...(await importOriginal<typeof TextUnitsApi>()),
  saveTextUnit: saveTextUnitMock,
}));

vi.mock('../../utils/integrityCheck', async (importOriginal) => ({
  ...(await importOriginal<typeof IntegrityCheck>()),
  checkTextUnitIntegrityWithRetry: vi.fn().mockResolvedValue({ checkResult: true }),
}));

vi.mock('../../components/mf2/translationValidation', () => ({
  mf2TranslationErrorCount: mf2TranslationErrorCountMock,
}));

import { useWorkbenchEdits } from './useWorkbenchEdits';

const mf2Row: WorkbenchRow = {
  id: 'mf2-row',
  textUnitName: 'files.count',
  repositoryName: 'messages',
  assetPath: 'messages.mf2',
  locations: [],
  locale: 'fr',
  localeId: 2,
  source: `.input {$count :number}
{{You have {$count} files.}}`,
  messageFormat: 'MF2',
  translation: `.input {$count :number}
{{Vous avez {$count} fichiers.}}`,
  sourceCreatedDate: null,
  translationCreatedDate: null,
  translationCreatedByUsername: null,
  status: 'Needs review',
  comment: null,
  tmTextUnitId: 1,
  tmTextUnitVariantId: 10,
  tmTextUnitCurrentVariantId: 10,
  isUsed: true,
  canEdit: true,
};

describe('useWorkbenchEdits MF2 bulk validation', () => {
  it('does not eagerly parse loaded MF2 rows and fails bulk acceptance closed', () => {
    mf2TranslationErrorCountMock.mockClear();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: PropsWithChildren) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
    const { result } = renderHook(
      () =>
        useWorkbenchEdits({
          apiRows: [mf2Row],
          canSearch: true,
          activeSearchRequest: null,
          canBypassIntegrityCheck: false,
        }),
      { wrapper },
    );

    expect(mf2TranslationErrorCountMock).not.toHaveBeenCalled();

    act(() => result.current.requestBulkStatusChange('Accepted'));

    expect(mf2TranslationErrorCountMock).not.toHaveBeenCalled();
    expect(result.current.pendingBulkAction).toBeNull();
    expect(result.current.bulkActionErrorMessage).toBe(
      'Accept loaded MF2 translations individually so each row can be validated safely.',
    );
  });
});

describe('useWorkbenchEdits saved-by attribution', () => {
  it.each([
    { label: 'new variant creator', creator: 'new-translator', expected: 'new-translator' },
    {
      label: 'reused variant creator',
      creator: 'original-translator',
      expected: 'original-translator',
    },
    { label: 'unknown creator', creator: null, expected: null },
    { label: 'omitted creator', creator: undefined, expected: null },
  ])('uses the $label from the save response', async ({ creator, expected }) => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const queryKey = ['workbench-search', 'saved-by-test'];
    const initial: ApiTextUnit = {
      tmTextUnitId: 1,
      tmTextUnitVariantId: 10,
      tmTextUnitCurrentVariantId: 10,
      localeId: 2,
      name: 'greeting',
      source: 'Hello',
      target: 'Bonjour',
      targetLocale: 'fr',
      used: true,
      status: 'REVIEW_NEEDED',
      includedInLocalizedFile: true,
      translationCreatedByUsername: 'previous-translator',
    };
    const otherLocale: ApiTextUnit = {
      ...initial,
      localeId: 3,
      target: 'Hallo',
      targetLocale: 'de',
      translationCreatedByUsername: 'other-translator',
    };
    queryClient.setQueryData<InfiniteData<ApiTextUnit[], number>>(queryKey, {
      pages: [[initial, otherLocale]],
      pageParams: [0],
    });
    const cachedRows = () =>
      queryClient.getQueryData<InfiniteData<ApiTextUnit[], number>>(queryKey)?.pages[0];
    const row: WorkbenchRow = {
      ...mf2Row,
      id: 'greeting-fr',
      textUnitName: initial.name,
      source: initial.source!,
      messageFormat: null,
      translation: initial.target!,
    };
    let resolveSave!: (saved: ApiTextUnit) => void;
    const pendingSave = new Promise<ApiTextUnit>((resolve) => {
      resolveSave = resolve;
    });
    saveTextUnitMock.mockReset();
    saveTextUnitMock.mockReturnValue(pendingSave);
    const wrapper = ({ children }: PropsWithChildren) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
    const { result } = renderHook(
      () =>
        useWorkbenchEdits({
          apiRows: [row],
          canSearch: true,
          activeSearchRequest: null,
          canBypassIntegrityCheck: false,
        }),
      { wrapper },
    );

    act(() => result.current.onStartEditing(row.id, row.translation));
    act(() => result.current.onChangeEditingValue('Salut'));
    act(() => result.current.onSaveEditing());

    await waitFor(() => expect(saveTextUnitMock).toHaveBeenCalledOnce());
    expect(cachedRows()?.[0]).toMatchObject({
      target: 'Salut',
      translationCreatedByUsername: null,
    });
    expect(cachedRows()?.[1]).toEqual(otherLocale);

    await act(async () => {
      resolveSave({
        ...initial,
        target: 'Salut',
        tmTextUnitVariantId: 20,
        tmTextUnitCurrentVariantId: 20,
        translationCreatedByUsername: creator,
      });
      await pendingSave;
    });

    await waitFor(() => expect(result.current.isSaving).toBe(false));
    expect(cachedRows()?.[0]).toMatchObject({
      target: 'Salut',
      tmTextUnitVariantId: 20,
      translationCreatedByUsername: expected,
    });
    expect(cachedRows()?.[1]).toEqual(otherLocale);
  });
});
