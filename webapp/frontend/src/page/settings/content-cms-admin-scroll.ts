export type CopyFieldFocusTarget =
  | 'field'
  | 'field-context'
  | 'source-copy'
  | 'translation'
  | 'translation-reconnect'
  | 'translation-reconnect-refresh';

export const cmsReleasePanelId = 'content-cms-release-panel';

export function queueCopyFieldScroll(fieldId: string, focusTarget: CopyFieldFocusTarget = 'field') {
  window.setTimeout(() => {
    const field = document.getElementById(getCopyFieldAnchorId(fieldId));
    const fieldHeader = field?.querySelector<HTMLElement>(
      '.content-cms-admin-page__field-editor-header',
    );
    const fieldScrollTarget = fieldHeader ?? field;
    if (focusTarget === 'field-context') {
      const fieldContextDetails = field?.querySelector<HTMLDetailsElement>(
        '.content-cms-admin-page__field-context-details',
      );
      if (fieldContextDetails != null) {
        fieldContextDetails.open = true;
      }
    }
    const fieldFocusTarget = getCopyFieldFocusTarget(field, focusTarget) ?? field;
    fieldScrollTarget?.scrollIntoView?.({ block: 'start' });
    fieldFocusTarget?.focus?.({ preventScroll: true });
  }, 0);
}

export function queueCopyBlockEditorScroll(entryId: string) {
  window.setTimeout(() => {
    const editor = document.getElementById(getCopyBlockEditorAnchorId(entryId));
    editor?.scrollIntoView?.({ block: 'start' });
    editor?.focus?.({ preventScroll: true });
  }, 0);
}

export function queueCopyBlockDetailsFocus(entryId: string) {
  window.setTimeout(() => {
    const editor = document.getElementById(getCopyBlockEditorAnchorId(entryId));
    const detailsInput = editor?.querySelector<HTMLElement>(
      '.content-cms-admin-page__block-form .settings-input',
    );
    detailsInput?.focus?.({ preventScroll: true });
  }, 0);
}

export function focusCmsReleasePanel() {
  const releasePanel = document.getElementById(cmsReleasePanelId);
  releasePanel?.scrollIntoView?.({ block: 'start' });
  releasePanel?.focus?.({ preventScroll: true });
}

export function queueCmsReleasePanelFocus() {
  window.setTimeout(focusCmsReleasePanel, 0);
}

function getCopyFieldAnchorId(fieldId: string) {
  return `content-cms-copy-field-${fieldId}`;
}

function getCopyBlockEditorAnchorId(entryId: string) {
  return `content-cms-copy-block-${entryId}`;
}

function getCopyFieldFocusTarget(field: HTMLElement | null, focusTarget: CopyFieldFocusTarget) {
  if (field == null || focusTarget === 'field') {
    return field;
  }
  if (focusTarget === 'source-copy') {
    return field.querySelector<HTMLElement>('.content-cms-admin-page__copy-input');
  }
  if (focusTarget === 'field-context') {
    return field.querySelector<HTMLElement>(
      '.content-cms-admin-page__field-context-form .settings-input',
    );
  }
  if (focusTarget === 'translation-reconnect') {
    return field.querySelector<HTMLElement>(
      '.content-cms-admin-page__translation-reconnect-action',
    );
  }
  if (focusTarget === 'translation-reconnect-refresh') {
    return field.querySelector<HTMLElement>(
      '.content-cms-admin-page__translation-reconnect-refresh-action',
    );
  }
  return (
    field.querySelector<HTMLElement>('.content-cms-admin-page__target-copy-input') ??
    field.querySelector<HTMLElement>(
      '.content-cms-admin-page__inline-translation .settings-button',
    ) ??
    field.querySelector<HTMLElement>(
      '.content-cms-admin-page__inline-translation-release-handoff .settings-button',
    ) ??
    field.querySelector<HTMLElement>('.content-cms-admin-page__inline-translation') ??
    field.querySelector<HTMLElement>('.content-cms-admin-page__inline-translation-release-handoff')
  );
}
