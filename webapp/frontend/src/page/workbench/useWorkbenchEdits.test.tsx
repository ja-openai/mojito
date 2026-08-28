import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import type { PropsWithChildren } from 'react';
import { describe, expect, it, vi } from 'vitest';

import type { WorkbenchRow } from './workbench-types';

const mf2TranslationErrorCountMock = vi.hoisted(() => vi.fn(() => 0));

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
