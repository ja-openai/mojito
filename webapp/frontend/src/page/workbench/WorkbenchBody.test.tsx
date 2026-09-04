import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { type ComponentProps } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { TranslationEditorHandle } from '../../components/TranslationEditorHandle';
import { mapApiTextUnitToRow } from './workbench-helpers';
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

const virtualRowsMock = vi.hoisted(() => ({
  visibleIndices: null as number[] | null,
  scrollToIndex: vi.fn(),
  measureElement: vi.fn(),
}));

vi.mock('../../components/virtual/useVirtualRows', () => ({
  useVirtualRows: ({ count }: { count: number }) => ({
    scrollRef: { current: null },
    virtualizer: {},
    items: Array.from({ length: count }, (_, index) => index)
      .filter((index) => virtualRowsMock.visibleIndices?.includes(index) ?? true)
      .map((index) => ({
        index,
        key: `row-${index + 1}`,
        start: index * 100,
        end: (index + 1) * 100,
        size: 100,
        lane: 0,
      })),
    totalSize: count * 100,
    scrollToIndex: virtualRowsMock.scrollToIndex,
    measureElement: virtualRowsMock.measureElement,
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
  translationCreatedByUsername: 'translator@example.com',
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
    onOpenDetails: noop,
    isVisibleTextEditorEnabled: true,
    translationMarksMode: 'auto',
    showProtectedTokens: true,
    showDateMetadata: true,
    showSavedBy: false,
    ...overrides,
  };

  const content = (currentProps: WorkbenchBodyProps) => (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <WorkbenchBody {...currentProps} />
      </MemoryRouter>
    </QueryClientProvider>
  );
  const result = render(content(props));
  return {
    ...result,
    updateProps: (next: Partial<WorkbenchBodyProps>) =>
      result.rerender(content({ ...props, ...next })),
  };
}

function getDetailsLink() {
  return screen.getByRole('link', { name: 'Details' });
}

describe('WorkbenchBody', () => {
  beforeEach(() => {
    noop.mockClear();
    virtualRowsMock.visibleIndices = null;
    virtualRowsMock.scrollToIndex.mockReset();
    virtualRowsMock.measureElement.mockReset();
    vi.spyOn(window, 'open').mockImplementation(() => null);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('uses the assisted protected editor for the active translation row', async () => {
    const { container } = renderWorkbenchBody();

    expect(await screen.findByRole('textbox', { name: 'Text editor' })).toHaveClass('ProseMirror');
    expect(screen.getByRole('button', { name: 'Characters' })).toBeVisible();
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

  it.each([false, true])(
    'limits inline hidden-character changes to the active target (MF2: %s)',
    async (mf2) => {
      const row: WorkbenchRow = {
        ...editingRow,
        ...(mf2 ? { messageFormat: 'MF2' as const } : {}),
        source: 'Hello world',
        translation: 'Bonjour monde',
      };
      const onChangeEditingValue = vi.fn();
      const { container } = renderWorkbenchBody({
        rows: [row, { ...row, id: 'row-2', tmTextUnitId: 4 }],
        editingValue: row.translation ?? '',
        onChangeEditingValue,
      });
      const editor = await within(
        container.querySelector('[data-editing="true"]') as HTMLElement,
      ).findByRole('textbox', {
        name: mf2 ? 'Target Message' : 'Text editor',
      });
      const markedSpaces = '.visible-text-editor__marked-char--space';
      expect(container.querySelectorAll(markedSpaces)).toHaveLength(0);

      fireEvent.click(screen.getByRole('button', { name: 'Hidden characters: Auto' }));
      fireEvent.click(screen.getByRole('option', { name: 'All' }));

      await waitFor(() => expect(editor.querySelectorAll(markedSpaces)).toHaveLength(1));
      expect(container.querySelectorAll(markedSpaces)).toHaveLength(1);
      expect(editor).toHaveFocus();
      expect(editor.textContent).toBe(row.translation);
      expect(onChangeEditingValue).not.toHaveBeenCalled();

      fireEvent.click(screen.getByRole('button', { name: 'Hidden characters: All' }));
      fireEvent.click(screen.getByRole('option', { name: 'Off' }));

      await waitFor(() => expect(container.querySelectorAll(markedSpaces)).toHaveLength(0));
      expect(editor.textContent).toBe(row.translation);
      expect(onChangeEditingValue).not.toHaveBeenCalled();
    },
  );

  it('resets inline hidden-character mode when switching or reopening a string', async () => {
    const secondRow = { ...editingRow, id: 'row-2', tmTextUnitId: 4 };
    const { container, updateProps } = renderWorkbenchBody({
      rows: [editingRow, secondRow],
    });
    await within(container.querySelector('[data-editing="true"]') as HTMLElement).findByRole(
      'textbox',
      { name: 'Text editor' },
    );
    fireEvent.click(screen.getByRole('button', { name: 'Hidden characters: Auto' }));
    fireEvent.click(screen.getByRole('option', { name: 'All' }));
    expect(screen.getByRole('button', { name: 'Hidden characters: All' })).toBeInTheDocument();

    updateProps({ editingRowId: secondRow.id });

    expect(
      await screen.findByRole('button', { name: 'Hidden characters: Auto' }),
    ).toBeInTheDocument();
    expect(container.querySelectorAll('.visible-text-editor__marked-char--space')).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: 'Hidden characters: Auto' }));
    fireEvent.click(screen.getByRole('option', { name: 'All' }));

    updateProps({ editingRowId: null });

    expect(
      screen.queryByRole('button', { name: 'Hidden characters: All' }),
    ).not.toBeInTheDocument();
    expect(container.querySelectorAll('.visible-text-editor__marked-char--space')).toHaveLength(0);

    updateProps({ editingRowId: secondRow.id });

    expect(
      await screen.findByRole('button', { name: 'Hidden characters: Auto' }),
    ).toBeInTheDocument();
    expect(container.querySelectorAll('.visible-text-editor__marked-char--space')).toHaveLength(0);
  });

  it('applies the global display mode to previews and resets the active editor override', async () => {
    const row = { ...editingRow, source: 'Hello world', translation: 'Bonjour monde' };
    const { container, updateProps } = renderWorkbenchBody({
      rows: [row, { ...row, id: 'row-2', tmTextUnitId: 4 }],
      editingValue: row.translation,
      translationMarksMode: 'all',
    });
    const editor = await within(
      container.querySelector('[data-editing="true"]') as HTMLElement,
    ).findByRole('textbox', { name: 'Text editor' });
    const markedSpaces = '.visible-text-editor__marked-char--space';
    expect(container.querySelectorAll(markedSpaces)).toHaveLength(4);

    fireEvent.click(screen.getByRole('button', { name: 'Hidden characters: All' }));
    fireEvent.click(screen.getByRole('option', { name: 'Off' }));

    await waitFor(() => expect(editor.querySelectorAll(markedSpaces)).toHaveLength(0));
    expect(container.querySelectorAll(markedSpaces)).toHaveLength(3);

    updateProps({ translationMarksMode: 'auto' });

    expect(
      await screen.findByRole('button', { name: 'Hidden characters: Auto' }),
    ).toBeInTheDocument();
    expect(container.querySelectorAll(markedSpaces)).toHaveLength(0);

    updateProps({ translationMarksMode: 'all' });

    expect(
      await screen.findByRole('button', { name: 'Hidden characters: All' }),
    ).toBeInTheDocument();
    expect(editor.querySelectorAll(markedSpaces)).toHaveLength(1);
    expect(container.querySelectorAll(markedSpaces)).toHaveLength(4);
  });

  it('routes an MF2 source to the structured editor', async () => {
    const mf2Row: WorkbenchRow = {
      ...editingRow,
      comment: 'Shown to translators',
      messageFormat: 'MF2',
      source: `.input {$count :number}
{{You have {$count} files.}}`,
      translation: `.input {$count :number}
.match $count
one {{Você tem {$count} arquivo.}}
* {{Você tem {$count} arquivos.}}`,
    };
    const { container } = renderWorkbenchBody({
      editingValue: mf2Row.translation ?? '',
      rows: [mf2Row],
    });

    expect(await screen.findByRole('textbox', { name: 'Target count: one' })).toHaveClass(
      'mf2-pm-view',
    );
    expect(screen.queryByText('Variables')).not.toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'MF2 source' })).toHaveTextContent(
      '.input {$count :number}',
    );
    expect(screen.getByText('Shown to translators')).toBeInTheDocument();
    expect(container.querySelector('.mf2-active-source-comparison')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hidden characters: Auto' })).toBeInTheDocument();
    expect(
      screen.getByRole('button', {
        name: 'Placeholder editing is off. Edit placeholders',
      }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Text editor' })).not.toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Accept' })).toBeEnabled();
    });
  });

  it('shows the serialized MF2 document instead of one resolved form on collapsed rows', () => {
    const mf2Row: WorkbenchRow = {
      ...editingRow,
      messageFormat: 'MF2',
      source: `.input {$status :string}
.match $status
active {{Active}}
paused {{Paused}}
* {{Unknown}}`,
      translation: `.input {$status :string}
.match $status
active {{Actif}}
paused {{En pause}}
* {{Inconnu}}`,
    };
    const onStartEditing = vi.fn();
    const { container } = renderWorkbenchBody({
      editingRowId: null,
      editingValue: '',
      onStartEditing,
      rows: [mf2Row],
    });

    const sourcePreview = screen.getByRole('textbox', { name: 'MF2 source' });
    const translationPreview = screen.getByRole('textbox', {
      name: 'MF2 translation editor',
    });
    expect(sourcePreview).toHaveAttribute('aria-multiline', 'true');
    expect(translationPreview).toHaveAttribute('aria-multiline', 'true');
    expect(sourcePreview).toHaveTextContent('.input {$status :string}');
    expect(sourcePreview).toHaveTextContent('.match $status');
    expect(sourcePreview).toHaveTextContent('active {{Active}}');
    expect(sourcePreview).toHaveTextContent('paused {{Paused}}');
    expect(sourcePreview).toHaveTextContent('fallback {{Unknown}}');
    expect(translationPreview).toHaveTextContent('.input {$status :string}');
    expect(translationPreview).toHaveTextContent('.match $status');
    expect(translationPreview).toHaveTextContent('active {{Actif}}');
    expect(translationPreview).toHaveTextContent('paused {{En pause}}');
    expect(translationPreview).toHaveTextContent('fallback {{Inconnu}}');
    [sourcePreview, translationPreview].forEach((preview) => {
      const fallback = within(preview).getByLabelText('MF2 fallback selector');
      expect(fallback).toHaveAttribute('data-raw', '*');
      expect(fallback).toHaveTextContent('fallback');
      expect(fallback).toHaveClass('visible-text-editor__protected-token--mf2-syntax');
      expect(fallback).not.toHaveClass('visible-text-editor__protected-token--mf2-placeholder');
      expect(within(preview).queryByLabelText('MF2 variable status')).not.toBeInTheDocument();
      expect(preview).not.toHaveTextContent('* fallback');
    });
    expect(container.querySelector('.workbench-page__mf2-badge')).not.toBeInTheDocument();

    fireEvent.focus(translationPreview);
    expect(onStartEditing).toHaveBeenCalledWith(mf2Row.id, mf2Row.translation);
  });

  it('opens a collapsed status row into guided bodies and preserves exact Raw syntax', async () => {
    const source = `.input {$status :string}\n.match $status\nactive {{Active}}\npaused {{Paused}}\n* {{Unknown}}`;
    const target = `.input {$status :string}\n.match $status\nactive {{Actif}}\npaused {{En pause}}\n* {{Inconnu}}`;
    const row = { ...editingRow, source, translation: target, messageFormat: 'MF2' as const };
    const onStartEditing = vi.fn();
    const { updateProps } = renderWorkbenchBody({
      rows: [row],
      editingRowId: null,
      onStartEditing,
    });
    const collapsed = screen.getByRole('textbox', { name: 'MF2 translation editor' });
    expect(collapsed.querySelector('.mf2-document-preview--structured')).toBeInTheDocument();
    expect(collapsed).toHaveTextContent('paused {{En pause}}');
    expect(collapsed).toHaveAccessibleDescription(/Shaded syntax is protected/u);
    fireEvent.focus(collapsed);
    expect(onStartEditing).toHaveBeenCalledWith(row.id, target);
    updateProps({ rows: [row], editingRowId: row.id, editingValue: target });
    const guided = await screen.findByRole('textbox', { name: 'Target status: active' });
    expect(guided).toHaveTextContent('Actif');
    expect(guided).toHaveAttribute('contenteditable', 'true');
    expect(guided).not.toHaveTextContent('.match');
    expect(screen.getByRole('textbox', { name: 'MF2 source' })).toHaveTextContent(
      'paused {{Paused}}',
    );
    fireEvent.click(
      screen.getByRole('button', { name: 'Placeholder editing is off. Edit placeholders' }),
    );
    await waitFor(() => {
      const raw = document.querySelector('.cm-content');
      expect(raw?.textContent).toBe(target.split('\n').join(''));
    });
    fireEvent.click(
      screen.getByRole('button', { name: 'Placeholder editing is on. Lock placeholders' }),
    );
    expect(await screen.findByRole('textbox', { name: 'Target status: active' })).toHaveTextContent(
      'Actif',
    );
  });

  it('leaves an untranslated MF2 target empty on collapsed rows', () => {
    const mf2Row: WorkbenchRow = {
      ...editingRow,
      messageFormat: 'MF2',
      source: 'Hello {$name}.',
      translation: null,
    };
    renderWorkbenchBody({
      editingRowId: null,
      editingValue: '',
      rows: [mf2Row],
    });

    expect(screen.getByRole('textbox', { name: 'MF2 translation editor' })).toHaveTextContent(
      /^$/u,
    );
  });

  it('protects MF2 variables without hiding their serialized syntax', () => {
    const mf2Row: WorkbenchRow = {
      ...editingRow,
      messageFormat: 'MF2',
      source: `.input {$count :number}
{{You have {$count} files.}}`,
      translation: `.input {$count :number}
{{Você tem {$count} arquivos.}}`,
    };
    renderWorkbenchBody({
      editingRowId: null,
      editingValue: '',
      rows: [mf2Row],
    });

    const sourcePreview = screen.getByRole('textbox', { name: 'MF2 source' });
    const variables = within(sourcePreview).getAllByLabelText('MF2 variable count');
    expect(variables).toHaveLength(1);
    expect(variables[0]).toHaveAttribute('data-raw', '{$count}');
    variables.forEach((variable) => {
      expect(variable).toHaveClass('visible-text-editor__protected-token--mf2-placeholder');
      expect(variable.textContent).toBe(variable.getAttribute('data-raw'));
    });
  });

  it('keeps raw MF2 serialization on collapsed rows when the assisted editor is off', () => {
    const onStartEditing = vi.fn();
    const mf2Row: WorkbenchRow = {
      ...editingRow,
      messageFormat: 'MF2',
      source: `.input {$count :number}
{{You have {$count} files.}}`,
      translation: `.input {$count :number}
{{Você tem {$count} arquivos.}}`,
    };
    const { container } = renderWorkbenchBody({
      editingRowId: null,
      editingValue: '',
      isVisibleTextEditorEnabled: false,
      onStartEditing,
      rows: [mf2Row],
    });

    expect(screen.queryByRole('textbox', { name: 'MF2 source' })).not.toBeInTheDocument();
    expect(container.querySelector('.workbench-page__source-text')?.textContent).toBe(
      mf2Row.source,
    );
    const editor = screen.getByRole('textbox', { name: 'Text editor' });
    expect(editor).toHaveValue(mf2Row.translation);
    fireEvent.focus(editor);
    expect(onStartEditing).toHaveBeenCalledWith(mf2Row.id, mf2Row.translation);
  });

  it('uses the native raw editor for active MF2 rows when the assisted editor is off', () => {
    const mf2Row: WorkbenchRow = {
      ...editingRow,
      messageFormat: 'MF2',
      source: `.input {$count :number}
{{You have {$count} files.}}`,
      translation: `.input {$count :number}
{{Você tem {$count} arquivos.}}`,
    };
    renderWorkbenchBody({
      editingValue: mf2Row.translation ?? '',
      isVisibleTextEditorEnabled: false,
      rows: [mf2Row],
    });

    const editor = screen.getByRole('textbox', { name: 'Text editor' });
    expect(editor.tagName).toBe('TEXTAREA');
    expect(editor).toHaveValue(mf2Row.translation);
    expect(screen.getByRole('button', { name: 'Characters' })).toBeVisible();
    expect(
      screen.queryByRole('button', {
        name: 'Placeholder editing is off. Edit placeholders',
      }),
    ).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: /Target count:/u })).not.toBeInTheDocument();
  });

  it('keeps raw MF2 serialization on collapsed rows when placeholder highlights are off', () => {
    const mf2Row: WorkbenchRow = {
      ...editingRow,
      messageFormat: 'MF2',
      source: `.input {$count :number}
{{You have {$count} files.}}`,
      translation: `.input {$count :number}
{{Você tem {$count} arquivos.}}`,
    };
    const { container } = renderWorkbenchBody({
      editingRowId: null,
      editingValue: '',
      rows: [mf2Row],
      showProtectedTokens: false,
    });

    expect(screen.queryByRole('textbox', { name: 'MF2 source' })).not.toBeInTheDocument();
    expect(container.querySelector('.workbench-page__source-text')?.textContent).toBe(
      mf2Row.source,
    );
    expect(screen.getByRole('textbox', { name: 'Text editor' })).toHaveValue(mf2Row.translation);
  });

  it('uses the native raw editor for active MF2 rows when placeholder highlights are off', () => {
    const mf2Row: WorkbenchRow = {
      ...editingRow,
      messageFormat: 'MF2',
      source: `.input {$count :number}
{{You have {$count} files.}}`,
      translation: `.input {$count :number}
{{Você tem {$count} arquivos.}}`,
    };
    renderWorkbenchBody({
      editingValue: mf2Row.translation ?? '',
      rows: [mf2Row],
      showProtectedTokens: false,
    });

    const editor = screen.getByRole('textbox', { name: 'Text editor' });
    expect(editor.tagName).toBe('TEXTAREA');
    expect(editor).toHaveValue(mf2Row.translation);
    expect(
      screen.queryByRole('button', {
        name: 'Placeholder editing is off. Edit placeholders',
      }),
    ).not.toBeInTheDocument();
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

  it('toggles saved-by metadata independently of dates and the assisted editor', () => {
    const row = mapApiTextUnitToRow({
      tmTextUnitId: 3,
      name: 'checkout.pay',
      targetLocale: 'pt-PT',
      target: 'Pagar agora',
      translationCreatedByUsername: ' translator@example.com ',
      used: true,
    });
    const { updateProps } = renderWorkbenchBody({
      rows: [row],
      showDateMetadata: false,
      isVisibleTextEditorEnabled: false,
    });

    expect(screen.queryByText('Saved by')).not.toBeInTheDocument();
    updateProps({ showSavedBy: true });
    expect(screen.getByText('Saved by')).toBeInTheDocument();
    expect(screen.getByText('translator@example.com')).toBeInTheDocument();
    expect(screen.queryByLabelText('Text unit dates')).not.toBeInTheDocument();
    updateProps({ showSavedBy: false });
    expect(screen.queryByText('translator@example.com')).not.toBeInTheDocument();
  });

  it.each([null, '', '   '])('shows unknown for missing saver metadata (%j)', (username) => {
    renderWorkbenchBody({
      rows: [{ ...editingRow, translation: '', translationCreatedByUsername: username }],
      showSavedBy: true,
    });

    expect(screen.getByText('Saved by')).toBeInTheDocument();
    expect(screen.getByText('unknown')).toBeInTheDocument();
  });

  it('does not attribute untranslated rows', () => {
    renderWorkbenchBody({
      rows: [{ ...editingRow, translation: null }],
      showSavedBy: true,
    });

    expect(screen.queryByText('Saved by')).not.toBeInTheDocument();
    expect(screen.queryByText('translator@example.com')).not.toBeInTheDocument();
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

  it('opens Details in the current workbench session with its scroll position', () => {
    const onOpenDetails = vi.fn();
    const { container } = renderWorkbenchBody({
      editingRowId: null,
      editingValue: '',
      onOpenDetails,
    });
    const scrollElement = container.querySelector('.virtual-scroll') as HTMLElement;
    scrollElement.scrollTop = 425;
    const link = getDetailsLink();
    const click = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 });

    expect(link).toHaveAttribute('href', '/text-units/3?locale=pt-PT');
    expect(link).not.toHaveAttribute('target');
    fireEvent(link, click);

    expect(click.defaultPrevented).toBe(true);
    expect(onOpenDetails).toHaveBeenCalledExactlyOnceWith(editingRow, 425);
    expect(window.open).not.toHaveBeenCalled();
  });

  it.each([
    ['Ctrl', { ctrlKey: true }],
    ['Meta', { metaKey: true }],
    ['Shift', { shiftKey: true }],
    ['Alt', { altKey: true }],
    ['middle button', { button: 1 }],
  ] as const)('preserves native Details navigation for %s clicks', (_name, modifiers) => {
    const onOpenDetails = vi.fn();
    renderWorkbenchBody({ editingRowId: null, onOpenDetails });
    const click = new MouseEvent('click', {
      bubbles: true,
      cancelable: true,
      button: 0,
      ...modifiers,
    });

    // Do not execute jsdom's deferred, unsupported cross-document navigation.
    vi.useFakeTimers();
    fireEvent(getDetailsLink(), click);

    expect(click.defaultPrevented).toBe(false);
    expect(onOpenDetails).not.toHaveBeenCalled();
    expect(window.open).not.toHaveBeenCalled();
    vi.clearAllTimers();
  });

  it('waits for a virtualized return row before consuming restoration and focusing Details', () => {
    const targetRow = { ...editingRow, id: 'row-2', tmTextUnitId: 4 };
    const onRestoreScrollConsumed = vi.fn();
    const onStartEditing = vi.fn();
    const frames = new Map<number, FrameRequestCallback>();
    let nextFrame = 0;
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
      frames.set(++nextFrame, callback);
      return nextFrame;
    });
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation((id) => frames.delete(id));
    const flushFrames = () =>
      act(() => {
        const callbacks = [...frames.values()];
        frames.clear();
        callbacks.forEach((callback) => callback(0));
      });
    virtualRowsMock.visibleIndices = [0];
    virtualRowsMock.scrollToIndex.mockImplementation((index: number) => {
      virtualRowsMock.visibleIndices = [index];
    });
    const { container, updateProps } = renderWorkbenchBody({
      rows: [editingRow, targetRow],
      editingRowId: null,
      restoreRowId: targetRow.id,
      restoreScrollTop: 625,
      onRestoreScrollConsumed,
      onStartEditing,
    });

    expect(virtualRowsMock.scrollToIndex).toHaveBeenCalledWith(1, { align: 'center' });
    expect(container.querySelector('[data-workbench-row-id="row-2"]')).toBeNull();
    flushFrames();
    expect(onRestoreScrollConsumed).not.toHaveBeenCalled();

    updateProps({});
    const target = container.querySelector('[data-workbench-row-id="row-2"]') as HTMLElement;
    const scrollIntoView = vi.fn();
    target.scrollIntoView = scrollIntoView;
    expect(onRestoreScrollConsumed).not.toHaveBeenCalled();
    flushFrames();

    expect((container.querySelector('.virtual-scroll') as HTMLElement).scrollTop).toBe(625);
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' });
    expect(within(target).getByRole('link', { name: 'Details' })).toHaveFocus();
    expect(onStartEditing).not.toHaveBeenCalled();
    expect(onRestoreScrollConsumed).toHaveBeenCalledOnce();
  });

  it('restores the saved scroll position when the return row is no longer in the results', async () => {
    const onRestoreScrollConsumed = vi.fn();
    const onStartEditing = vi.fn();
    const { container, updateProps } = renderWorkbenchBody({
      editingRowId: null,
      restoreRowId: 'removed-row',
      restoreScrollTop: 425,
      onRestoreScrollConsumed,
      onStartEditing,
    });
    onRestoreScrollConsumed.mockImplementation(() =>
      updateProps({ restoreRowId: null, restoreScrollTop: null }),
    );

    await waitFor(() => expect(onRestoreScrollConsumed).toHaveBeenCalledOnce());

    expect((container.querySelector('.virtual-scroll') as HTMLElement).scrollTop).toBe(425);
    expect(virtualRowsMock.scrollToIndex).not.toHaveBeenCalled();
    expect(getDetailsLink()).not.toHaveFocus();
    expect(onStartEditing).not.toHaveBeenCalled();
  });
});
