import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { type ComponentProps } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { TranslationEditorHandle } from '../../components/TranslationEditorHandle';
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
  sourceCreatedDate: '2026-05-01T10:15:00Z',
  translationCreatedDate: '2026-05-02T11:30:00Z',
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
  const translationInputRef: { current: TranslationEditorHandle | null } = { current: null };
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
    showDateMetadata: true,
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

function getDetailsButton() {
  return screen.getByRole('button', { name: 'Details' });
}

describe('WorkbenchBody', () => {
  beforeEach(() => {
    noop.mockClear();
    vi.spyOn(window, 'open').mockImplementation(() => null);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

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
      expect(protectedToken).toHaveTextContent('price');
      expect(protectedToken).toHaveClass('visible-text-editor__protected-token--icu-placeholder');
    });
  });

  it('routes an MF2 source to the structured editor', async () => {
    const mf2Row: WorkbenchRow = {
      ...editingRow,
      messageFormat: 'MF2',
      source: `.input {$count :number}
{{You have {$count} files.}}`,
      translation: `.input {$count :number}
.match $count
one {{Você tem {$count} arquivo.}}
* {{Você tem {$count} arquivos.}}`,
    };
    renderWorkbenchBody({
      editingValue: mf2Row.translation ?? '',
      rows: [mf2Row],
    });

    expect(await screen.findByRole('textbox', { name: 'Target count: one' })).toHaveClass(
      'mf2-pm-view',
    );
    expect(screen.getByText('Source contract')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Raw' })).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Text editor' })).not.toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Accept' })).toBeEnabled();
    });
  });

  it('blocks an initially invalid MF2 target from being accepted', async () => {
    const onSaveEditing = vi.fn();
    const mf2Row: WorkbenchRow = {
      ...editingRow,
      messageFormat: 'MF2',
      source: `.input {$count :number}
{{You have {$count} files.}}`,
      translation: `.input {$count :number}
{{Você tem {$rogue} arquivos.}}`,
    };
    renderWorkbenchBody({
      editingValue: mf2Row.translation ?? '',
      onSaveEditing,
      rows: [mf2Row],
    });

    const editor = await screen.findByRole('textbox', { name: 'Target Message' });
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Accept' })).toBeDisabled();
    });

    fireEvent.keyDown(editor, { key: 'Enter', ctrlKey: true });

    expect(onSaveEditing).not.toHaveBeenCalled();
  });

  it('opens ICU form controls from the active translation row', async () => {
    const pluralRow: WorkbenchRow = {
      ...editingRow,
      source: 'Delete {count, plural, one {# app} other {# apps}}',
      translation: 'Supprimer {count, plural, one {# application} other {# applications}}',
    };
    const { container } = renderWorkbenchBody({
      rows: [pluralRow],
      editingValue: pluralRow.translation ?? '',
    });

    expect(await screen.findByRole('textbox', { name: 'Text editor' })).toHaveClass('ProseMirror');

    await waitFor(() => {
      expect(
        container.querySelector('.visible-text-editor__icu-inline-message'),
      ).toBeInTheDocument();
    });
    const formTrigger = container.querySelector<HTMLElement>(
      '.visible-text-editor__protected-token--icu-form-trigger',
    );
    expect(formTrigger).toBeInTheDocument();

    fireEvent.click(formTrigger as HTMLElement);

    expect(screen.getByRole('menu', { name: 'count plural forms: 2/6' })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'zero' })).not.toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'one' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'other' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'other' })).toBeDisabled();
  });

  it('renders source and translation created dates in row metadata', () => {
    const { container } = renderWorkbenchBody({
      editingRowId: null,
      editingValue: '',
    });

    expect(screen.getByLabelText('Text unit dates')).toHaveTextContent('Created');
    expect(screen.getByLabelText('Text unit dates')).toHaveTextContent('Translated');
    expect(container.querySelector('time[datetime="2026-05-01T10:15:00Z"]')).toBeInTheDocument();
    expect(container.querySelector('time[datetime="2026-05-02T11:30:00Z"]')).toBeInTheDocument();
  });

  it('hides source and translation created dates when date metadata is off', () => {
    renderWorkbenchBody({
      editingRowId: null,
      editingValue: '',
      showDateMetadata: false,
    });

    expect(screen.queryByLabelText('Text unit dates')).not.toBeInTheDocument();
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
    expect(protectedToken).toHaveTextContent('price');
    expect(protectedToken).toHaveClass('visible-text-editor__protected-token--icu-placeholder');

    fireEvent.focus(renderer);
    expect(handleStartEditing).toHaveBeenCalledWith(editingRow.id, editingRow.translation);
  });

  it('groups ICU messages in inactive Workbench renderers', () => {
    const pluralRow: WorkbenchRow = {
      ...editingRow,
      source: 'Delete {count, plural, one {# app} other {# apps}}',
      translation: 'Supprimer {count, plural, one {# application} other {# applications}}',
    };
    const { container } = renderWorkbenchBody({
      editingRowId: null,
      editingValue: '',
      rows: [pluralRow],
    });

    expect(screen.getByRole('textbox', { name: 'Text editor' })).toHaveClass(
      'visible-text-renderer',
    );
    expect(container.querySelectorAll('.visible-text-renderer__icu-message')).toHaveLength(2);
    expect(
      container.querySelector(
        '.workbench-page__cell--translation .visible-text-renderer__icu-message',
      ),
    ).toHaveTextContent('count one# applicationother# applications');
  });

  it('renders source placeholders with the same protected token highlighting', () => {
    const { container } = renderWorkbenchBody({
      editingRowId: null,
      editingValue: '',
      rows: [
        {
          ...editingRow,
          source: 'Delete {count} apps',
          translation: 'Supprimer les apps',
        },
      ],
    });

    const sourceToken = container.querySelector(
      '.workbench-page__source-text .visible-text-editor__protected-token',
    );
    expect(sourceToken).toHaveTextContent('count');
    expect(sourceToken).toHaveClass('visible-text-editor__protected-token--icu-placeholder');
  });

  it('renders source text without placeholder highlights when protected token display is off', () => {
    const { container } = renderWorkbenchBody({
      editingRowId: null,
      editingValue: '',
      showProtectedTokens: false,
      rows: [
        {
          ...editingRow,
          source: 'Delete {count} apps',
          translation: 'Supprimer les apps',
        },
      ],
    });

    const sourceText = container.querySelector('.workbench-page__source-text');
    expect(sourceText).toHaveTextContent('Delete {count} apps');
    expect(sourceText).toHaveClass('visible-text-renderer');
    expect(
      container.querySelector('.workbench-page__source-text .visible-text-editor__protected-token'),
    ).not.toBeInTheDocument();
  });

  it('keeps source text plain when visible text rendering is disabled', () => {
    const { container } = renderWorkbenchBody({
      editingRowId: null,
      editingValue: '',
      isVisibleTextEditorEnabled: false,
      rows: [
        {
          ...editingRow,
          source: 'Delete {count} apps',
          translation: 'Supprimer les apps',
        },
      ],
    });

    const sourceText = container.querySelector('.workbench-page__source-text');
    expect(sourceText).toHaveTextContent('Delete {count} apps');
    expect(sourceText).not.toHaveClass('visible-text-renderer');
    expect(
      container.querySelector('.workbench-page__source-text .visible-text-editor__protected-token'),
    ).not.toBeInTheDocument();
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

  it('opens Details in a new window', () => {
    renderWorkbenchBody({
      editingRowId: null,
      editingValue: '',
    });

    fireEvent.click(getDetailsButton());

    expect(window.open).toHaveBeenCalledWith(
      '/text-units/3?locale=pt-PT',
      '_blank',
      'noopener,noreferrer',
    );
  });
});
