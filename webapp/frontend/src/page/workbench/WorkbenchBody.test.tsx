import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { type ComponentProps, createRef } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import type { VisibleTextEditorHandle } from '../../components/VisibleTextEditor';
import type { WorkbenchRow } from './workbench-types';
import { WorkbenchBody } from './WorkbenchBody';

vi.mock('../../api/glossaries', () => ({
  fetchGlossaries: vi.fn().mockResolvedValue({ glossaries: [] }),
}));

vi.mock('../../components/virtual/useMeasuredRowRefs', () => ({
  useMeasuredRowRefs: () => ({
    getRowRef: () => () => undefined,
  }),
}));

vi.mock('../../components/virtual/useVirtualRows', () => ({
  useVirtualRows: () => ({
    scrollRef: { current: null },
    virtualizer: {},
    items: [{ index: 0, key: 'row-1', start: 0, end: 100, size: 100, lane: 0 }],
    totalSize: 100,
    scrollToIndex: vi.fn(),
    measureElement: vi.fn(),
  }),
}));

type WorkbenchBodyProps = ComponentProps<typeof WorkbenchBody>;

const noop = vi.fn();

const editingRow: WorkbenchRow = {
  id: 'row-1',
  textUnitName: 'checkout.pay',
  repositoryName: 'web',
  assetPath: 'checkout.json',
  locations: [],
  locale: 'pt-PT',
  localeId: 17,
  source: 'Pay {price} now',
  translation: 'Pay {price} now',
  status: 'TRANSLATED',
  comment: null,
  tmTextUnitId: 3,
  tmTextUnitVariantId: 30,
  tmTextUnitCurrentVariantId: 30,
  isUsed: true,
  canEdit: true,
};

function renderWorkbenchBody(overrides: Partial<WorkbenchBodyProps> = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  });
  const translationInputRef = createRef<VisibleTextEditorHandle>();
  const props: WorkbenchBodyProps = {
    rows: [editingRow],
    editingRowId: editingRow.id,
    editingValue: 'Pay {price} now',
    editedRowIds: new Set(),
    statusSavingRowIds: new Set(),
    onShowDiff: noop,
    onStartEditing: noop,
    onCancelEditing: noop,
    onSaveEditing: noop,
    canSaveEditing: true,
    onChangeEditingValue: noop,
    onChangeStatus: noop,
    statusOptions: ['TRANSLATED', 'NEEDS_REVIEW'],
    translationInputRef,
    registerRowRef: noop,
    isSaving: false,
    saveErrorMessage: null,
    isRepositoryLoading: false,
    repositoryErrorMessage: null,
    canSearch: true,
    isSearchLoading: false,
    searchErrorMessage: null,
    onRetrySearch: noop,
    hasSearched: true,
    activeSearchRequest: null,
    repositories: [],
    onAddToCollection: noop,
    onRemoveFromCollection: noop,
    activeCollectionIds: new Set(),
    activeCollectionName: null,
    glossaryContext: null,
    restoreScrollTop: null,
    restoreRowId: null,
    onRestoreScrollConsumed: noop,
    isVisibleTextEditorEnabled: true,
    translationMarksMode: 'auto',
    onChangeTranslationMarksMode: noop,
    showProtectedTokens: true,
    ...overrides,
  };

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <WorkbenchBody {...props} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('WorkbenchBody', () => {
  it('uses the assisted protected editor for the active translation row', async () => {
    const { container } = renderWorkbenchBody();

    expect(await screen.findByRole('textbox', { name: 'Text editor' })).toHaveClass('ProseMirror');
    expect(screen.getByRole('button', { name: 'Hidden characters: Auto' })).toBeInTheDocument();
    expect(
      screen.getByRole('button', {
        name: 'Placeholder editing is off. Edit placeholders',
      }),
    ).toBeInTheDocument();
    expect(screen.queryByText('1 token found')).not.toBeInTheDocument();

    await waitFor(() => {
      const protectedToken = container.querySelector('.visible-text-editor__protected-token');
      expect(protectedToken).toHaveTextContent('{price}');
      expect(protectedToken).toHaveClass('visible-text-editor__protected-token--icu-placeholder');
    });
  });

  it('uses a lightweight protected renderer for inactive translation rows', () => {
    const handleStartEditing = vi.fn();
    const { container } = renderWorkbenchBody({
      editingRowId: null,
      editingValue: '',
      onStartEditing: handleStartEditing,
    });

    const renderer = screen.getByRole('textbox', { name: 'Text editor' });
    expect(renderer).toHaveClass('visible-text-renderer');
    expect(container.querySelector('.ProseMirror')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Hidden characters: Auto' }),
    ).not.toBeInTheDocument();

    const protectedToken = container.querySelector('.visible-text-editor__protected-token');
    expect(protectedToken).toHaveTextContent('{price}');
    expect(protectedToken).toHaveClass('visible-text-editor__protected-token--icu-placeholder');

    fireEvent.focus(renderer);
    expect(handleStartEditing).toHaveBeenCalledWith(editingRow.id, editingRow.translation);
  });

  it('can render inactive translation rows without protected token highlights', () => {
    const { container } = renderWorkbenchBody({
      editingRowId: null,
      editingValue: '',
      showProtectedTokens: false,
    });

    expect(screen.getByRole('textbox', { name: 'Text editor' })).toHaveClass(
      'visible-text-renderer',
    );
    expect(container).toHaveTextContent('Pay {price} now');
    expect(
      container.querySelector('.visible-text-editor__protected-token'),
    ).not.toBeInTheDocument();
  });
});
