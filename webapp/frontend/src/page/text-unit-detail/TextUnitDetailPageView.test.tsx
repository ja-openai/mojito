import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type * as TextUnitsApi from '../../api/text-units';
import { TextUnitDetailPageView } from './TextUnitDetailPageView';

type TextUnitDetailPageViewProps = ComponentProps<typeof TextUnitDetailPageView>;

const noop = vi.fn();
const fetchRepositoriesMock = vi.hoisted(() => vi.fn());
const searchTextUnitsMock = vi.hoisted(() => vi.fn());

vi.mock('../../api/repositories', () => ({ fetchRepositories: fetchRepositoriesMock }));
vi.mock('../../api/text-units', async (importActual) => ({
  ...(await importActual<typeof TextUnitsApi>()),
  searchTextUnits: searchTextUnitsMock,
}));

function buildProps(
  overrides: Partial<TextUnitDetailPageViewProps> = {},
): TextUnitDetailPageViewProps {
  return {
    tmTextUnitId: 3,
    isSearchEnabled: false,
    onBack: noop,
    editorInfo: {
      target: 'Pay {price} now',
      status: 'TRANSLATED',
      isSourceOnly: false,
      statusOptions: ['TRANSLATED', 'NEEDS_REVIEW'],
      canEdit: true,
      canDelete: true,
      isDirty: false,
      isSaving: false,
      isDeleting: false,
      mf2ErrorCount: 0,
      errorMessage: null,
      warningMessage: null,
    },
    visibleTextEditor: {
      enabled: true,
      marksMode: 'auto',
      onChangeMarksMode: noop,
      protectedDiagnostics: [],
      protectedTokens: [
        {
          start: 4,
          end: 11,
          label: 'ICU argument price',
          kind: 'icu-placeholder',
        },
      ],
      validateNextValue: () => true,
      dir: 'ltr',
    },
    keyInfo: {
      stringId: 'checkout.pay',
      locale: 'pt-PT',
      source: 'Pay {price} now',
      comment: 'Checkout payment copy',
      repositoryName: 'web',
    },
    onChangeTarget: noop,
    onChangeStatus: noop,
    onSaveEditor: noop,
    onResetEditor: noop,
    onRequestDeleteEditor: noop,
    previewLocale: 'pt-PT',
    isIcuPreviewCollapsed: true,
    onToggleIcuPreviewCollapsed: noop,
    icuPreviewMode: 'target',
    onChangeIcuPreviewMode: noop,
    isAiCollapsed: true,
    onToggleAiCollapsed: noop,
    aiMessages: [],
    aiInput: '',
    onChangeAiInput: noop,
    onSubmitAi: noop,
    onRetryAi: noop,
    onUseAiSuggestion: noop,
    isAiResponding: false,
    glossaryMatches: [],
    isGlossaryLoading: false,
    glossaryErrorMessage: null,
    glossaryTermMetadata: null,
    sourceScreenshots: [],
    isGlossaryCollapsed: true,
    onToggleGlossaryCollapsed: noop,
    isMetaCollapsed: true,
    onToggleMetaCollapsed: noop,
    isMetaLoading: false,
    metaErrorMessage: null,
    metaSections: [],
    targetCommentEditor: {
      draft: '',
      isEditing: false,
      canEdit: true,
      isSaving: false,
      isDisabled: false,
      disabledReason: null,
      onStart: noop,
      onChange: noop,
      onSave: noop,
      onCancel: noop,
    },
    metaWarningMessage: null,
    isHistoryCollapsed: true,
    onToggleHistoryCollapsed: noop,
    isHistoryLoading: false,
    historyErrorMessage: null,
    historyMissingLocale: false,
    historyRows: [],
    historyInitialDate: '',
    isHistoryCountReady: true,
    showDeletedHistoryEntry: false,
    showValidationDialog: false,
    validationDialogTitle: '',
    validationDialogBody: '',
    validationDialogFailureDetail: null,
    validationDialogReportMessage: null,
    validationDialogReportHtml: null,
    validationDialogCanBypass: false,
    validationDialogCanRetry: false,
    onConfirmValidationSave: noop,
    onRetryValidationSave: noop,
    onDismissValidationDialog: noop,
    showDeleteDialog: false,
    deleteDialogBody: '',
    onConfirmDeleteEditor: noop,
    onDismissDeleteDialog: noop,
    ...overrides,
  };
}

describe('TextUnitDetailPageView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchRepositoriesMock.mockResolvedValue([{ id: 1, name: 'web' }]);
    searchTextUnitsMock.mockResolvedValue([]);
  });

  it('shows a visible Open in Workbench action for Details opened in a new tab', () => {
    const onBack = vi.fn();
    render(<TextUnitDetailPageView {...buildProps({ openInWorkbench: true, onBack })} />);

    const button = screen.getByRole('button', { name: 'Open in Workbench' });
    expect(button).toHaveTextContent('Open in Workbench');
    expect(button).toHaveAttribute('title', 'Open in Workbench');
    fireEvent.click(button);
    expect(onBack).toHaveBeenCalledOnce();
  });

  it('uses the assisted protected editor for translation details', async () => {
    const { container } = render(<TextUnitDetailPageView {...buildProps()} />);

    expect(await screen.findByRole('textbox', { name: 'Translation' })).toHaveClass('ProseMirror');
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

  it('omits Search in source-only details even when the preference is enabled', () => {
    const props = buildProps({ isSearchEnabled: true });
    render(
      <TextUnitDetailPageView
        {...props}
        editorInfo={{ ...props.editorInfo, isSourceOnly: true }}
      />,
    );

    expect(screen.queryByRole('button', { name: 'Search' })).not.toBeInTheDocument();
    expect(fetchRepositoriesMock).not.toHaveBeenCalled();
    expect(searchTextUnitsMock).not.toHaveBeenCalled();
  });

  it.each(['locale', 'string'] as const)('resets Search when the %s changes', async (change) => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const view = (props: TextUnitDetailPageViewProps) => (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <TextUnitDetailPageView {...props} />
        </MemoryRouter>
      </QueryClientProvider>
    );
    const props = buildProps({ isSearchEnabled: true });
    const { rerender } = render(view(props));
    fireEvent.click(screen.getByRole('button', { name: 'Search', expanded: false }));
    await waitFor(() => expect(fetchRepositoriesMock).toHaveBeenCalledTimes(1));
    const input = screen.getByRole('searchbox', { name: 'Search translation' });
    fireEvent.change(input, { target: { value: 'previous query' } });
    fireEvent.submit(input.closest('form')!);
    expect(await screen.findByText('No matches found.')).toBeVisible();

    const nextLocale = change === 'locale' ? 'fr-FR' : 'pt-PT';
    rerender(
      view({
        ...props,
        tmTextUnitId: change === 'string' ? 4 : 3,
        keyInfo: { ...props.keyInfo, locale: nextLocale },
      }),
    );
    expect(screen.getByRole('button', { name: 'Search', expanded: false })).toBeInTheDocument();
    expect(screen.queryByRole('searchbox', { name: 'Search translation' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Search', expanded: false }));
    const nextInput = screen.getByRole('searchbox', { name: 'Search translation' });
    expect(nextInput).not.toBe(input);
    expect(nextInput).toHaveValue('');
    expect(screen.queryByText('No matches found.')).not.toBeInTheDocument();
    fireEvent.change(nextInput, { target: { value: 'new query' } });
    fireEvent.submit(nextInput.closest('form')!);
    await waitFor(() =>
      expect(searchTextUnitsMock).toHaveBeenLastCalledWith(
        expect.objectContaining({
          localeTags: [nextLocale],
          textSearch: {
            operator: 'AND',
            predicates: [{ field: 'target', searchType: 'contains', value: 'new query' }],
          },
        }),
      ),
    );
  });
});
