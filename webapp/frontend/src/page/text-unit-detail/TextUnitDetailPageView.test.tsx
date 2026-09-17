import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { type ComponentProps, useState } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type * as TextUnitsApi from '../../api/text-units';
import { installProseMirrorDomMock } from '../../test/proseMirrorDom';
import { extractIcuProtectedTextTokens } from '../../utils/protectedTextTokens';
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
      hasSaveableChanges: false,
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
    showDiscardDialog: false,
    onConfirmDiscard: noop,
    onDismissDiscardDialog: noop,
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

  it('waits for the compact host to become visible before focusing a cached translation', () => {
    const props = buildProps({ embedded: true, presentation: 'compact' });
    const view = (autoFocus: boolean) => (
      <TextUnitDetailPageView
        {...props}
        autoFocus={autoFocus}
        visibleTextEditor={{ ...props.visibleTextEditor, enabled: false }}
      />
    );
    const editor = render(view(false));
    expect(screen.getByRole('textbox', { name: 'Translation' })).not.toHaveFocus();
    editor.rerender(view(true));
    expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveFocus();
  });

  it.each(['plain', 'assisted', 'MF2'] as const)(
    'restores %s editor focus and selection after keyboard placement switches',
    async (mode) => {
      const restoreDom = installProseMirrorDomMock();
      const user = userEvent.setup();
      const props = buildProps({ embedded: true });
      props.visibleTextEditor.enabled = mode !== 'plain';
      if (mode === 'MF2') {
        props.keyInfo.source = 'Hello {$name}';
        props.keyInfo.messageFormat = 'MF2';
        props.editorInfo.target = 'Olá {$name}';
      }
      function Editor() {
        const [presentation, setPresentation] = useState<'compact' | 'full'>('compact');
        return (
          <TextUnitDetailPageView
            {...props}
            presentation={presentation}
            onShowDetails={() => setPresentation('full')}
            onShowCompact={() => setPresentation('compact')}
          />
        );
      }
      const rendered = render(<Editor />);
      try {
        const editor = screen.getByRole('textbox', {
          name: mode === 'MF2' ? /^Target/ : 'Translation',
        });
        editor.focus();
        const selection = window.getSelection();
        const text = document.createTreeWalker(editor, NodeFilter.SHOW_TEXT).nextNode();
        if (mode === 'plain') {
          (editor as HTMLTextAreaElement).setSelectionRange(1, 3);
        } else {
          const range = document.createRange();
          range.setStart(text!, 1);
          range.setEnd(text!, 3);
          selection?.removeAllRanges();
          selection?.addRange(range);
          fireEvent(document, new Event('selectionchange'));
        }

        for (const buttonLabel of ['Open side panel', 'Edit inline']) {
          screen.getByRole('button', { name: buttonLabel }).focus();
          await user.keyboard('{Enter}');

          expect(
            screen.getByRole('textbox', { name: mode === 'MF2' ? /^Target/ : 'Translation' }),
          ).toBe(editor);
          expect(editor).toHaveFocus();
          if (mode === 'plain') {
            expect(editor).toHaveProperty('selectionStart', 1);
            expect(editor).toHaveProperty('selectionEnd', 3);
          } else {
            expect(selection?.anchorNode).toBe(text);
            expect(selection?.anchorOffset).toBe(1);
            expect(selection?.focusOffset).toBe(3);
          }
        }
      } finally {
        rendered.unmount();
        restoreDom();
      }
    },
  );

  it.each(['plain', 'assisted', 'MF2'] as const)(
    'closes the %s character menu before Escape closes quick edit',
    (mode) => {
      const onBack = vi.fn();
      const props = buildProps({ embedded: true, presentation: 'compact', onBack });
      props.visibleTextEditor.enabled = mode !== 'plain';
      if (mode === 'MF2') {
        props.keyInfo.source = 'Hello {$name}';
        props.keyInfo.messageFormat = 'MF2';
        props.editorInfo.target = 'Olá {$name}';
      }
      render(<TextUnitDetailPageView {...props} />);
      const editor = screen.getByRole('textbox', {
        name: mode === 'MF2' ? /^Target/ : 'Translation',
      });
      editor.focus();
      fireEvent.click(screen.getByText('Characters'));
      expect(editor).toHaveFocus();
      fireEvent.keyDown(editor, { key: 'Escape', keyCode: 27 });
      expect(onBack).not.toHaveBeenCalled();
      expect(screen.getByText('No-break space')).not.toBeVisible();
      fireEvent.keyDown(editor, { key: 'Escape', keyCode: 27 });
      expect(onBack).toHaveBeenCalledOnce();
    },
  );

  it.each(['assisted', 'MF2'] as const)(
    'closes the %s hidden-character menu before Escape closes quick edit',
    (mode) => {
      const onBack = vi.fn();
      const props = buildProps({ embedded: true, presentation: 'compact', onBack });
      if (mode === 'MF2') {
        props.keyInfo.source = 'Hello {$name}';
        props.keyInfo.messageFormat = 'MF2';
        props.editorInfo.target = 'Olá {$name}';
      }
      render(<TextUnitDetailPageView {...props} />);
      const editor = screen.getByRole('textbox', {
        name: mode === 'MF2' ? /^Target/ : 'Translation',
      });
      editor.focus();
      fireEvent.click(screen.getByRole('button', { name: 'Hidden characters: Auto' }));
      expect(editor).toHaveFocus();
      fireEvent.keyDown(editor, { key: 'Escape', keyCode: 27 });
      expect(onBack).not.toHaveBeenCalled();
      expect(screen.queryByRole('listbox', { name: 'Hidden characters' })).not.toBeInTheDocument();
      fireEvent.keyDown(editor, { key: 'Escape', keyCode: 27 });
      expect(onBack).toHaveBeenCalledOnce();
    },
  );

  it('closes MF2 shortcut help before Escape closes quick edit', () => {
    const onBack = vi.fn();
    const props = buildProps({ embedded: true, presentation: 'compact', onBack });
    props.keyInfo.source = 'Hello {$name}';
    props.keyInfo.messageFormat = 'MF2';
    props.editorInfo.target = 'Olá {$name}';
    render(<TextUnitDetailPageView {...props} />);
    const editor = screen.getByRole('textbox', { name: /^Target/ });
    editor.focus();
    fireEvent.click(screen.getByText('Shortcuts'));
    expect(screen.getByText('placeholder menu')).toBeVisible();

    fireEvent.keyDown(editor, { key: 'Escape', keyCode: 27 });

    expect(onBack).not.toHaveBeenCalled();
    expect(screen.getByText('placeholder menu')).not.toBeVisible();
    fireEvent.keyDown(editor, { key: 'Escape', keyCode: 27 });
    expect(onBack).toHaveBeenCalledOnce();
  });

  it('closes the ICU form menu before Escape closes quick edit', () => {
    const onBack = vi.fn();
    const props = buildProps({ embedded: true, presentation: 'compact', onBack });
    props.keyInfo.source = '{count, plural, one {# file} other {# files}}';
    props.editorInfo.target = props.keyInfo.source;
    props.visibleTextEditor.protectedTokens = extractIcuProtectedTextTokens(props.keyInfo.source);
    const { container } = render(<TextUnitDetailPageView {...props} />);
    const editor = screen.getByRole('textbox', { name: 'Translation' });
    editor.focus();
    const form = container.querySelector<HTMLElement>(
      '.visible-text-editor__protected-token--icu-syntax:not(.visible-text-editor__protected-token--empty-icu-syntax)',
    );
    expect(form).toBeInTheDocument();
    fireEvent.click(form as HTMLElement);
    expect(screen.getByRole('menu', { name: /count plural forms/ })).toBeInTheDocument();

    fireEvent.keyDown(editor, { key: 'Escape', keyCode: 27 });

    expect(onBack).not.toHaveBeenCalled();
    expect(screen.queryByRole('menu', { name: /count plural forms/ })).not.toBeInTheDocument();
    fireEvent.keyDown(editor, { key: 'Escape', keyCode: 27 });
    expect(onBack).toHaveBeenCalledOnce();
  });

  it('keeps the source visible as a plain section in quick edit', () => {
    const props = buildProps({ embedded: true, presentation: 'compact' });
    render(
      <TextUnitDetailPageView
        {...props}
        visibleTextEditor={{ ...props.visibleTextEditor, enabled: false }}
      />,
    );

    expect(screen.getByRole('heading', { name: 'Source' })).toBeVisible();
    expect(screen.getByText(props.keyInfo.source, { selector: 'pre' })).toBeVisible();
  });

  it('closes quick edit on a real Escape key from the assisted editor', () => {
    const onBack = vi.fn();
    const props = buildProps({ embedded: true, presentation: 'compact', onBack });
    render(<TextUnitDetailPageView {...props} />);
    const editor = screen.getByRole('textbox', { name: 'Translation' });
    expect(editor).toHaveClass('ProseMirror');
    editor.focus();

    fireEvent.keyDown(editor, { key: 'Escape', keyCode: 27 });

    expect(onBack).toHaveBeenCalledOnce();
  });

  it('closes quick edit on a real Escape key from the guided MF2 editor', () => {
    const onBack = vi.fn();
    const props = buildProps({ embedded: true, presentation: 'compact', onBack });
    props.keyInfo.source = 'Hello {$name}';
    props.keyInfo.messageFormat = 'MF2';
    props.editorInfo.target = 'Olá {$name}';
    render(<TextUnitDetailPageView {...props} />);
    const editor = screen.getByRole('textbox', { name: /^Target/ });
    expect(editor).toHaveClass('ProseMirror');
    editor.focus();

    fireEvent.keyDown(editor, { key: 'Escape', keyCode: 27 });

    expect(onBack).toHaveBeenCalledOnce();
  });

  it.each([false, true])('keeps the final-passage action visible when dirty is %s', (isDirty) => {
    const props = buildProps({ embedded: true, presentation: 'compact' });
    props.editorInfo.isDirty = isDirty;
    props.editorInfo.hasSaveableChanges = isDirty;
    render(<TextUnitDetailPageView {...props} />);

    const next = screen.getByRole('button', { name: isDirty ? 'Save & next' : 'Next' });
    expect(next).toBeDisabled();
    expect(next).toHaveAttribute('title', 'No next passage in this file');
  });

  it.each([
    'composition',
    'repeat',
    'saving',
    'deleting',
    'validation error',
    'validation dialog',
    'delete dialog',
    'discard dialog',
  ])('does not save or advance with the shortcut during %s', (guard) => {
    const onSaveEditor = vi.fn();
    const onSaveAndNext = vi.fn();
    const props = buildProps({
      embedded: true,
      presentation: 'compact',
      onSaveEditor,
      onSaveAndNext,
    });
    props.visibleTextEditor.enabled = false;
    props.editorInfo.isDirty = true;
    props.editorInfo.hasSaveableChanges = true;
    props.editorInfo.isSaving = guard === 'saving';
    props.editorInfo.isDeleting = guard === 'deleting';
    props.editorInfo.mf2ErrorCount = guard === 'validation error' ? 1 : 0;
    props.showValidationDialog = guard === 'validation dialog';
    props.showDeleteDialog = guard === 'delete dialog';
    props.showDiscardDialog = guard === 'discard dialog';
    render(<TextUnitDetailPageView {...props} />);

    fireEvent.keyDown(screen.getByRole('textbox', { name: 'Translation' }), {
      key: 'Enter',
      ctrlKey: true,
      shiftKey: true,
      isComposing: guard === 'composition',
      repeat: guard === 'repeat',
    });

    expect(onSaveAndNext).not.toHaveBeenCalled();
    expect(onSaveEditor).not.toHaveBeenCalled();
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
