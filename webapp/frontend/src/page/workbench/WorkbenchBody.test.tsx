import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { type ComponentProps, createRef } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  type ApiInflectionBindingReport,
  fetchGlossaries,
  matchGlossaryTerms,
  renderInflectionBindingManifest,
  reportInflectionBindingManifest,
} from '../../api/glossaries';
import type { VisibleTextEditorHandle } from '../../components/VisibleTextEditor';
import type { WorkbenchRow } from './workbench-types';
import { WorkbenchBody } from './WorkbenchBody';

vi.mock('../../api/glossaries', () => ({
  fetchGlossaries: vi.fn().mockResolvedValue({ glossaries: [] }),
  matchGlossaryTerms: vi.fn().mockResolvedValue({ matchedTerms: [] }),
  renderInflectionBindingManifest: vi.fn().mockResolvedValue({
    locale: 'fr-FR',
    messages: {},
  }),
  reportInflectionBindingManifest: vi.fn().mockResolvedValue({
    schema: 'mojito-mf2-inflection/term-binding-report/v0',
    locale: 'fr-FR',
    summary: { messages: 0, requiredArguments: 0, diagnostics: 0 },
    diagnostics: [],
    messages: {},
  }),
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
    vi.mocked(fetchGlossaries).mockReset();
    vi.mocked(matchGlossaryTerms).mockReset();
    vi.mocked(renderInflectionBindingManifest).mockReset();
    vi.mocked(reportInflectionBindingManifest).mockReset();
    vi.mocked(fetchGlossaries).mockResolvedValue({ glossaries: [], totalCount: 0 });
    vi.mocked(matchGlossaryTerms).mockResolvedValue({ matchedTerms: [] });
    vi.mocked(renderInflectionBindingManifest).mockResolvedValue({
      locale: 'fr-FR',
      messages: {},
    });
    vi.mocked(reportInflectionBindingManifest).mockResolvedValue(emptyBindingReport('fr-FR'));
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
      expect(protectedToken).toHaveTextContent('{price}');
      expect(protectedToken).toHaveClass('visible-text-editor__protected-token--icu-placeholder');
    });
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
    expect(protectedToken).toHaveTextContent('{price}');
    expect(protectedToken).toHaveClass('visible-text-editor__protected-token--icu-placeholder');

    fireEvent.focus(renderer);
    expect(handleStartEditing).toHaveBeenCalledWith(editingRow.id, editingRow.translation);
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
    expect(sourceToken).toHaveTextContent('{count}');
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

  it('surfaces MF2 term requirements from the source and current translation text', () => {
    renderWorkbenchBody({
      rows: [
        {
          ...editingRow,
          locale: 'fr-FR',
          source: 'Deleted {$item :term article=definite count=$count}.',
        },
      ],
      editingValue: 'Supprimé {$item :term article=definite count=$count}.',
    });

    expect(
      screen.getByText(/MF2 term requirements: source \$item · target \$item/u),
    ).toBeInTheDocument();
    expect(screen.getAllByText(/forms\.definite\.singular/u).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/forms\.count\.other/u).length).toBeGreaterThan(0);
  });

  it('requests backend MF2 term binding diagnostics for glossary rows', async () => {
    vi.mocked(reportInflectionBindingManifest).mockResolvedValue({
      schema: 'mojito-mf2-inflection/term-binding-report/v0',
      locale: 'fr-FR',
      summary: { messages: 2, requiredArguments: 2, diagnostics: 2 },
      diagnostics: [
        { messageId: 'checkout.pay.source', argument: 'item', status: 'missing', termIds: [] },
        { messageId: 'checkout.pay.target', argument: 'item', status: 'missing', termIds: [] },
      ],
      messages: {},
    });

    renderWorkbenchBody({
      glossaryContext: {
        assetPath: 'checkout.json',
        backingRepositoryId: 10,
        backingRepositoryName: 'web',
        glossaryId: 42,
        glossaryName: 'Product glossary',
      },
      rows: [
        {
          ...editingRow,
          locale: 'fr-FR',
          source: 'Deleted {$item :term article=definite count=$count}.',
        },
      ],
      editingValue: 'Supprimé {$item :term article=definite count=$count}.',
    });

    await waitFor(() => {
      expect(reportInflectionBindingManifest).toHaveBeenCalledWith(
        42,
        'fr-FR',
        expect.objectContaining({
          argumentTerms: {
            'checkout.pay.source': { item: [] },
            'checkout.pay.target': { item: [] },
          },
          locale: 'fr-FR',
          messages: {
            'checkout.pay.source': 'Deleted {$item :term article=definite count=$count}.',
            'checkout.pay.target': 'Supprimé {$item :term article=definite count=$count}.',
          },
          schema: 'mojito-mf2-inflection/message-term-binding-manifest/v0',
        }),
      );
    });
    expect(
      await screen.findByText(
        'MF2 term bindings: 2 missing for $item; bind MF2 arguments to valid glossary term IDs before rendering.',
      ),
    ).toHaveClass('is-warning');
  });

  it('explains unsupported locale runtime term binding diagnostics', async () => {
    vi.mocked(reportInflectionBindingManifest).mockResolvedValue({
      schema: 'mojito-mf2-inflection/term-binding-report/v0',
      locale: 'ja',
      summary: { messages: 2, requiredArguments: 2, diagnostics: 2 },
      diagnostics: [
        {
          messageId: 'checkout.pay.source',
          argument: 'item',
          status: 'unsupported-locale-runtime-term-inflection',
          termIds: [],
        },
        {
          messageId: 'checkout.pay.target',
          argument: 'item',
          status: 'unsupported-locale-runtime-term-inflection',
          termIds: [],
        },
      ],
      messages: {},
    });

    renderWorkbenchBody({
      glossaryContext: {
        assetPath: 'checkout.json',
        backingRepositoryId: 10,
        backingRepositoryName: 'web',
        glossaryId: 42,
        glossaryName: 'Product glossary',
      },
      rows: [
        {
          ...editingRow,
          locale: 'ja',
          source: 'Deleted {$item :term number=plural}.',
        },
      ],
      editingValue: '削除しました {$item :term number=plural}。',
    });

    expect(
      await screen.findByText(
        'MF2 term bindings: 2 unsupported by current V0 locale runtime; remove MF2 :term options for $item or use a locale with a checked V0 runtime term-form pack.',
      ),
    ).toHaveClass('is-warning');
  });

  it('surfaces backend MF2 term binding report errors in the Workbench preview', async () => {
    vi.mocked(reportInflectionBindingManifest).mockRejectedValue(
      new Error('Expected non-blank message id'),
    );

    renderWorkbenchBody({
      glossaryContext: {
        assetPath: 'checkout.json',
        backingRepositoryId: 10,
        backingRepositoryName: 'web',
        glossaryId: 42,
        glossaryName: 'Product glossary',
      },
      rows: [
        {
          ...editingRow,
          locale: 'fr-FR',
          source: 'Deleted {$item :term article=definite count=$count}.',
        },
      ],
      editingValue: 'Supprimé {$item :term article=definite count=$count}.',
    });

    expect(await screen.findByText('MF2 term bindings: Expected non-blank message id')).toHaveClass(
      'is-error',
    );
    expect(renderInflectionBindingManifest).not.toHaveBeenCalled();
  });

  it('binds Hindi pronoun agreement through the related term argument', async () => {
    renderWorkbenchBody({
      glossaryContext: {
        assetPath: 'checkout.json',
        backingRepositoryId: 10,
        backingRepositoryName: 'web',
        glossaryId: 42,
        glossaryName: 'Product glossary',
      },
      rows: [
        {
          ...editingRow,
          locale: 'hi',
          source:
            '{$owner :term person=first case=genitive count=$ownerCount agreeWith=$item agreeWithCount=$itemCount} {$item :term case=direct count=$itemCount}.',
        },
      ],
      editingValue:
        '{$owner :term person=first case=genitive count=$ownerCount agreeWith=$item agreeWithCount=$itemCount} {$item :term case=direct count=$itemCount}.',
    });

    await waitFor(() => {
      expect(reportInflectionBindingManifest).toHaveBeenCalledWith(
        42,
        'hi',
        expect.objectContaining({
          argumentTerms: {
            'checkout.pay.source': { item: [] },
            'checkout.pay.target': { item: [] },
          },
          locale: 'hi',
        }),
      );
    });

    fireEvent.click(screen.getByText(/MF2 term requirements: source \$item/u));

    expect(
      await screen.findByRole('combobox', { name: 'Glossary term ID for item' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'Glossary term ID for owner' })).toBeNull();
    expect(screen.getByRole('textbox', { name: 'Runtime value for ownerCount' })).toBeVisible();
    expect(screen.getByRole('textbox', { name: 'Runtime value for itemCount' })).toBeVisible();
    expect(screen.queryByRole('textbox', { name: 'Runtime value for item' })).toBeNull();
  });

  it('defaults a single MF2 argument to one unambiguous glossary term candidate', async () => {
    vi.mocked(matchGlossaryTerms).mockResolvedValue({
      matchedTerms: [
        {
          glossaryId: 42,
          glossaryName: 'Product glossary',
          tmTextUnitId: 9,
          termKey: 'item.file',
          source: 'file',
          comment: null,
          definition: null,
          partOfSpeech: 'noun',
          termType: 'product term',
          enforcement: 'required',
          status: 'APPROVED',
          provenance: null,
          target: 'fichier',
          targetComment: null,
          doNotTranslate: false,
          caseSensitive: false,
          matchType: 'EXACT',
          startIndex: 8,
          endIndex: 12,
          matchedText: 'file',
          evidence: [],
        },
      ],
    });

    renderWorkbenchBody({
      glossaryContext: {
        assetPath: 'checkout.json',
        backingRepositoryId: 10,
        backingRepositoryName: 'web',
        glossaryId: 42,
        glossaryName: 'Product glossary',
      },
      rows: [
        {
          ...editingRow,
          locale: 'fr-FR',
          source: 'Deleted {$item :term count=$count}.',
        },
      ],
      editingValue: 'Supprimé {$item :term count=$count}.',
    });

    fireEvent.click(screen.getByText(/MF2 term requirements: source \$item/u));

    await waitFor(() => {
      expect(matchGlossaryTerms).toHaveBeenCalledWith({
        repositoryName: 'web',
        localeTag: 'fr-FR',
        sourceText: 'Deleted {$item :term count=$count}.',
        excludeTmTextUnitId: 3,
      });
    });

    const select = await screen.findByRole('combobox', { name: 'Glossary term ID for item' });
    expect(await screen.findByText('item.file — file → fichier')).toBeInTheDocument();
    await waitFor(() => {
      expect(select).toHaveValue('item.file');
    });

    await waitFor(() => {
      expect(reportInflectionBindingManifest).toHaveBeenCalledWith(
        42,
        'fr-FR',
        expect.objectContaining({
          argumentTerms: {
            'checkout.pay.source': { item: ['item.file'] },
            'checkout.pay.target': { item: ['item.file'] },
          },
        }),
      );
    });
  });

  it('does not default MF2 bindings when multiple glossary term candidates match', async () => {
    vi.mocked(matchGlossaryTerms).mockResolvedValue({
      matchedTerms: [
        {
          glossaryId: 42,
          glossaryName: 'Product glossary',
          tmTextUnitId: 9,
          termKey: 'item.file',
          source: 'file',
          comment: null,
          definition: null,
          partOfSpeech: 'noun',
          termType: 'product term',
          enforcement: 'required',
          status: 'APPROVED',
          provenance: null,
          target: 'fichier',
          targetComment: null,
          doNotTranslate: false,
          caseSensitive: false,
          matchType: 'EXACT',
          startIndex: 8,
          endIndex: 12,
          matchedText: 'file',
          evidence: [],
        },
        {
          glossaryId: 42,
          glossaryName: 'Product glossary',
          tmTextUnitId: 10,
          termKey: 'item.folder',
          source: 'folder',
          comment: null,
          definition: null,
          partOfSpeech: 'noun',
          termType: 'product term',
          enforcement: 'required',
          status: 'APPROVED',
          provenance: null,
          target: 'dossier',
          targetComment: null,
          doNotTranslate: false,
          caseSensitive: false,
          matchType: 'EXACT',
          startIndex: 20,
          endIndex: 26,
          matchedText: 'folder',
          evidence: [],
        },
      ],
    });

    renderWorkbenchBody({
      glossaryContext: {
        assetPath: 'checkout.json',
        backingRepositoryId: 10,
        backingRepositoryName: 'web',
        glossaryId: 42,
        glossaryName: 'Product glossary',
      },
      rows: [
        {
          ...editingRow,
          locale: 'fr-FR',
          source: 'Deleted {$item :term count=$count}.',
        },
      ],
      editingValue: 'Supprimé {$item :term count=$count}.',
    });

    fireEvent.click(screen.getByText(/MF2 term requirements: source \$item/u));

    const select = await screen.findByRole('combobox', { name: 'Glossary term ID for item' });
    await screen.findByText('item.file — file → fichier');
    await screen.findByText('item.folder — folder → dossier');

    expect(select).toHaveValue('');
    expect(reportInflectionBindingManifest).not.toHaveBeenCalledWith(
      42,
      'fr-FR',
      expect.objectContaining({
        argumentTerms: {
          'checkout.pay.source': { item: ['item.file'] },
          'checkout.pay.target': { item: ['item.file'] },
        },
      }),
    );
  });

  it('renders MF2 preview after default term binding and explicit runtime variable input', async () => {
    vi.mocked(matchGlossaryTerms).mockResolvedValue({
      matchedTerms: [
        {
          glossaryId: 42,
          glossaryName: 'Product glossary',
          tmTextUnitId: 9,
          termKey: 'item.file',
          source: 'file',
          comment: null,
          definition: null,
          partOfSpeech: 'noun',
          termType: 'product term',
          enforcement: 'required',
          status: 'APPROVED',
          provenance: null,
          target: 'fichier',
          targetComment: null,
          doNotTranslate: false,
          caseSensitive: false,
          matchType: 'EXACT',
          startIndex: 8,
          endIndex: 12,
          matchedText: 'file',
          evidence: [],
        },
      ],
    });
    vi.mocked(reportInflectionBindingManifest).mockImplementation(
      (_glossaryId, localeTag, manifest) => {
        const sourceTermIds = manifest.argumentTerms['checkout.pay.source']?.item ?? [];
        const targetTermIds = manifest.argumentTerms['checkout.pay.target']?.item ?? [];
        if (sourceTermIds.length === 0 || targetTermIds.length === 0) {
          return Promise.resolve({
            schema: 'mojito-mf2-inflection/term-binding-report/v0',
            locale: localeTag,
            summary: { messages: 2, requiredArguments: 2, diagnostics: 2 },
            diagnostics: [
              {
                messageId: 'checkout.pay.source',
                argument: 'item',
                status: 'missing',
                termIds: [],
              },
              {
                messageId: 'checkout.pay.target',
                argument: 'item',
                status: 'missing',
                termIds: [],
              },
            ],
            messages: {},
          });
        }
        return Promise.resolve({
          schema: 'mojito-mf2-inflection/term-binding-report/v0',
          locale: localeTag,
          summary: { messages: 2, requiredArguments: 2, diagnostics: 0 },
          diagnostics: [],
          messages: {},
        });
      },
    );
    vi.mocked(renderInflectionBindingManifest).mockResolvedValue({
      locale: 'fr-FR',
      messages: {
        'checkout.pay.source': 'Deleted file.',
        'checkout.pay.target': 'Supprimé fichier.',
      },
    });

    renderWorkbenchBody({
      glossaryContext: {
        assetPath: 'checkout.json',
        backingRepositoryId: 10,
        backingRepositoryName: 'web',
        glossaryId: 42,
        glossaryName: 'Product glossary',
      },
      rows: [
        {
          ...editingRow,
          locale: 'fr-FR',
          source: 'Deleted {$item :term count=$count}.',
        },
      ],
      editingValue: 'Supprimé {$item :term count=$count}.',
    });

    fireEvent.click(screen.getByText(/MF2 term requirements: source \$item/u));

    const select = await screen.findByRole('combobox', { name: 'Glossary term ID for item' });
    const countInput = await screen.findByRole('textbox', { name: 'Runtime value for count' });
    expect(await screen.findByText('item.file — file → fichier')).toBeInTheDocument();
    await waitFor(() => {
      expect(select).toHaveValue('item.file');
    });
    expect(renderInflectionBindingManifest).not.toHaveBeenCalled();

    await waitFor(() => {
      expect(reportInflectionBindingManifest).toHaveBeenCalledWith(
        42,
        'fr-FR',
        expect.objectContaining({
          argumentTerms: {
            'checkout.pay.source': { item: ['item.file'] },
            'checkout.pay.target': { item: ['item.file'] },
          },
        }),
      );
    });
    expect(
      await screen.findByText('MF2 term bindings: renderable for Product glossary.'),
    ).toBeInTheDocument();
    expect(renderInflectionBindingManifest).not.toHaveBeenCalled();

    fireEvent.change(countInput, { target: { value: '1' } });

    await waitFor(() => {
      expect(renderInflectionBindingManifest).toHaveBeenCalledWith(
        42,
        'fr-FR',
        expect.objectContaining({
          argumentTerms: {
            'checkout.pay.source': { item: ['item.file'] },
            'checkout.pay.target': { item: ['item.file'] },
          },
        }),
        { count: '1' },
      );
    });
    expect(await screen.findByText('Deleted file.')).toBeInTheDocument();
    expect(await screen.findByText('Supprimé fichier.')).toBeInTheDocument();
  });

  it('surfaces backend current V0 MF2 render preview errors in the Workbench preview', async () => {
    vi.mocked(matchGlossaryTerms).mockResolvedValue({
      matchedTerms: [
        {
          glossaryId: 42,
          glossaryName: 'Product glossary',
          tmTextUnitId: 9,
          termKey: 'item.file',
          source: 'file',
          comment: null,
          definition: null,
          partOfSpeech: 'noun',
          termType: 'product term',
          enforcement: 'required',
          status: 'APPROVED',
          provenance: null,
          target: 'fichier',
          targetComment: null,
          doNotTranslate: false,
          caseSensitive: false,
          matchType: 'EXACT',
          startIndex: 8,
          endIndex: 12,
          matchedText: 'file',
          evidence: [],
        },
      ],
    });
    vi.mocked(reportInflectionBindingManifest).mockResolvedValue({
      schema: 'mojito-mf2-inflection/term-binding-report/v0',
      locale: 'fr-FR',
      summary: { messages: 2, requiredArguments: 2, diagnostics: 0 },
      diagnostics: [],
      messages: {},
    });
    const renderError =
      'MF2 term binding manifest is not renderable: 1 binding diagnostics: inventory.deleted.item=unsupported-locale-runtime-term-inflection (unsupported by current V0 locale runtime)';
    vi.mocked(renderInflectionBindingManifest).mockRejectedValue(new Error(renderError));

    renderWorkbenchBody({
      glossaryContext: {
        assetPath: 'checkout.json',
        backingRepositoryId: 10,
        backingRepositoryName: 'web',
        glossaryId: 42,
        glossaryName: 'Product glossary',
      },
      rows: [
        {
          ...editingRow,
          locale: 'fr-FR',
          source: 'Deleted {$item :term count=$count}.',
        },
      ],
      editingValue: 'Supprimé {$item :term count=$count}.',
    });

    fireEvent.click(screen.getByText(/MF2 term requirements: source \$item/u));
    const countInput = await screen.findByRole('textbox', { name: 'Runtime value for count' });
    fireEvent.change(countInput, { target: { value: '1' } });

    expect(await screen.findByText(`MF2 render preview: ${renderError}`)).toHaveClass('is-error');
  });

  it('surfaces invalid MF2 term requirement options without breaking the row', () => {
    renderWorkbenchBody({
      rows: [
        {
          ...editingRow,
          source: 'Deleted {$item :term role=source}.',
        },
      ],
    });

    expect(screen.getByText('MF2 term requirements: Unsupported term option: role')).toHaveClass(
      'is-error',
    );
    expect(getDetailsButton()).toBeInTheDocument();
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

function emptyBindingReport(locale: string): ApiInflectionBindingReport {
  return {
    schema: 'mojito-mf2-inflection/term-binding-report/v0',
    locale,
    summary: { messages: 0, requiredArguments: 0, diagnostics: 0 },
    diagnostics: [],
    messages: {},
  };
}
