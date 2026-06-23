import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentProps } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import { TextUnitDetailPageView } from './TextUnitDetailPageView';

describe('TextUnitDetailPageView', () => {
  it('keeps CMS handoffs focused on translation and source context', async () => {
    const user = userEvent.setup();

    renderView({
      isCmsHandoff: true,
      isGlossaryCollapsed: true,
      originContext: 'Product copy: Growth email copy / Welcome email / Copy',
    });

    expect(screen.getByRole('heading', { name: 'Translate copy' })).toBeInTheDocument();
    expect(screen.getByText('Product copy')).toBeInTheDocument();
    expect(screen.getByText('Back to Product copy')).toBeInTheDocument();
    expect(screen.getByText('Source context')).toBeInTheDocument();
    expect(screen.getByText('Source copy')).toBeInTheDocument();
    expect(screen.getByText('Translator note')).toBeInTheDocument();
    expect(screen.queryByText('Text unit #1')).not.toBeInTheDocument();
    expect(screen.queryByText('cms-growth-email-copy')).not.toBeInTheDocument();
    expect(screen.queryByText('Glossary')).not.toBeInTheDocument();
    expect(screen.queryByText('Metadata')).not.toBeInTheDocument();
    expect(screen.queryByText('AI Chat Review')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Translation help/ }));

    expect(screen.getByText('AI Chat Review')).toBeInTheDocument();
    expect(screen.getByText('Glossary')).toBeInTheDocument();
    expect(screen.getByText('Metadata')).toBeInTheDocument();
    expect(
      screen.queryByText('No glossary terms matched this source string.'),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Glossary' })).toHaveAttribute(
      'aria-expanded',
      'false',
    );

    await user.click(screen.getByRole('button', { name: 'Back to translation' }));

    expect(screen.getByRole('textbox', { name: 'Translation' })).toHaveFocus();
  });

  it('keeps the existing Mojito detail surface outside CMS handoffs', () => {
    renderView({ isCmsHandoff: false });

    expect(screen.getByRole('heading', { name: 'Translation' })).toBeInTheDocument();
    expect(screen.getByText('Text unit #1')).toBeInTheDocument();
    expect(screen.getByText('cms-growth-email-copy')).toBeInTheDocument();
    expect(screen.queryByText('Back to Product copy')).not.toBeInTheDocument();
    expect(screen.getByText('Glossary')).toBeInTheDocument();
    expect(screen.getByText('Metadata')).toBeInTheDocument();
  });

  it('names CMS optional tools for review work', () => {
    renderView({
      isCmsHandoff: true,
      editorInfo: {
        ...createProps({}).editorInfo,
        status: 'Accepted',
      },
    });

    expect(screen.getByRole('heading', { name: 'Review translation' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Review help/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Translation help/ })).not.toBeInTheDocument();
  });

  it('keeps source-only CMS handoffs on source context', () => {
    renderView({
      isCmsHandoff: true,
      editorInfo: {
        ...createProps({}).editorInfo,
        isSourceOnly: true,
      },
    });

    expect(screen.getByRole('heading', { name: 'Source copy' })).toBeInTheDocument();
    expect(screen.getByText('Source context')).toBeInTheDocument();
    expect(screen.queryByText('Optional')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: /Translation help|Review help|Copy details/ }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText('Glossary')).not.toBeInTheDocument();
    expect(screen.queryByText('Metadata')).not.toBeInTheDocument();
  });

  it('uses the assisted protected editor for translation details', async () => {
    const { container } = renderView();

    expect(await screen.findByRole('textbox', { name: 'Translation' })).toHaveClass('ProseMirror');
    expect(screen.getByRole('button', { name: 'Hidden characters: Auto' })).toBeInTheDocument();
    expect(
      screen.getByRole('button', {
        name: 'Placeholder editing is off. Edit placeholders',
      }),
    ).toBeInTheDocument();

    await waitFor(() => {
      const protectedToken = container.querySelector('.visible-text-editor__protected-token');
      expect(protectedToken).toHaveTextContent('price');
      expect(protectedToken).toHaveClass('visible-text-editor__protected-token--icu-placeholder');
    });
  });
});

function renderView(overrides: Partial<ComponentProps<typeof TextUnitDetailPageView>> = {}) {
  return render(
    <MemoryRouter>
      <TextUnitDetailPageView {...createProps(overrides)} />
    </MemoryRouter>,
  );
}

function createProps(
  overrides: Partial<ComponentProps<typeof TextUnitDetailPageView>>,
): ComponentProps<typeof TextUnitDetailPageView> {
  return {
    tmTextUnitId: 1,
    onBack: vi.fn(),
    backLabel: 'Back to Product copy',
    isCmsHandoff: false,
    originContext: null,
    editorInfo: {
      target: 'Pay {price} now',
      status: 'Rejected',
      isSourceOnly: false,
      statusOptions: ['Accepted', 'To review', 'To translate', 'Rejected'],
      canEdit: true,
      canDelete: false,
      isDirty: false,
      isSaving: false,
      isDeleting: false,
      errorMessage: null,
      warningMessage: null,
    },
    visibleTextEditor: {
      enabled: true,
      marksMode: 'auto',
      onChangeMarksMode: vi.fn(),
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
      stringId: 'cms.growth-email-copy.welcome-email.default.copy',
      locale: 'fr-FR',
      source: 'Pay {price} now',
      comment: 'Friendly welcome sentence. Keep Acme untranslated.',
      repositoryName: 'cms-growth-email-copy',
    },
    onChangeTarget: vi.fn(),
    onChangeStatus: vi.fn(),
    onSaveEditor: vi.fn(),
    onResetEditor: vi.fn(),
    onRequestDeleteEditor: vi.fn(),
    previewLocale: 'fr-FR',
    isIcuPreviewCollapsed: true,
    onToggleIcuPreviewCollapsed: vi.fn(),
    icuPreviewMode: 'target',
    onChangeIcuPreviewMode: vi.fn(),
    isAiCollapsed: true,
    onToggleAiCollapsed: vi.fn(),
    aiMessages: [],
    aiInput: '',
    onChangeAiInput: vi.fn(),
    onSubmitAi: vi.fn(),
    onRetryAi: vi.fn(),
    onUseAiSuggestion: vi.fn(),
    isAiResponding: false,
    glossaryMatches: [],
    isGlossaryLoading: false,
    glossaryErrorMessage: null,
    glossaryTermMetadata: null,
    sourceScreenshots: [],
    isGlossaryCollapsed: false,
    onToggleGlossaryCollapsed: vi.fn(),
    isMetaCollapsed: true,
    onToggleMetaCollapsed: vi.fn(),
    isMetaLoading: false,
    metaErrorMessage: null,
    metaSections: [],
    targetCommentEditor: {
      draft: '',
      isEditing: false,
      canEdit: false,
      isSaving: false,
      isDisabled: false,
      disabledReason: null,
      onStart: vi.fn(),
      onChange: vi.fn(),
      onSave: vi.fn(),
      onCancel: vi.fn(),
    },
    metaWarningMessage: null,
    isHistoryCollapsed: true,
    onToggleHistoryCollapsed: vi.fn(),
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
    onConfirmValidationSave: vi.fn(),
    onRetryValidationSave: vi.fn(),
    onDismissValidationDialog: vi.fn(),
    showDeleteDialog: false,
    deleteDialogBody: '',
    onConfirmDeleteEditor: vi.fn(),
    onDismissDeleteDialog: vi.fn(),
    ...overrides,
  };
}
