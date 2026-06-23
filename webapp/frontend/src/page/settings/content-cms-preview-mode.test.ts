import { describe, expect, it } from 'vitest';

import {
  contentCmsPreviewModes,
  contentCmsSettingsDirectoryPreviewMode,
  getContentCmsPreviewMode,
  isContentCmsPreviewMode,
  isContentCmsSettingsDirectoryPreviewMode,
} from './content-cms-preview-mode';

describe('content CMS preview mode', () => {
  it('keeps the settings directory preview outside the authoring preview registry', () => {
    expect(contentCmsSettingsDirectoryPreviewMode).toBe('settings-directory');
    expect(isContentCmsSettingsDirectoryPreviewMode('settings-directory')).toBe(true);
    expect(isContentCmsPreviewMode('settings-directory')).toBe(false);
  });

  it('keeps the clean-start demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('clean-start');
    expect(isContentCmsPreviewMode('clean-start')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=clean-start')).toBe('clean-start');
  });

  it('keeps the source-only demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('source-only');
    expect(isContentCmsPreviewMode('source-only')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=source-only')).toBe('source-only');
  });

  it('keeps the seeded authoring and stale recovery demo routes in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('authoring-demo');
    expect(isContentCmsPreviewMode('authoring-demo')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=authoring-demo')).toBe('authoring-demo');
    expect(contentCmsPreviewModes).toContain('stale-recovery');
    expect(isContentCmsPreviewMode('stale-recovery')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=stale-recovery')).toBe('stale-recovery');
  });

  it('keeps the translation refresh failure demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('translation-refresh-failed');
    expect(isContentCmsPreviewMode('translation-refresh-failed')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=translation-refresh-failed')).toBe(
      'translation-refresh-failed',
    );
  });

  it('keeps the translation editor unavailable demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('translation-editor-unavailable');
    expect(isContentCmsPreviewMode('translation-editor-unavailable')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=translation-editor-unavailable')).toBe(
      'translation-editor-unavailable',
    );
  });

  it('keeps the removed translation language demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('translation-language-removed');
    expect(isContentCmsPreviewMode('translation-language-removed')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=translation-language-removed')).toBe(
      'translation-language-removed',
    );
  });

  it('keeps the removed multiple-alternate translation language demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('translation-language-removed-multiple-alternates');
    expect(isContentCmsPreviewMode('translation-language-removed-multiple-alternates')).toBe(true);
    expect(
      getContentCmsPreviewMode('?cmsPreview=translation-language-removed-multiple-alternates'),
    ).toBe('translation-language-removed-multiple-alternates');
  });

  it('keeps the removed no-access translation language demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('translation-language-removed-no-access');
    expect(isContentCmsPreviewMode('translation-language-removed-no-access')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=translation-language-removed-no-access')).toBe(
      'translation-language-removed-no-access',
    );
  });

  it('keeps the removed no-target translation language demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('translation-language-removed-no-targets');
    expect(isContentCmsPreviewMode('translation-language-removed-no-targets')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=translation-language-removed-no-targets')).toBe(
      'translation-language-removed-no-targets',
    );
  });

  it('keeps the removed loading translation language demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('translation-language-removed-loading');
    expect(isContentCmsPreviewMode('translation-language-removed-loading')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=translation-language-removed-loading')).toBe(
      'translation-language-removed-loading',
    );
  });

  it('keeps the removed unsupported translation language demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('translation-language-removed-unsupported');
    expect(isContentCmsPreviewMode('translation-language-removed-unsupported')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=translation-language-removed-unsupported')).toBe(
      'translation-language-removed-unsupported',
    );
  });

  it('keeps the no-access translation locale demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('translation-locale-no-access');
    expect(isContentCmsPreviewMode('translation-locale-no-access')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=translation-locale-no-access')).toBe(
      'translation-locale-no-access',
    );
  });

  it('keeps the ready-for-release demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('ready-for-release');
    expect(isContentCmsPreviewMode('ready-for-release')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=ready-for-release')).toBe('ready-for-release');
  });

  it('keeps the release-retry demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-retry');
    expect(isContentCmsPreviewMode('release-retry')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-retry')).toBe('release-retry');
  });

  it('keeps the changed release demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-changed');
    expect(isContentCmsPreviewMode('release-changed')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-changed')).toBe('release-changed');
    expect(contentCmsPreviewModes).toContain('release-changed-multi-item');
    expect(isContentCmsPreviewMode('release-changed-multi-item')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-changed-multi-item')).toBe(
      'release-changed-multi-item',
    );
    expect(contentCmsPreviewModes).toContain('release-changed-item-review');
    expect(isContentCmsPreviewMode('release-changed-item-review')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-changed-item-review')).toBe(
      'release-changed-item-review',
    );
    expect(contentCmsPreviewModes).toContain('release-overflow');
    expect(isContentCmsPreviewMode('release-overflow')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-overflow')).toBe('release-overflow');
  });

  it('keeps the release-checking demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-checking');
    expect(isContentCmsPreviewMode('release-checking')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-checking')).toBe('release-checking');
  });

  it('keeps the release-confirmation demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-confirmation');
    expect(isContentCmsPreviewMode('release-confirmation')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-confirmation')).toBe(
      'release-confirmation',
    );
  });

  it('keeps the release-publishing demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-publishing');
    expect(isContentCmsPreviewMode('release-publishing')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-publishing')).toBe('release-publishing');
  });

  it('keeps the release-success demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-success');
    expect(isContentCmsPreviewMode('release-success')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-success')).toBe('release-success');
  });

  it('keeps the release locale blocker demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-locale-blocker');
    expect(isContentCmsPreviewMode('release-locale-blocker')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-locale-blocker')).toBe(
      'release-locale-blocker',
    );
  });

  it('keeps the saved repair refresh failure demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-repair-refresh-failed');
    expect(isContentCmsPreviewMode('release-repair-refresh-failed')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-repair-refresh-failed')).toBe(
      'release-repair-refresh-failed',
    );
  });

  it('keeps the release source blocker demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-source-blocker');
    expect(isContentCmsPreviewMode('release-source-blocker')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-source-blocker')).toBe(
      'release-source-blocker',
    );
  });

  it('keeps the release single blocker demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-single-blocker');
    expect(isContentCmsPreviewMode('release-single-blocker')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-single-blocker')).toBe(
      'release-single-blocker',
    );
  });

  it('keeps the unavailable release target demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-target-unavailable');
    expect(isContentCmsPreviewMode('release-target-unavailable')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-target-unavailable')).toBe(
      'release-target-unavailable',
    );
  });

  it('keeps the repaired unavailable release target demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-target-repaired-unavailable');
    expect(isContentCmsPreviewMode('release-target-repaired-unavailable')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-target-repaired-unavailable')).toBe(
      'release-target-repaired-unavailable',
    );
  });

  it('keeps the failed release target reconnect demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-target-reconnect-error');
    expect(isContentCmsPreviewMode('release-target-reconnect-error')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-target-reconnect-error')).toBe(
      'release-target-reconnect-error',
    );
  });

  it('keeps the conflicting release target reconnect demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-target-reconnect-conflict');
    expect(isContentCmsPreviewMode('release-target-reconnect-conflict')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-target-reconnect-conflict')).toBe(
      'release-target-reconnect-conflict',
    );
  });

  it('keeps the dirty source draft release target demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-target-dirty-source-draft');
    expect(isContentCmsPreviewMode('release-target-dirty-source-draft')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-target-dirty-source-draft')).toBe(
      'release-target-dirty-source-draft',
    );
  });

  it('keeps the conflicting dirty source draft release target demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-target-dirty-source-conflict');
    expect(isContentCmsPreviewMode('release-target-dirty-source-conflict')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-target-dirty-source-conflict')).toBe(
      'release-target-dirty-source-conflict',
    );
  });

  it('keeps the removed multiple-alternate release target demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-target-language-removed-multiple-alternates');
    expect(isContentCmsPreviewMode('release-target-language-removed-multiple-alternates')).toBe(
      true,
    );
    expect(
      getContentCmsPreviewMode('?cmsPreview=release-target-language-removed-multiple-alternates'),
    ).toBe('release-target-language-removed-multiple-alternates');
  });

  it('keeps the failed release target add-back demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-target-language-add-failed');
    expect(isContentCmsPreviewMode('release-target-language-add-failed')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-target-language-add-failed')).toBe(
      'release-target-language-add-failed',
    );
  });

  it('keeps the conflicting release target add-back demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-target-language-add-conflict');
    expect(isContentCmsPreviewMode('release-target-language-add-conflict')).toBe(true);
    expect(getContentCmsPreviewMode('?cmsPreview=release-target-language-add-conflict')).toBe(
      'release-target-language-add-conflict',
    );
  });

  it('keeps the unresolved conflicting release target add-back demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-target-language-add-conflict-still-removed');
    expect(isContentCmsPreviewMode('release-target-language-add-conflict-still-removed')).toBe(
      true,
    );
    expect(
      getContentCmsPreviewMode('?cmsPreview=release-target-language-add-conflict-still-removed'),
    ).toBe('release-target-language-add-conflict-still-removed');
  });

  it('keeps the failed conflicting release target refresh demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain('release-target-language-add-conflict-refresh-failed');
    expect(isContentCmsPreviewMode('release-target-language-add-conflict-refresh-failed')).toBe(
      true,
    );
    expect(
      getContentCmsPreviewMode('?cmsPreview=release-target-language-add-conflict-refresh-failed'),
    ).toBe('release-target-language-add-conflict-refresh-failed');
  });

  it('keeps the still-missing failed conflicting release target retry demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain(
      'release-target-language-add-conflict-refresh-failed-retry-still-removed',
    );
    expect(
      isContentCmsPreviewMode(
        'release-target-language-add-conflict-refresh-failed-retry-still-removed',
      ),
    ).toBe(true);
    expect(
      getContentCmsPreviewMode(
        '?cmsPreview=release-target-language-add-conflict-refresh-failed-retry-still-removed',
      ),
    ).toBe('release-target-language-add-conflict-refresh-failed-retry-still-removed');
  });

  it('keeps the repeated conflicting release target retry demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain(
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again',
    );
    expect(
      isContentCmsPreviewMode(
        'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again',
      ),
    ).toBe(true);
    expect(
      getContentCmsPreviewMode(
        '?cmsPreview=release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again',
      ),
    ).toBe(
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again',
    );
  });

  it('keeps the failed repeated conflicting release target retry demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain(
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed',
    );
    expect(
      isContentCmsPreviewMode(
        'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed',
      ),
    ).toBe(true);
    expect(
      getContentCmsPreviewMode(
        '?cmsPreview=release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed',
      ),
    ).toBe(
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed',
    );
  });

  it('keeps the restored failed repeated conflicting release target retry demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain(
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored',
    );
    expect(
      isContentCmsPreviewMode(
        'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored',
      ),
    ).toBe(true);
    expect(
      getContentCmsPreviewMode(
        '?cmsPreview=release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored',
      ),
    ).toBe(
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored',
    );
  });

  it('keeps the restored direct add after failed repeated retry demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain(
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-add-restored',
    );
    expect(
      isContentCmsPreviewMode(
        'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-add-restored',
      ),
    ).toBe(true);
    expect(
      getContentCmsPreviewMode(
        '?cmsPreview=release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-add-restored',
      ),
    ).toBe(
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-add-restored',
    );
  });

  it('keeps the restored third failed repeated retry demo route in the shared preview registry', () => {
    expect(contentCmsPreviewModes).toContain(
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored',
    );
    expect(
      isContentCmsPreviewMode(
        'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored',
      ),
    ).toBe(true);
    expect(
      getContentCmsPreviewMode(
        '?cmsPreview=release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored',
      ),
    ).toBe(
      'release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored',
    );
  });

  it('falls back to stale recovery for unknown preview routes', () => {
    expect(isContentCmsPreviewMode('not-a-preview')).toBe(false);
    expect(getContentCmsPreviewMode('?cmsPreview=not-a-preview')).toBe('stale-recovery');
  });
});
