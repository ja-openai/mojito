import { type InfiniteData, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { PropsWithChildren } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type * as TextUnitsApi from '../../api/text-units';
import type { ApiTextUnit, saveTextUnit, TextUnitIntegrityCheckResult } from '../../api/text-units';
import type * as IntegrityCheck from '../../utils/integrityCheck';
import type { WorkbenchRow } from './workbench-types';

const mf2TranslationErrorCountMock = vi.hoisted(() => vi.fn(() => 0));
const saveTextUnitMock = vi.hoisted(() => vi.fn<typeof saveTextUnit>());
const deleteTextUnitCurrentVariantsMock = vi.hoisted(() =>
  vi.fn<typeof TextUnitsApi.deleteTextUnitCurrentVariants>(),
);
const updateTextUnitCurrentVariantsStatusMock = vi.hoisted(() =>
  vi.fn<typeof TextUnitsApi.updateTextUnitCurrentVariantsStatus>(),
);
const checkTextUnitIntegrityMock = vi.hoisted(() =>
  vi.fn<typeof IntegrityCheck.checkTextUnitIntegrityWithRetry>().mockResolvedValue({
    checkResult: true,
  }),
);

vi.mock('../../api/text-units', async (importOriginal) => ({
  ...(await importOriginal<typeof TextUnitsApi>()),
  saveTextUnit: saveTextUnitMock,
  deleteTextUnitCurrentVariants: deleteTextUnitCurrentVariantsMock,
  updateTextUnitCurrentVariantsStatus: updateTextUnitCurrentVariantsStatusMock,
}));

vi.mock('../../utils/integrityCheck', async (importOriginal) => ({
  ...(await importOriginal<typeof IntegrityCheck>()),
  checkTextUnitIntegrityWithRetry: checkTextUnitIntegrityMock,
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

describe('useWorkbenchEdits navigation guard', () => {
  const row: WorkbenchRow = {
    ...mf2Row,
    id: 'greeting-fr',
    source: 'Hello',
    messageFormat: null,
    translation: 'Bonjour',
  };
  const otherRow: WorkbenchRow = { ...row, id: 'farewell-fr', tmTextUnitId: 2 };
  const saved: ApiTextUnit = {
    tmTextUnitId: row.tmTextUnitId,
    localeId: row.localeId,
    name: row.textUnitName,
    source: row.source,
    target: 'Salut',
    targetLocale: row.locale,
    used: true,
    status: 'APPROVED',
    includedInLocalizedFile: true,
  };

  function deferred<T>() {
    let resolve!: (value: T) => void;
    const promise = new Promise<T>((resolvePromise) => {
      resolve = resolvePromise;
    });
    return { promise, resolve };
  }

  function renderEdits() {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: PropsWithChildren) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
    return renderHook(
      () =>
        useWorkbenchEdits({
          apiRows: [row, otherRow],
          canSearch: true,
          activeSearchRequest: null,
          canBypassIntegrityCheck: false,
        }),
      { wrapper },
    );
  }

  beforeEach(() => {
    saveTextUnitMock.mockReset().mockResolvedValue(saved);
    deleteTextUnitCurrentVariantsMock.mockReset().mockResolvedValue({ deletedCount: 1 });
    updateTextUnitCurrentVariantsStatusMock.mockReset().mockResolvedValue({ updatedCount: 1 });
    checkTextUnitIntegrityMock.mockReset().mockResolvedValue({ checkResult: true });
  });

  it('ends an unchanged edit before navigating without a dialog', () => {
    const { result } = renderEdits();
    const blur = vi.fn();
    result.current.translationInputRef.current = { blur, focus: vi.fn() };
    const navigate = vi.fn(() => expect(blur).toHaveBeenCalledOnce());
    act(() => result.current.onStartEditing(row.id, row.translation));

    act(() => result.current.requestNavigate(navigate));

    expect(navigate).toHaveBeenCalledOnce();
    expect(result.current.showDiscardDialog).toBe(false);
    expect(result.current.editingRowId).toBeNull();
    expect(saveTextUnitMock).not.toHaveBeenCalled();
  });

  it('keeps a draft when dismissed and discards it only on confirmed navigation', () => {
    const { result } = renderEdits();
    const navigate = vi.fn();
    act(() => result.current.onStartEditing(row.id, row.translation));
    act(() => result.current.onChangeEditingValue('Salut'));

    act(() => result.current.requestNavigate(navigate));
    expect(result.current.showDiscardDialog).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
    act(() => result.current.dismissDiscardEditing());
    expect(result.current.showDiscardDialog).toBe(false);
    expect(result.current.editingRowId).toBe(row.id);
    expect(result.current.editingValue).toBe('Salut');

    act(() => result.current.requestNavigate(navigate));
    act(() => result.current.confirmDiscardEditing());
    expect(navigate).toHaveBeenCalledOnce();
    expect(result.current.showDiscardDialog).toBe(false);
    expect(result.current.editingRowId).toBeNull();
    expect(result.current.editingValue).toBe('');
    act(() => result.current.confirmDiscardEditing());
    expect(navigate).toHaveBeenCalledOnce();
    expect(saveTextUnitMock).not.toHaveBeenCalled();
  });

  it('still confirms discarding a draft before editing another row', () => {
    const { result } = renderEdits();
    act(() => result.current.onStartEditing(row.id, row.translation));
    act(() => result.current.onChangeEditingValue('Salut'));

    act(() => result.current.onStartEditing(otherRow.id, otherRow.translation));
    expect(result.current.showDiscardDialog).toBe(true);
    expect(result.current.editingRowId).toBe(row.id);
    act(() => result.current.confirmDiscardEditing());

    expect(result.current.showDiscardDialog).toBe(false);
    expect(result.current.editingRowId).toBe(otherRow.id);
    expect(result.current.editingValue).toBe(otherRow.translation);
  });

  it('blocks navigation during a translation save and allows it after completion', async () => {
    const save = deferred<ApiTextUnit>();
    saveTextUnitMock.mockReturnValue(save.promise);
    const { result } = renderEdits();
    const navigate = vi.fn();
    act(() => result.current.onStartEditing(row.id, row.translation));
    act(() => result.current.onChangeEditingValue('Salut'));
    act(() => result.current.onSaveEditing());
    await waitFor(() => expect(result.current.isSaving).toBe(true));

    act(() => result.current.requestNavigate(navigate));
    expect(navigate).not.toHaveBeenCalled();
    expect(result.current.showDiscardDialog).toBe(false);
    expect(result.current.editingValue).toBe('Salut');

    await act(async () => {
      save.resolve(saved);
      await save.promise;
    });
    await waitFor(() => expect(result.current.isSaving).toBe(false));
    act(() => result.current.requestNavigate(navigate));
    expect(navigate).toHaveBeenCalledOnce();
  });

  it('rechecks active saves when a pending discard is confirmed', async () => {
    const validation = deferred<TextUnitIntegrityCheckResult>();
    const save = deferred<ApiTextUnit>();
    checkTextUnitIntegrityMock.mockReturnValue(validation.promise);
    saveTextUnitMock.mockReturnValue(save.promise);
    const { result } = renderEdits();
    const navigate = vi.fn();
    act(() => result.current.onStartEditing(row.id, row.translation));
    act(() => result.current.onChangeEditingValue('Salut'));
    act(() => result.current.onSaveEditing());
    act(() => result.current.requestNavigate(navigate));
    expect(result.current.showDiscardDialog).toBe(true);

    await act(async () => {
      validation.resolve({ checkResult: true });
      await validation.promise;
    });
    await waitFor(() => expect(result.current.isSaving).toBe(true));
    act(() => result.current.confirmDiscardEditing());
    expect(navigate).not.toHaveBeenCalled();
    expect(result.current.showDiscardDialog).toBe(true);
    expect(result.current.editingValue).toBe('Salut');

    await act(async () => {
      save.resolve(saved);
      await save.promise;
    });
    await waitFor(() => expect(result.current.isSaving).toBe(false));
    expect(navigate).not.toHaveBeenCalled();
  });

  it.each([true, false])(
    'ignores a late integrity result (%s) after confirmed departure',
    async (checkResult) => {
      const validation = deferred<TextUnitIntegrityCheckResult>();
      checkTextUnitIntegrityMock.mockReturnValue(validation.promise);
      const { result } = renderEdits();
      const navigate = vi.fn();
      act(() => result.current.onStartEditing(row.id, row.translation));
      act(() => result.current.onChangeEditingValue('Salut'));
      act(() => result.current.onSaveEditing());
      act(() => result.current.requestNavigate(navigate));
      act(() => result.current.confirmDiscardEditing());
      expect(navigate).toHaveBeenCalledOnce();

      await act(async () => {
        validation.resolve({ checkResult });
        await validation.promise;
      });

      expect(saveTextUnitMock).not.toHaveBeenCalled();
      expect(result.current.pendingValidationSave).toBeNull();
      expect(result.current.editingRowId).toBeNull();
    },
  );

  it('waits for every active status save, including an earlier concurrent save', async () => {
    const firstSave = deferred<ApiTextUnit>();
    const secondSave = deferred<ApiTextUnit>();
    saveTextUnitMock.mockReturnValueOnce(firstSave.promise).mockReturnValueOnce(secondSave.promise);
    const { result } = renderEdits();
    const navigate = vi.fn();
    act(() => result.current.onChangeStatus(row.id, 'Accepted'));
    await waitFor(() => expect(saveTextUnitMock).toHaveBeenCalledOnce());
    act(() => result.current.onChangeStatus(otherRow.id, 'Accepted'));
    await waitFor(() => expect(saveTextUnitMock).toHaveBeenCalledTimes(2));

    await act(async () => {
      secondSave.resolve({ ...saved, tmTextUnitId: otherRow.tmTextUnitId });
      await secondSave.promise;
    });
    await waitFor(() => expect(result.current.isSaving).toBe(false));
    expect(result.current.statusSavingRowIds.has(row.id)).toBe(true);
    act(() => result.current.requestNavigate(navigate));
    expect(navigate).not.toHaveBeenCalled();

    await act(async () => {
      firstSave.resolve(saved);
      await firstSave.promise;
    });
    await waitFor(() => expect(result.current.statusSavingRowIds.size).toBe(0));
    act(() => result.current.requestNavigate(navigate));
    expect(navigate).toHaveBeenCalledOnce();
  });

  it.each(['delete', 'status'] as const)(
    'blocks navigation while a bulk %s is being applied',
    async (kind) => {
      const bulk = deferred<{ deletedCount: number; updatedCount: number }>();
      deleteTextUnitCurrentVariantsMock.mockReturnValue(bulk.promise);
      updateTextUnitCurrentVariantsStatusMock.mockReturnValue(bulk.promise);
      const { result } = renderEdits();
      const navigate = vi.fn();
      act(() => {
        if (kind === 'delete') {
          result.current.requestDeleteAll();
        } else {
          result.current.requestBulkStatusChange('Accepted');
        }
      });
      act(() => result.current.confirmBulkAction());
      await waitFor(() => expect(result.current.isApplyingBulkAction).toBe(true));

      act(() => result.current.requestNavigate(navigate));
      expect(navigate).not.toHaveBeenCalled();
      expect(result.current.showDiscardDialog).toBe(false);

      await act(async () => {
        bulk.resolve({ deletedCount: 1, updatedCount: 1 });
        await bulk.promise;
      });
      await waitFor(() => expect(result.current.isApplyingBulkAction).toBe(false));
      act(() => result.current.requestNavigate(navigate));
      expect(navigate).toHaveBeenCalledOnce();
    },
  );
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
