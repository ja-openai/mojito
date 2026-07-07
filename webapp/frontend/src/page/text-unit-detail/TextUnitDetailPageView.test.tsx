import { render, screen, waitFor } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { TextUnitDetailPageView } from './TextUnitDetailPageView';

type TextUnitDetailPageViewProps = ComponentProps<typeof TextUnitDetailPageView>;

const noop = vi.fn();

function buildProps(
  overrides: Partial<TextUnitDetailPageViewProps> = {},
): TextUnitDetailPageViewProps {
  return {
    tmTextUnitId: 3,
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
});
