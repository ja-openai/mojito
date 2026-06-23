import { describe, expect, it } from 'vitest';

import contentCmsStyles from './admin-content-cms-page.css?raw';

describe('admin content CMS responsive styles', () => {
  it('stacks the dirty author save bar on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__authoring-save-bar {
    align-items: stretch;
    flex-direction: column;
  }

  .content-cms-admin-page__authoring-save-bar .settings-button {
    width: 100%;
  }`,
    );
  });

  it('stretches the collapsed copy collection switcher on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__sidebar-toggle {
    justify-content: space-between;
    width: 100%;
  }`,
    );
  });

  it('keeps the collapsed phone copy collection switcher before authoring', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__layout {
    grid-template-columns: minmax(0, 1fr);
    grid-template-areas:
      'sidebar'
      'main';
  }`,
    );
  });

  it('stretches release review return actions on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__release-review-return {
    align-items: stretch;
  }

  .content-cms-admin-page__release-review-return .settings-button {
    width: 100%;
  }`,
    );
  });

  it('stretches copy collection retry actions on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__query-state .settings-button {
    width: 100%;
  }`,
    );
  });

  it('stretches stale saved-copy recovery actions on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__source-copy-refresh > .settings-button {
    width: 100%;
  }`,
    );
  });

  it('stretches saved item detail saves on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__block-form > .settings-button {
    width: 100%;
  }`,
    );
  });

  it('stacks ready release handoff actions on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__block-release,
  .content-cms-admin-page__block-release-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .content-cms-admin-page__block-release-actions .settings-button {
    width: 100%;
  }`,
    );
  });

  it('stretches the author release action on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__release-panel.is-author .content-cms-admin-page__release-actions {
    align-items: stretch;
  }

  .content-cms-admin-page__release-panel.is-author
    .content-cms-admin-page__release-actions
    .settings-button {
    width: 100%;
  }`,
    );
  });

  it('stacks first-task author actions on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__clean-start .content-cms-admin-page__actions,
  .content-cms-admin-page__new-block > .content-cms-admin-page__actions {
    align-items: stretch;
    flex-direction: column;
  }

  .content-cms-admin-page__clean-start .content-cms-admin-page__actions .settings-button,
  .content-cms-admin-page__new-block > .content-cms-admin-page__actions .settings-button {
    width: 100%;
  }`,
    );
  });

  it('stretches optional content item details on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__authoring-options-actions {
    align-items: stretch;
  }

  .content-cms-admin-page__authoring-options-actions
    .content-cms-admin-page__authoring-options-trigger {
    justify-content: space-between;
    width: 100%;
  }`,
    );
  });

  it('stretches placement disclosures on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__block-details-trigger,
  .content-cms-admin-page__field-context-details summary {
    width: 100%;
  }

  .content-cms-admin-page__block-details-trigger {
    justify-content: space-between;
  }`,
    );
  });

  it('stacks later content item actions on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__block-form > .content-cms-admin-page__actions {
    align-items: stretch;
    flex-direction: column;
  }

  .content-cms-admin-page__block-form > .content-cms-admin-page__actions .settings-button {
    width: 100%;
  }`,
    );
  });

  it('collapses the author workspace grid on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__workflow-grid,
  .content-cms-admin-page__technical-fields-grid,
  .content-cms-admin-page__inline-form,
  .content-cms-admin-page__workspace-shell,
  .content-cms-admin-page__clean-start,
  .content-cms-admin-page__authoring-form-section-grid,
  .content-cms-admin-page__new-block,
  .content-cms-admin-page__new-block-copy-piece,
  .content-cms-admin-page__block-form {
    grid-template-columns: minmax(0, 1fr);
  }

  .content-cms-admin-page__copy-block-details-header {
    display: grid;
  }

  .content-cms-admin-page__form--wide,
  .content-cms-admin-page__field-span {
    grid-column: auto;
  }

  .content-cms-admin-page__entry-list {
    padding: var(--space-2);
  }

  .content-cms-admin-page__editor-header,
  .content-cms-admin-page__field-editor-header,
  .content-cms-admin-page__field-actions,
  .content-cms-admin-page__field-localization,
  .content-cms-admin-page__inline-translation-header,
  .content-cms-admin-page__inline-translation-actions,
  .content-cms-admin-page__inline-translation-state,
  .content-cms-admin-page__inline-translation-next-step-header,
  .content-cms-admin-page__new-block-copy-list-header,
  .content-cms-admin-page__workspace-header,
  .content-cms-admin-page__new-block-header,
  .content-cms-admin-page__field-list-header {
    flex-direction: column;
  }

  .content-cms-admin-page__editor-header,
  .content-cms-admin-page__field-editor-header {
    align-items: stretch;
  }`,
    );
  });

  it('stretches saved-field author actions on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__field-actions {
    align-items: stretch;
  }

  .content-cms-admin-page__field-actions > .settings-button {
    width: 100%;
  }`,
    );
  });

  it('stacks saved-field localization status on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__field-label-row {
    align-items: start;
    flex-direction: column;
  }

  .content-cms-admin-page__field-localization {
    align-items: stretch;
  }

  .content-cms-admin-page__inline-translation-tools-trigger {
    margin-left: 0;
  }

  .content-cms-admin-page__field-localization > div,
  .content-cms-admin-page__inline-translation-state > div {
    min-width: 0;
    width: 100%;
  }

  .content-cms-admin-page__required-hint,
  .content-cms-admin-page__field-localization span,
  .content-cms-admin-page__inline-translation-state strong,
  .content-cms-admin-page__inline-translation-state span {
    overflow-wrap: anywhere;
  }

  .content-cms-admin-page__status-stack {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    justify-items: start;
    width: 100%;
    min-width: 0;
  }`,
    );
  });

  it('lets long author status pills wrap on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__status {
    overflow-wrap: anywhere;
    white-space: normal;
  }`,
    );
  });

  it('stretches saved item header actions on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__editor-header .content-cms-admin-page__status-stack .settings-button,
  .content-cms-admin-page__editor-header .content-cms-admin-page__authoring-options-trigger {
    width: 100%;
  }

  .content-cms-admin-page__editor-header .content-cms-admin-page__authoring-options-trigger {
    justify-content: space-between;
  }`,
    );
  });

  it('stacks inline translation next-step headers on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__field-localization,
  .content-cms-admin-page__inline-translation-header,
  .content-cms-admin-page__inline-translation-actions,
  .content-cms-admin-page__inline-translation-state,
  .content-cms-admin-page__inline-translation-next-step-header,
  .content-cms-admin-page__new-block-copy-list-header,
  .content-cms-admin-page__workspace-header,
  .content-cms-admin-page__new-block-header,
  .content-cms-admin-page__field-list-header {
    flex-direction: column;
  }`,
    );
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__inline-translation-next-step-header {
    align-items: stretch;
  }`,
    );
  });

  it('stretches translation language setup on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__target-locale-setup {
    align-items: stretch;
  }

  .content-cms-admin-page__target-locale-select,
  .content-cms-admin-page__target-locale-setup .settings-button,
  .content-cms-admin-page__target-locale-state .settings-button {
    width: 100%;
  }`,
    );
  });

  it('stretches translation disclosure controls on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__locale-readiness-toggle,
  .content-cms-admin-page__target-locale-catalog-toggle {
    text-align: left;
    width: 100%;
  }`,
    );
  });

  it('stretches optional detailed translation review links on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__text-unit-link {
    width: 100%;
  }`,
    );
  });

  it('stretches inline translation recovery actions on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__inline-translation-actions {
    align-items: stretch;
  }`,
    );
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__inline-translation-actions > .settings-button {
    width: 100%;
  }`,
    );
  });

  it('stacks inline translation language choices on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__inline-translation-locales {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
  }

  .content-cms-admin-page__inline-translation-locale {
    min-width: 0;
    width: 100%;
  }`,
    );
  });

  it('stacks inline translation save actions on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__inline-translation-save-actions,
  .content-cms-admin-page__inline-translation-save-options {
    align-items: stretch;
    flex-direction: column;
  }

  .content-cms-admin-page__inline-translation-save-actions .settings-button,
  .content-cms-admin-page__inline-translation-save-options .settings-button {
    width: 100%;
  }`,
    );
  });

  it('stacks long release action rows on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__release-change-item,
  .content-cms-admin-page__release-blocker {
    align-items: stretch;
    flex-direction: column;
  }`,
    );
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__release-change-action,
  .content-cms-admin-page__release-change-group-header > span:last-child,
  .content-cms-admin-page__release-change-subgroup-header > span:last-child,
  .content-cms-admin-page__release-blocker-action {
    white-space: normal;
  }`,
    );
  });

  it('stretches release disclosure toggles on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__release-change-toggle,
  .content-cms-admin-page__release-blocker-toggle {
    text-align: left;
    width: 100%;
  }`,
    );
  });

  it('stretches long included release items on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `.content-cms-admin-page__release-included-item {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  max-width: 100%;
  min-height: 2rem;`,
    );
    expect(contentCmsStyles).toContain(
      `.content-cms-admin-page__release-included-item strong {
  min-width: 0;
  overflow-wrap: anywhere;
}`,
    );
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__release-included-item {
    align-items: flex-start;
    border-radius: var(--radius-2);
    width: 100%;
  }

  .content-cms-admin-page__release-included-state {
    flex: 0 0 auto;
  }`,
    );
  });

  it('stacks release detail summaries on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__release-details-summary {
    align-items: stretch;
    flex-direction: column;
  }`,
    );
  });

  it('stretches release detail disclosures on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__release-details-trigger {
    text-align: left;
    width: 100%;
  }`,
    );
  });

  it('stacks grouped release headers on phone widths', () => {
    expect(contentCmsStyles).toContain(
      `  .content-cms-admin-page__release-change-group-header,
  .content-cms-admin-page__release-change-subgroup-header,
  .content-cms-admin-page__release-blocker-group-header {
    align-items: stretch;
    flex-direction: column;
  }`,
    );
  });
});
