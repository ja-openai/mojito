export const contentCmsPreviewModes = [
  'clean-start',
  'source-only',
  'source-only-required-source-empty',
  'authoring-demo',
  'blank-field-placement',
  'blank-item-placement',
  'blank-item-name',
  'empty-content-items',
  'multi-item-no-source',
  'new-item-no-fields',
  'required-source-empty',
  'release-loading',
  'missing-translation',
  'translation-refresh-failed',
  'translation-editor-unavailable',
  'translation-load-error',
  'translation-language-removed',
  'translation-language-removed-multiple-alternates',
  'translation-language-removed-no-access',
  'translation-language-removed-no-targets',
  'translation-language-removed-loading',
  'translation-language-removed-unsupported',
  'translation-locale-no-access',
  'optional-wait',
  'optional-source-draft',
  'saved-content-repair',
  'ready-for-release',
  'release-start',
  'release-changed',
  'release-changed-multi-item',
  'release-changed-item-review',
  'release-overflow',
  'release-checking',
  'release-confirmation',
  'release-publishing',
  'release-success',
  'release-retry',
  'release-blocker',
  'release-locale-blocker',
  'release-repair-refresh-failed',
  'release-source-blocker',
  'release-single-blocker',
  'release-target-unavailable',
  'release-target-repaired-unavailable',
  'release-target-reconnect-error',
  'release-target-reconnect-conflict',
  'release-target-dirty-source-draft',
  'release-target-dirty-source-conflict',
  'release-target-language-removed',
  'release-target-language-removed-multiple-alternates',
  'release-target-language-add-failed',
  'release-target-language-add-conflict',
  'release-target-language-add-conflict-still-removed',
  'release-target-language-add-conflict-refresh-failed',
  'release-target-language-add-conflict-refresh-failed-retry-still-removed',
  'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again',
  'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed',
  'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored',
  'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-add-restored',
  'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored',
  'stale-recovery',
] as const;

export type ContentCmsPreviewMode = (typeof contentCmsPreviewModes)[number];
export const contentCmsSettingsDirectoryPreviewMode = 'settings-directory' as const;

export function isContentCmsPreviewMode(
  previewMode: string | null,
): previewMode is ContentCmsPreviewMode {
  return contentCmsPreviewModes.includes(previewMode as ContentCmsPreviewMode);
}

export function isContentCmsSettingsDirectoryPreviewMode(
  previewMode: string | null,
): previewMode is typeof contentCmsSettingsDirectoryPreviewMode {
  return previewMode === contentCmsSettingsDirectoryPreviewMode;
}

export function getContentCmsPreviewMode(search: string): ContentCmsPreviewMode {
  const previewMode = new URLSearchParams(search).get('cmsPreview');
  return isContentCmsPreviewMode(previewMode) ? previewMode : 'stale-recovery';
}
