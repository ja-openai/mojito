import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StrictMode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { contentCmsPreviewModes } from './content-cms-preview-mode';
import { ContentCmsAuthoringPreview } from './ContentCmsAuthoringPreview';

vi.setConfig({ testTimeout: 15_000 });

const primaryAuthoringTechnicalInternals =
  /Admin tools|CMS|Mojito|schema|mapping|variant|publish|repository|package|snapshot|JSON|text unit|source locale|target locale/i;
const authorFacingPreviewModes = contentCmsPreviewModes;

function getPrimaryAuthoringAccessibleCopy() {
  return Array.from(document.querySelectorAll('[aria-label], [title], [aria-describedby]'))
    .flatMap((element) => [
      element.getAttribute('aria-label'),
      element.getAttribute('title'),
      ...(element.getAttribute('aria-describedby') ?? '')
        .split(/\s+/)
        .filter(Boolean)
        .map((describedById) => document.getElementById(describedById)?.textContent),
    ])
    .filter((value): value is string => value != null);
}

function targetLocalePicker() {
  const picker = screen.getByRole('region', { name: 'Choose translation languages' });
  if (!(picker instanceof HTMLElement)) {
    throw new Error('Choose translation languages panel not found');
  }
  return picker;
}

describe('ContentCmsAuthoringPreview', () => {
  beforeEach(() => {
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=authoring-demo');
  });

  async function openSavedItemDetails(user: ReturnType<typeof userEvent.setup>) {
    const optionsTrigger = screen.getByRole('button', { name: 'Item details' });
    if (optionsTrigger.getAttribute('aria-expanded') !== 'true') {
      await user.click(optionsTrigger);
    }
    return optionsTrigger;
  }

  async function openNewContentItemForm(user: ReturnType<typeof userEvent.setup>) {
    if (screen.queryByRole('button', { name: 'Write new content item' }) == null) {
      await openSavedItemDetails(user);
    }
    await user.click(screen.getByRole('button', { name: 'Write new content item' }));
  }

  async function openAuthorReleaseDetails(
    user: ReturnType<typeof userEvent.setup>,
    releasePanel: HTMLElement,
  ) {
    const releaseDetails = within(releasePanel).getByRole('button', {
      name: 'Show release details',
    });
    if (releaseDetails.getAttribute('aria-expanded') !== 'true') {
      await user.click(releaseDetails);
    }
  }

  it('keeps the seeded multi-piece walkthrough editable', async () => {
    const user = userEvent.setup();
    render(<ContentCmsAuthoringPreview />);

    expect(screen.getByRole('complementary', { name: 'Copy collections' })).toBeVisible();
    expect(
      screen.getByRole('button', { name: 'Open copy collection Growth email copy' }),
    ).toBeVisible();
    expect(
      screen.queryByRole('button', { name: 'Growth email copySignup and onboarding messages' }),
    ).not.toBeInTheDocument();
    const copyCollectionsToggle = document.querySelector('.content-cms-admin-page__sidebar-toggle');
    expect(copyCollectionsToggle).not.toBeNull();
    expect(copyCollectionsToggle).toHaveTextContent('Copy collections');
    expect(copyCollectionsToggle).not.toHaveTextContent('>');
    expect(
      copyCollectionsToggle?.querySelector('.content-cms-admin-page__sidebar-toggle-chevron'),
    ).toHaveAttribute('aria-hidden', 'true');
    const authoringLayout = document.querySelector('.content-cms-admin-page__layout');
    expect(authoringLayout?.firstElementChild).toHaveClass('content-cms-admin-page__sidebar');
    expect(authoringLayout?.lastElementChild).toHaveClass('content-cms-admin-page__main');
    expect(authoringLayout?.lastElementChild?.tagName).toBe('SECTION');
    expect(screen.getAllByRole('main')).toHaveLength(1);
    expect(screen.getByRole('region', { name: 'Product copy editor' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'New copy collection' })).toBeVisible();
    expect(screen.queryByLabelText('Product copy workflow')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Product copy workspace')).toBeVisible();
    expect(document.body).not.toHaveTextContent(primaryAuthoringTechnicalInternals);
    expect(getPrimaryAuthoringAccessibleCopy()).not.toEqual(
      expect.arrayContaining([expect.stringMatching(primaryAuthoringTechnicalInternals)]),
    );
    expect(screen.getByRole('textbox', { name: 'CTA label source copy' })).toBeEnabled();
    expect(screen.getByRole('textbox', { name: 'CTA label source copy' })).toHaveAttribute(
      'rows',
      '2',
    );
    expect(screen.getByRole('textbox', { name: 'CTA label translator note' })).toHaveAttribute(
      'rows',
      '1',
    );
    expect(screen.queryByText('Field 1 of 2')).not.toBeInTheDocument();
    expect(screen.queryByText('Field 2 of 2')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Copy preview')).not.toBeInTheDocument();
    expect(screen.queryByText('Source saved')).not.toBeInTheDocument();
    expect(
      screen.queryByText('Edit this content item, or write the next one. Writing in English.'),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText(
        'Choose a content item to edit, or write the next one. Writing in English.',
      ),
    ).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Content items' })).not.toBeInTheDocument();
    const editorHeader = screen
      .getByRole('heading', { name: 'Welcome email' })
      .closest('.content-cms-admin-page__editor-header');
    if (!(editorHeader instanceof HTMLElement)) {
      throw new Error('Editor header not found');
    }
    expect(screen.queryByRole('navigation', { name: 'Content items' })).not.toBeInTheDocument();
    expect(within(editorHeader).getByRole('button', { name: 'Item details' })).toBeVisible();
    expect(
      within(editorHeader).getByRole('button', { name: 'Write new content item' }),
    ).toBeVisible();
    expect(editorHeader.querySelector('.content-cms-admin-page__editor-progress')).toBeNull();
    expect(screen.queryByText('2/2 fields saved')).not.toBeInTheDocument();
    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement)) {
      throw new Error('Headline field not found');
    }
    expect(
      within(headlineField).queryByRole('button', { name: 'Save source changes' }),
    ).not.toBeInTheDocument();
    expect(
      within(headlineField).queryByRole('button', { name: 'Save placement details' }),
    ).not.toBeInTheDocument();
    expect(
      within(headlineField).getByText('Where this copy appears', { selector: 'summary' }),
    ).toBeVisible();
    const headlinePlacementDetails = within(headlineField).getByLabelText(
      'Headline placement details',
    );
    expect(headlinePlacementDetails).not.toBeVisible();
    expect(headlinePlacementDetails).toHaveAttribute('tabindex', '-1');
    await user.click(
      within(headlineField).getByText('Where this copy appears', { selector: 'summary' }),
    );
    await user.type(within(headlineField).getByLabelText('Headline placement details'), ' updated');
    expect(
      within(headlineField).getByRole('button', { name: 'Save placement details' }),
    ).toBeVisible();
    const optionsTrigger = await openSavedItemDetails(user);
    const optionsPanel = screen.getByLabelText('Item details');
    const contentItemDetails = screen.getByLabelText('Content item details');
    expect(optionsTrigger).toHaveAttribute('aria-controls', optionsPanel.id);
    expect(
      within(editorHeader).getByRole('button', { name: 'Write new content item' }),
    ).toBeVisible();
    expect(
      within(contentItemDetails).queryByRole('button', { name: 'Save copy details' }),
    ).not.toBeInTheDocument();
    await user.type(
      within(contentItemDetails).getByLabelText('Where this item appears'),
      ' updated',
    );
    expect(
      within(contentItemDetails).getByRole('button', { name: 'Save copy details' }),
    ).toBeVisible();
    expect(within(headlineField).queryByLabelText('Headline translation editor')).toBeNull();
    expect(within(headlineField).getByText('Needs translation')).toBeVisible();
    expect(within(headlineField).getByLabelText('French (France) Needs translation')).toBeVisible();
    expect(within(headlineField).queryByText('Translation')).not.toBeInTheDocument();
    expect(within(headlineField).getByText('Write French (France)')).toBeVisible();
    expect(within(headlineField).queryByText('French (France) needs translation')).toBeNull();
    expect(within(headlineField).queryByText('Write French (France) translation')).toBeNull();
    expect(
      within(headlineField).queryByText(
        '1 field needs translation. Write or update this translation, then mark it ready for review when it is complete.',
      ),
    ).not.toBeInTheDocument();
    await user.click(
      within(headlineField).getByRole('button', { name: 'Write French (France) translation' }),
    );
    expect(
      screen.getByRole('heading', { name: 'Write French (France) translation' }),
    ).toBeVisible();
    expect(
      await within(headlineField).findByLabelText('French (France) translation'),
    ).toBeVisible();
    await waitFor(() =>
      expect(within(headlineField).getByLabelText('French (France) translation')).toHaveFocus(),
    );
    expect(
      within(headlineField).queryByText('French (France) translation is unavailable.'),
    ).not.toBeInTheDocument();
    expect(
      within(headlineField).queryByText(
        '1 field needs translation. Write or update this translation, then mark it ready for review when it is complete.',
      ),
    ).not.toBeInTheDocument();
    expect(within(headlineField).queryByText('Translation')).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(primaryAuthoringTechnicalInternals);
    expect(getPrimaryAuthoringAccessibleCopy()).not.toEqual(
      expect.arrayContaining([expect.stringMatching(primaryAuthoringTechnicalInternals)]),
    );
    expect(screen.getByRole('button', { name: 'Hide translation editor' })).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Hide translation editor' }));
    const restoredTranslationAction = within(headlineField).getByRole('button', {
      name: 'Write French (France) translation',
    });
    await waitFor(() => expect(restoredTranslationAction).toHaveFocus());
    expect(within(headlineField).queryByLabelText('Headline translation editor')).toBeNull();
  });

  it('keeps author-facing preview modes out of the failed admin framing', () => {
    authorFacingPreviewModes.forEach((previewMode) => {
      window.history.pushState({}, '', `/settings/system/content-cms?cmsPreview=${previewMode}`);
      const { unmount } = render(<ContentCmsAuthoringPreview />);

      expect(document.body, `${previewMode} visible copy`).not.toHaveTextContent(
        primaryAuthoringTechnicalInternals,
      );
      expect(getPrimaryAuthoringAccessibleCopy(), `${previewMode} accessible copy`).not.toEqual(
        expect.arrayContaining([expect.stringMatching(primaryAuthoringTechnicalInternals)]),
      );

      unmount();
    });
  });

  it('tabs through the clean inline translation task before the next source field', async () => {
    const user = userEvent.setup();
    render(<ContentCmsAuthoringPreview />);

    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement) || !(ctaField instanceof HTMLElement)) {
      throw new Error('Preview copy fields not found');
    }

    await user.click(
      within(headlineField).getByRole('button', { name: 'Write French (France) translation' }),
    );
    await waitFor(() =>
      expect(within(headlineField).getByLabelText('French (France) translation')).toHaveFocus(),
    );
    await user.tab();
    expect(within(headlineField).getByRole('button', { name: 'Send for review' })).toHaveFocus();
    await user.tab();
    expect(within(headlineField).getByRole('button', { name: 'Other actions' })).toHaveFocus();
    await user.tab();
    expect(
      within(headlineField).getByRole('button', { name: 'Hide translation editor' }),
    ).toHaveFocus();
    await user.tab();
    expect(
      within(headlineField).getByRole('button', { name: 'More translation help' }),
    ).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('textbox', { name: 'CTA label source copy' })).toHaveFocus();
    expect(within(headlineField).queryByLabelText('Headline translation editor')).toBeNull();
    expect(headlineField).not.toHaveClass('is-active');
    expect(ctaField).toHaveClass('is-active');
  });

  it('keeps dirty inline translation on the active field until reset reopens requested source copy', async () => {
    const user = userEvent.setup();
    render(<ContentCmsAuthoringPreview />);

    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement) || !(ctaField instanceof HTMLElement)) {
      throw new Error('Preview copy fields not found');
    }

    await user.click(
      within(headlineField).getByRole('button', { name: 'Write French (France) translation' }),
    );
    await user.type(
      within(headlineField).getByLabelText('French (France) translation'),
      ' revised',
    );
    await user.click(within(ctaField).getByLabelText('CTA label source copy'));

    expect(
      within(headlineField).getByText('Finish this translation to open CTA label.'),
    ).toBeVisible();
    expect(
      within(headlineField).getByText(
        'Save or reset French (France) translation edits; CTA label will open next.',
      ),
    ).toBeVisible();
    expect(headlineField).toHaveClass('is-active');
    expect(ctaField).not.toHaveClass('is-active');
    expect(within(ctaField).getByLabelText('CTA label source copy')).not.toHaveFocus();

    await user.click(within(headlineField).getByRole('button', { name: 'Reset' }));

    await waitFor(() => expect(ctaField).toHaveClass('is-active'));
    await waitFor(() =>
      expect(within(ctaField).getByLabelText('CTA label source copy')).toHaveFocus(),
    );
  });

  it('keeps refresh-failed inline translation field switch queued until retry reopens requested source copy', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=translation-refresh-failed',
    );

    render(<ContentCmsAuthoringPreview />);

    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement) || !(ctaField instanceof HTMLElement)) {
      throw new Error('Preview copy fields not found');
    }

    await user.click(
      within(headlineField).getByRole('button', { name: 'Write French (France) translation' }),
    );
    expect(
      await within(headlineField).findByText('Translation status could not refresh.'),
    ).toBeVisible();

    await user.click(within(ctaField).getByLabelText('CTA label source copy'));

    expect(
      within(headlineField).getByText('Refresh translation status to open CTA label.'),
    ).toBeVisible();
    expect(
      within(headlineField).getByText(
        'Try again; CTA label will open after status refresh succeeds.',
      ),
    ).toBeVisible();
    expect(headlineField).toHaveClass('is-active');
    expect(ctaField).not.toHaveClass('is-active');
    expect(within(ctaField).getByLabelText('CTA label source copy')).not.toHaveFocus();

    await user.click(within(headlineField).getByRole('button', { name: 'Try again' }));
    expect(
      await within(headlineField).findByRole('button', { name: 'Refreshing status...' }),
    ).toBeDisabled();

    await waitFor(() => expect(ctaField).toHaveClass('is-active'));
    await waitFor(() =>
      expect(within(ctaField).getByLabelText('CTA label source copy')).toHaveFocus(),
    );
  });

  it('reopens requested translations after the preview inline editor readiness retry succeeds', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=translation-editor-unavailable',
    );

    render(
      <StrictMode>
        <ContentCmsAuthoringPreview />
      </StrictMode>,
    );

    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement)) {
      throw new Error('Headline field not found');
    }
    expect(
      await within(headlineField).findByText('French (France) translation could not open.'),
    ).toBeVisible();
    expect(within(headlineField).getByText('Try again to open it.')).toBeVisible();
    expect(within(headlineField).queryByText('Translation')).not.toBeInTheDocument();
    expect(
      within(headlineField).queryByLabelText('Headline translation status'),
    ).not.toBeInTheDocument();
    const tryAgain = within(headlineField).getByRole('button', { name: 'Try again' });
    await waitFor(() => expect(tryAgain).toHaveFocus());

    await user.click(tryAgain);

    expect(await within(headlineField).findByRole('button', { name: 'Opening...' })).toBeDisabled();
    const translation = await within(headlineField).findByLabelText('French (France) translation');
    await waitFor(() => expect(translation).toHaveFocus());
  });

  it('keeps selected translation load retries in author language', async () => {
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=translation-load-error',
    );

    render(
      <StrictMode>
        <ContentCmsAuthoringPreview />
      </StrictMode>,
    );

    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement)) {
      throw new Error('Headline field not found');
    }
    expect(
      await within(headlineField).findByText('French (France) translation could not load.'),
    ).toBeVisible();
    expect(within(headlineField).getByText('Try again to open it.')).toBeVisible();
    expect(within(headlineField).getByRole('button', { name: 'Try again' })).toBeVisible();
    expect(
      within(headlineField).queryByText('Try again to reopen this translation.'),
    ).not.toBeInTheDocument();
  });

  it('reopens a configured translation after a preview requested language was removed', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=translation-language-removed',
    );

    render(
      <StrictMode>
        <ContentCmsAuthoringPreview />
      </StrictMode>,
    );

    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement)) {
      throw new Error('Headline field not found');
    }
    expect(
      await within(headlineField).findByText('Japanese (Japan) is not set up for this copy.'),
    ).toBeVisible();
    const addJapanese = within(headlineField).getByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(addJapanese).toHaveFocus());
    expect(
      within(headlineField).getByText(
        'Add this translation language again, or continue with French (France).',
      ),
    ).toBeVisible();
    expect(
      within(headlineField).getByText(
        'Add Japanese (Japan) again, or continue with French (France).',
      ),
    ).toBeVisible();
    expect(within(headlineField).queryByRole('button', { name: 'Switch language' })).toBeNull();
    expect(
      within(headlineField).queryByRole('button', { name: 'Hide translation editor' }),
    ).toBeNull();
    const continueWithFrench = within(headlineField).getByRole('button', {
      name: 'Continue with French (France)',
    });
    expect(continueWithFrench).toHaveClass('settings-button--ghost');

    await user.click(continueWithFrench);
    expect(
      within(headlineField).queryByText('Japanese (Japan) is not set up for this copy.'),
    ).not.toBeInTheDocument();

    const frenchTranslation = await within(headlineField).findByLabelText(
      'French (France) translation',
    );
    await waitFor(() => expect(frenchTranslation).toHaveFocus());
    expect(
      within(headlineField).queryByText('Japanese (Japan) is not set up for this copy.'),
    ).not.toBeInTheDocument();
  });

  it('opens a scoped preview language chooser when multiple configured translations remain after removal', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=translation-language-removed-multiple-alternates',
    );

    render(
      <StrictMode>
        <ContentCmsAuthoringPreview />
      </StrictMode>,
    );

    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement)) {
      throw new Error('Headline field not found');
    }
    expect(
      await within(headlineField).findByText('Japanese (Japan) is not set up for this copy.'),
    ).toBeVisible();
    const addJapanese = within(headlineField).getByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(addJapanese).toHaveFocus());
    expect(
      within(headlineField).getByText(
        'Add this translation language again, or choose another translation language.',
      ),
    ).toBeVisible();
    expect(
      within(headlineField).getByText(
        'Add Japanese (Japan) again, or choose another translation language.',
      ),
    ).toBeVisible();
    expect(
      within(headlineField).queryByRole('button', {
        name: 'Continue with French (France)',
      }),
    ).toBeNull();
    expect(within(headlineField).queryByRole('button', { name: 'Switch language' })).toBeNull();
    expect(
      within(headlineField).queryByRole('button', { name: 'Hide translation editor' }),
    ).toBeNull();
    const chooseAnotherLanguage = within(headlineField).getByRole('button', {
      name: 'Choose another language',
    });
    expect(chooseAnotherLanguage).toHaveClass('settings-button--ghost');

    await user.click(chooseAnotherLanguage);

    const frenchLocale = within(headlineField).getByRole('button', {
      name: 'French (France) Needs translation',
    });
    await waitFor(() => expect(frenchLocale).toHaveFocus());
    expect(within(headlineField).getByRole('button', { name: 'Hide languages' })).toBeVisible();

    await user.click(
      within(headlineField).getByRole('button', { name: 'German (Germany) Approved' }),
    );

    const germanTranslation = await within(headlineField).findByLabelText(
      'German (Germany) translation',
    );
    await waitFor(() => expect(germanTranslation).toHaveFocus());
    expect(
      within(headlineField).queryByText('Japanese (Japan) is not set up for this copy.'),
    ).not.toBeInTheDocument();
  });

  it('reopens an editable preview translation after requested language access is missing', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=translation-locale-no-access',
    );

    render(
      <StrictMode>
        <ContentCmsAuthoringPreview />
      </StrictMode>,
    );

    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement)) {
      throw new Error('Headline field not found');
    }
    expect(
      await within(headlineField).findByRole('heading', {
        name: 'You cannot edit French (France)',
      }),
    ).toBeVisible();
    expect(
      within(headlineField).getByText(
        'Choose another translation language you can edit, or ask an admin to give you access to French (France).',
      ),
    ).toBeVisible();
    expect(
      within(headlineField).queryByLabelText('French (France) translation'),
    ).not.toBeInTheDocument();
    const chooseAnotherLanguage = await within(headlineField).findByRole('button', {
      name: 'Choose another language',
    });
    await waitFor(() => expect(chooseAnotherLanguage).toHaveFocus());

    await user.click(chooseAnotherLanguage);
    const unavailableFrenchLocale = within(headlineField).getByRole('button', {
      name: 'French (France) No access',
    });
    expect(unavailableFrenchLocale).toBeVisible();
    expect(unavailableFrenchLocale).toBeDisabled();
    const translationLanguages = within(headlineField).getByRole('group', {
      name: 'Translation languages',
    });
    expect(within(translationLanguages).getByText('No access')).toBeVisible();
    expect(within(translationLanguages).getByText('Open')).toBeVisible();
    expect(within(translationLanguages).queryByText('Approved')).not.toBeInTheDocument();
    await user.click(
      within(headlineField).getByRole('button', { name: 'Japanese (Japan) Approved' }),
    );

    const japaneseTranslation = await within(headlineField).findByLabelText(
      'Japanese (Japan) translation',
    );
    await waitFor(() => expect(japaneseTranslation).toHaveFocus());
    expect(
      within(headlineField).queryByRole('heading', {
        name: 'You cannot edit French (France)',
      }),
    ).not.toBeInTheDocument();
  });

  it('keeps removed preview translation add recovery singular when no configured locale is editable', async () => {
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=translation-language-removed-no-access',
    );

    render(
      <StrictMode>
        <ContentCmsAuthoringPreview />
      </StrictMode>,
    );

    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement)) {
      throw new Error('Headline field not found');
    }
    expect(
      await within(headlineField).findByText('Japanese (Japan) is not set up for this copy.'),
    ).toBeVisible();
    expect(
      within(headlineField).getByText(
        'Ask an admin to give you access to another translation language, or add Japanese (Japan) again.',
      ),
    ).toBeVisible();
    const addJapanese = within(headlineField).getByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(addJapanese).toHaveFocus());
    expect(within(headlineField).queryByRole('button', { name: 'Switch language' })).toBeNull();
    expect(
      within(headlineField).queryByRole('button', { name: 'French (France) No access' }),
    ).toBeNull();
  });

  it('keeps removed preview translation add recovery visible when no configured locale remains', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=translation-language-removed-no-targets',
    );

    render(
      <StrictMode>
        <ContentCmsAuthoringPreview />
      </StrictMode>,
    );

    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement)) {
      throw new Error('Headline field not found');
    }
    expect(
      await within(headlineField).findByText('Japanese (Japan) is not set up for this copy.'),
    ).toBeVisible();
    expect(
      within(headlineField).getByText(
        'Add this translation language again before editing this translation.',
      ),
    ).toBeVisible();
    expect(
      within(headlineField).getByText(
        'Add Japanese (Japan) again before editing this translation.',
      ),
    ).toBeVisible();
    const addJapanese = within(headlineField).getByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(addJapanese).toHaveFocus());
    expect(within(headlineField).queryByRole('button', { name: 'Switch language' })).toBeNull();
    expect(screen.queryByRole('region', { name: 'Choose translation languages' })).toBeNull();
    await user.click(addJapanese);
    expect(
      await within(headlineField).findByLabelText('Japanese (Japan) translation'),
    ).toBeVisible();
    expect(
      within(headlineField).queryByText('Japanese (Japan) is not set up for this copy.'),
    ).toBeNull();
  });

  it('restores source-only translation setup after closing removed preview translation recovery', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=translation-language-removed-no-targets',
    );

    render(
      <StrictMode>
        <ContentCmsAuthoringPreview />
      </StrictMode>,
    );

    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement)) {
      throw new Error('Headline field not found');
    }
    expect(
      await within(headlineField).findByText('Japanese (Japan) is not set up for this copy.'),
    ).toBeVisible();
    expect(screen.queryByRole('region', { name: 'Choose translation languages' })).toBeNull();

    await user.click(
      within(headlineField).getByRole('button', { name: 'Hide translation editor' }),
    );

    const targetLocaleSetup = await screen.findByRole('region', {
      name: 'Choose translation languages',
    });
    expect(
      within(targetLocaleSetup).getByRole('heading', {
        level: 3,
        name: 'Choose translation languages',
      }),
    ).toBeVisible();
    expect(targetLocaleSetup.closest('.content-cms-admin-page__field-outline-card')).toBeNull();
    await waitFor(() =>
      expect(
        within(targetLocaleSetup).getByRole('button', { name: 'Choose translation languages' }),
      ).toHaveFocus(),
    );
    expect(
      within(headlineField).queryByText('Japanese (Japan) is not set up for this copy.'),
    ).toBeNull();
  });

  it('keeps removed preview translation retry local when that language is not supported', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=translation-language-removed-unsupported',
    );

    render(
      <StrictMode>
        <ContentCmsAuthoringPreview />
      </StrictMode>,
    );

    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement)) {
      throw new Error('Headline field not found');
    }
    expect(
      await within(headlineField).findByText('Japanese (Japan) is not set up for this copy.'),
    ).toBeVisible();
    expect(
      within(headlineField).getByText(
        'Ask an admin to add this translation language before editing this translation.',
      ),
    ).toBeVisible();
    expect(
      within(headlineField).getByText(
        'Japanese (Japan) is not available in supported translation languages. Ask an admin to add it, then try again.',
      ),
    ).toBeVisible();
    const tryAgain = within(headlineField).getByRole('button', { name: 'Try again' });
    await waitFor(() => expect(tryAgain).toHaveFocus());
    expect(
      within(headlineField).queryByRole('button', { name: 'Add Japanese (Japan)' }),
    ).toBeNull();
    expect(screen.queryByRole('region', { name: 'Choose translation languages' })).toBeNull();
    await user.click(tryAgain);
    expect(within(headlineField).getByRole('button', { name: 'Try again' })).toBeVisible();
  });

  it('keeps removed preview translation locale loading local before add recovery', async () => {
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=translation-language-removed-loading',
    );

    render(
      <StrictMode>
        <ContentCmsAuthoringPreview />
      </StrictMode>,
    );

    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement)) {
      throw new Error('Headline field not found');
    }
    expect(
      await within(headlineField).findByText('Japanese (Japan) is not set up for this copy.'),
    ).toBeVisible();
    expect(
      within(headlineField).getByText(
        'Checking whether this translation language can be added again.',
      ),
    ).toBeVisible();
    expect(
      within(headlineField).getByText('Checking whether Japanese (Japan) can be added again.'),
    ).toBeVisible();
    const translationEditor = within(headlineField).getByLabelText('Headline translation editor');
    await waitFor(() => expect(translationEditor).toHaveFocus());
    expect(within(headlineField).getByRole('status')).toContainElement(
      within(headlineField).getByText('Japanese (Japan) is not set up for this copy.'),
    );
    expect(within(headlineField).queryByRole('alert')).toBeNull();
    expect(
      within(headlineField).queryByRole('button', { name: 'Add Japanese (Japan)' }),
    ).toBeNull();
    expect(within(headlineField).queryByRole('button', { name: 'Try again' })).toBeNull();
    expect(screen.queryByRole('region', { name: 'Choose translation languages' })).toBeNull();
  });

  it('shows clean start as the first real authoring task', async () => {
    const user = userEvent.setup();
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=clean-start');

    render(<ContentCmsAuthoringPreview />);

    expect(
      screen.queryByRole('complementary', { name: 'Copy collections' }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Start with product copy' })).toBeVisible();
    expect(screen.getByText('Name the first copy')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Start writing' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Use welcome email starter' })).toBeVisible();
    const primaryActions = screen
      .getByRole('button', { name: 'Start writing' })
      .closest('.content-cms-admin-page__actions');
    if (!(primaryActions instanceof HTMLElement)) {
      throw new Error('Clean-start primary actions not found');
    }
    expect(
      within(primaryActions).getByRole('button', { name: 'Use welcome email starter' }),
    ).toBeVisible();
    const optionsTrigger = screen.getByRole('button', { name: 'Add details' });
    expect(optionsTrigger).toHaveAttribute('aria-expanded', 'false');
    expect(
      screen
        .getByRole('button', { name: 'Start writing' })
        .compareDocumentPosition(optionsTrigger) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
    expect(
      screen
        .getByRole('button', { name: 'Start writing' })
        .compareDocumentPosition(
          screen.getByRole('button', { name: 'Use welcome email starter' }),
        ) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
    expect(
      screen
        .getByRole('button', { name: 'Use welcome email starter' })
        .compareDocumentPosition(optionsTrigger) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
    expect(
      screen.queryByText('Fills this empty draft so you can see the authoring path.'),
    ).not.toBeInTheDocument();
    expect(screen.queryByText('Repository')).not.toBeInTheDocument();
    expect(screen.queryByText('Mapping')).not.toBeInTheDocument();
    expect(screen.queryByText('Variant')).not.toBeInTheDocument();
    expect(screen.queryByText('JSON')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Use welcome email starter' }));

    expect(screen.getByLabelText('Copy collection name')).toHaveValue('Growth email copy');
    expect(screen.getByLabelText('Content item name')).toHaveValue('Welcome email');
    expect(screen.getByLabelText('Where this item appears')).toHaveValue(
      'Signup confirmation email',
    );
    expect(screen.getByLabelText('Source copy')).toHaveValue(
      'Welcome to Acme. Start your first project in minutes.',
    );
    expect(screen.getByLabelText('Translator note')).toHaveValue(
      'Friendly welcome sentence. Keep Acme untranslated.',
    );
    expect(
      screen.queryByRole('button', { name: 'Use welcome email starter' }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add details' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
    expect(screen.getByRole('button', { name: 'Start writing' })).toBeEnabled();
  });

  it('keeps source-only copy on translation language setup', async () => {
    const user = userEvent.setup();
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=source-only');

    render(<ContentCmsAuthoringPreview />);

    expect(screen.queryByText('Choose translation languages to start translation')).toBeNull();
    expect(screen.queryByLabelText('Headline translation status')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('CTA label translation status')).not.toBeInTheDocument();
    const ctaHeading = screen.getByRole('heading', { name: 'CTA label' });
    const translationLanguagesHeading = screen.getByRole('heading', {
      name: 'Choose translation languages',
    });
    const targetLocaleSetup = targetLocalePicker();
    expect(translationLanguagesHeading).toBeVisible();
    expect(
      ctaHeading.compareDocumentPosition(translationLanguagesHeading) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
    expect(targetLocaleSetup.closest('.content-cms-admin-page__field-outline-card')).toBeNull();
    expect(
      within(targetLocaleSetup).getByText(
        'Source copy is saved. Choose the languages this content item should be translated into next.',
      ),
    ).toBeVisible();
    const ctaTranslatorNote = screen.getByLabelText('CTA label translator note');
    await user.click(ctaTranslatorNote);
    await user.tab();
    expect(
      within(targetLocaleSetup).getByRole('button', { name: 'Choose translation languages' }),
    ).toHaveFocus();
    expect(
      screen.queryByRole('heading', { name: 'Release approved copy' }),
    ).not.toBeInTheDocument();
  });

  it('waits for every source copy draft before translation language setup', async () => {
    const user = userEvent.setup();
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=source-only');

    render(<ContentCmsAuthoringPreview />);

    expect(targetLocalePicker()).toBeVisible();

    await user.type(screen.getByLabelText('CTA label source copy'), ' revised');

    expect(
      screen.queryByRole('region', { name: 'Choose translation languages' }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', {
        name: 'Save source changes',
      }),
    ).toBeVisible();
  });

  it('keeps clean fields without saved placement quiet until placement opens', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=blank-field-placement',
    );

    render(<ContentCmsAuthoringPreview />);

    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement)) {
      throw new Error('Headline field not found');
    }
    expect(
      within(headlineField).queryByText('Add placement details if translators need context.'),
    ).not.toBeInTheDocument();
    expect(within(headlineField).queryByText('Hero headline')).not.toBeInTheDocument();
    expect(
      within(headlineField).getByText('Where this copy appears', { selector: 'summary' }),
    ).toBeVisible();
    const headlinePlacementDetails = within(headlineField).getByLabelText(
      'Headline placement details',
    );
    expect(headlinePlacementDetails).not.toBeVisible();
    expect(headlinePlacementDetails).toHaveAttribute('tabindex', '-1');
    await user.click(
      within(headlineField).getByText('Where this copy appears', { selector: 'summary' }),
    );
    expect(within(headlineField).getByLabelText('Headline placement details')).toBeVisible();
    expect(within(headlineField).getByLabelText('Headline placement details')).not.toHaveAttribute(
      'tabindex',
    );
  });

  it('keeps fallback stale recovery beside the author draft', () => {
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=not-a-preview');

    render(<ContentCmsAuthoringPreview />);

    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement)) {
      throw new Error('Headline field not found');
    }

    expect(screen.getByLabelText('Content item details')).toBeVisible();
    expect(screen.getByLabelText('Content item name')).toHaveValue('Welcome email draft');
    expect(screen.getByLabelText('Where this item appears')).toHaveValue('Shown after signup');
    expect(screen.getByText('Saved copy details changed while you were editing.')).toBeVisible();
    expect(within(headlineField).getByLabelText('Headline source copy')).toHaveValue(
      'Hello updated',
    );
    expect(within(headlineField).getByLabelText('Headline translator note')).toHaveValue(
      'Hero headline updated',
    );
    expect(
      within(headlineField).getByText('Saved source copy changed while you were editing.'),
    ).toBeVisible();
    expect(within(headlineField).getByLabelText('Headline placement details')).toHaveValue(
      'Hero headline',
    );
    expect(
      within(headlineField).getByText('Saved placement details changed while you were editing.'),
    ).toBeVisible();
    expect(screen.getAllByText('Saved elsewhere')).toHaveLength(3);
    expect(screen.queryByRole('heading', { name: 'Admin tools' })).not.toBeInTheDocument();
  });

  it('keeps blank item placement quiet until the author opens details', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=blank-item-placement',
    );

    render(<ContentCmsAuthoringPreview />);

    expect(screen.queryByText('Add where this item appears.')).not.toBeInTheDocument();
    await openSavedItemDetails(user);
    expect(
      within(screen.getByLabelText('Content item details')).getByLabelText(
        'Where this item appears',
      ),
    ).toHaveValue('');
  });

  it('keeps empty content item recovery in writing language', async () => {
    const user = userEvent.setup();
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=empty-content-items');

    render(<ContentCmsAuthoringPreview />);

    const firstContentItemForm = screen
      .getByRole('heading', { name: 'Write the first content item' })
      .closest('form');
    if (!(firstContentItemForm instanceof HTMLElement)) {
      throw new Error('First content item form not found');
    }
    await user.click(within(firstContentItemForm).getByRole('button', { name: 'Cancel' }));

    expect(screen.getByText('Write the first content item in English.')).toBeVisible();
    expect(
      screen.queryByText(
        'Write the first content item in English, then send its source copy to translation.',
      ),
    ).not.toBeInTheDocument();
    expect(screen.getByText('No content items yet.')).toBeVisible();
    expect(
      screen.getByText('Write the first content item above to start writing source copy.'),
    ).toBeVisible();
    expect(
      screen.queryByText('Add the first content item above to start writing source copy.'),
    ).not.toBeInTheDocument();
  });

  it('does not use item placement as a missing source-copy preview', () => {
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=multi-item-no-source',
    );

    render(<ContentCmsAuthoringPreview />);

    const followUpRow = screen.getByRole('button', { name: 'Open content item Follow-up email' });
    expect(within(followUpRow).getByText('No source copy yet')).toBeVisible();
    expect(within(followUpRow).queryByText('Used after signup')).not.toBeInTheDocument();
  });

  it('shows display locale names in the seeded release blocker', () => {
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=release-blocker');

    render(<ContentCmsAuthoringPreview />);

    const releaseBlocker = screen.getByRole('region', { name: 'Release this copy' });
    expect(releaseBlocker).toBeVisible();
    expect(
      within(releaseBlocker).getByText('French (France) needs translation before release.'),
    ).toBeVisible();
    expect(within(releaseBlocker).getByRole('button', { name: 'Go to release' })).not.toHaveClass(
      'settings-button--ghost',
    );
    expect(within(releaseBlocker).getByRole('button', { name: 'Remove from release' })).toHaveClass(
      'settings-button--ghost',
    );
    expect(screen.queryByText('fr-FR needs translation before release.')).not.toBeInTheDocument();
    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    const welcomeBlockers = within(blockers).getByRole('region', {
      name: 'Welcome email release blockers',
    });
    expect(within(welcomeBlockers).getByText('Current content item')).toBeVisible();
    expect(within(welcomeBlockers).getByText('2 fields need work')).toBeVisible();
    expect(within(welcomeBlockers).getByText('Headline:')).toBeVisible();
    expect(within(welcomeBlockers).getByText('CTA label:')).toBeVisible();
    expect(
      within(
        within(welcomeBlockers).getByRole('button', {
          name: 'Write French (France) translation in Headline for Welcome email',
        }),
      ).getByText('Current field'),
    ).toBeVisible();
    expect(
      within(welcomeBlockers).getByRole('button', {
        name: 'Write French (France) translation in CTA label for Welcome email',
      }),
    ).toBeVisible();
    expect(
      within(
        within(welcomeBlockers).getByRole('button', {
          name: 'Write French (France) translation in CTA label for Welcome email',
        }),
      ).getByText('Write translation'),
    ).toBeVisible();
    expect(screen.getByText(/Last released English and French \(France\) ·/)).toBeVisible();
    expect(screen.queryByText(/Last released en, fr-FR ·/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Last released v7 ·/)).not.toBeInTheDocument();
  });

  it('routes seeded multi-field release blockers to the exact copy field', async () => {
    const user = userEvent.setup();
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=release-blocker');

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write French (France) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    expect(ctaField).toHaveClass('is-active');
    const ctaTranslation = await within(ctaField).findByLabelText('French (France) translation');
    await waitFor(() => expect(ctaTranslation).toHaveFocus());
  });

  it('routes seeded single-field release blockers to the exact copy field', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-single-blocker',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    expect(within(blockers).getByText('Headline:')).toBeVisible();
    expect(
      within(blockers).getByText('French (France) needs translation before release.'),
    ).toBeVisible();

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write French (France) translation in Headline for Welcome email',
      }),
    );

    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement)) {
      throw new Error('Headline field not found');
    }
    expect(headlineField).toHaveClass('is-active');
    const headlineTranslation = await within(headlineField).findByLabelText(
      'French (France) translation',
    );
    await waitFor(() => expect(headlineTranslation).toHaveFocus());
  });

  it('keeps seeded source-copy blockers on the source field', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-source-blocker',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    expect(within(blockers).getByText('CTA label:')).toBeVisible();
    expect(
      within(blockers).getByText('Save 1 required field of source copy before release.'),
    ).toBeVisible();
    expect(
      within(
        within(blockers).getByRole('button', {
          name: 'Fix source copy in CTA label for Welcome email',
        }),
      ).getByText('Fix source copy'),
    ).toBeVisible();

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Fix source copy in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    expect(ctaField).toHaveClass('is-active');
    const ctaSourceCopy = within(ctaField).getByLabelText('CTA label source copy');
    await waitFor(() => expect(ctaSourceCopy).toHaveFocus());
    await waitFor(() =>
      expect(
        within(ctaField).queryByLabelText('CTA label translation editor'),
      ).not.toBeInTheDocument(),
    );
  });

  it('keeps unavailable release translation blockers on retry', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-unavailable',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write French (France) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    expect(
      await within(ctaField).findByText('French (France) translation is unavailable.'),
    ).toBeVisible();
    const tryAgain = within(ctaField).getByRole('button', { name: 'Try again' });
    await waitFor(() => expect(tryAgain).toHaveFocus());
    const reconnect = within(ctaField).getByRole('button', { name: 'Reconnect French (France)' });
    expect(reconnect).toBeVisible();

    await user.click(reconnect);

    expect(
      await within(ctaField).findByText('French (France) translation still needs work.'),
    ).toBeVisible();
    expect(
      within(ctaField).getByRole('button', { name: 'Reconnect French (France) again' }),
    ).toBeVisible();
  });

  it('keeps repaired unavailable release translation blockers in author task language', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-repaired-unavailable',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write French (France) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    expect(
      await within(ctaField).findByText('French (France) translation still needs work.'),
    ).toBeVisible();
    expect(
      within(ctaField).getByText(
        'Try again to open it. Reconnect French (France) again only if it stays unavailable.',
      ),
    ).toBeVisible();
    expect(
      within(ctaField).queryByText('French (France) translation is unavailable.'),
    ).not.toBeInTheDocument();
    const tryAgain = within(ctaField).getByRole('button', { name: 'Try again' });
    await waitFor(() => expect(tryAgain).toHaveFocus());
    expect(
      within(ctaField).getByRole('button', { name: 'Reconnect French (France) again' }),
    ).toBeVisible();
  });

  it('keeps failed unavailable release translation reconnects beside the field', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-reconnect-error',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write French (France) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    expect(
      await within(ctaField).findByText('French (France) translation is unavailable.'),
    ).toBeVisible();
    await user.click(within(ctaField).getByRole('button', { name: 'Reconnect French (France)' }));

    expect(
      await within(ctaField).findByText('French (France) translation could not reconnect.'),
    ).toBeVisible();
    expect(
      within(ctaField).getByText(
        'Reconnect French (France) again. If it keeps failing, ask an admin to check this field.',
      ),
    ).toBeVisible();
    const reconnectAgain = within(ctaField).getByRole('button', {
      name: 'Reconnect French (France) again',
    });
    await waitFor(() => expect(reconnectAgain).toHaveFocus());
  });

  it('keeps dirty unavailable release translation blockers on saved source copy guardrails', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-dirty-source-draft',
    );

    render(<ContentCmsAuthoringPreview />);

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    const translationEditor = await within(ctaField).findByLabelText(
      'CTA label translation editor',
    );
    expect(
      within(translationEditor).getByText('Save source changes before translating'),
    ).toBeVisible();
    expect(
      within(translationEditor).getByText(
        'This translation still uses the last saved source. Save source changes before editing it.',
      ),
    ).toBeVisible();
    expect(
      within(translationEditor).getByRole('link', { name: 'Open saved CTA label translation' }),
    ).toBeVisible();
    expect(
      within(translationEditor).queryByText('French (France) translation is unavailable.'),
    ).not.toBeInTheDocument();
    expect(
      within(translationEditor).queryByRole('button', { name: 'Reconnect French (France)' }),
    ).not.toBeInTheDocument();
    const returnToRelease = within(ctaField).getByRole('region', { name: 'Return to release' });
    expect(within(returnToRelease).getByText('Fix before release')).toBeVisible();
    expect(
      within(returnToRelease).getByText('French (France) needs translation before release.'),
    ).toBeVisible();
    expect(within(returnToRelease).queryByText('Repair saved')).not.toBeInTheDocument();

    await user.click(within(ctaField).getByRole('button', { name: 'Save source changes' }));

    expect(
      await within(ctaField).findByText('French (France) translation is unavailable.'),
    ).toBeVisible();
    expect(
      within(ctaField).getByRole('button', { name: 'Reconnect French (France)' }),
    ).toBeVisible();
    expect(within(returnToRelease).getByText('Fix before release')).toBeVisible();
    expect(within(returnToRelease).queryByText('Repair saved')).not.toBeInTheDocument();
  });

  it('keeps mixed batch-saved dirty unavailable release drafts on the same handoff', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-dirty-source-draft',
    );

    render(<ContentCmsAuthoringPreview />);

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    expect(await within(ctaField).findByLabelText('CTA label translation editor')).toBeVisible();
    await user.type(within(ctaField).getByLabelText('CTA label translator note'), ' revised');
    await user.type(within(ctaField).getByLabelText('CTA label placement details'), ' below hero');
    const returnToRelease = within(ctaField).getByRole('region', { name: 'Return to release' });
    expect(within(returnToRelease).getByText('Fix before release')).toBeVisible();
    expect(within(returnToRelease).queryByText('Repair saved')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save copy changes' })).toBeVisible();

    await user.click(screen.getByRole('button', { name: 'Save copy changes' }));

    expect(screen.queryByRole('button', { name: 'Save copy changes' })).not.toBeInTheDocument();
    expect(within(ctaField).getByLabelText('CTA label translator note')).toHaveValue(
      'Short action label. Keep it concise. revised',
    );
    expect(within(ctaField).getByLabelText('CTA label placement details')).toHaveValue(
      'Primary action below hero',
    );
    expect(
      await within(ctaField).findByText('French (France) translation is unavailable.'),
    ).toBeVisible();
    expect(
      within(ctaField).getByRole('button', { name: 'Reconnect French (France)' }),
    ).toBeVisible();
    expect(within(returnToRelease).getByText('Fix before release')).toBeVisible();
    expect(within(returnToRelease).queryByText('Repair saved')).not.toBeInTheDocument();
  });

  it('keeps failed mixed dirty release drafts beside the same blocker', async () => {
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-dirty-source-conflict',
    );

    render(<ContentCmsAuthoringPreview />);

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    const translationEditor = await within(ctaField).findByLabelText(
      'CTA label translation editor',
    );
    expect(
      within(translationEditor).getByText('Save source changes before translating'),
    ).toBeVisible();
    expect(within(ctaField).getByText('Saved elsewhere')).toBeVisible();
    expect(
      within(ctaField).getByText('Saved placement details changed while you were editing.'),
    ).toBeVisible();
    expect(within(ctaField).getByText('Primary action from another editor')).toBeVisible();
    expect(within(ctaField).getByLabelText('CTA label placement details')).toHaveValue(
      'Primary action below hero',
    );
    expect(within(ctaField).getByLabelText('CTA label source copy')).toHaveValue(
      'Start now revised',
    );
    expect(screen.getByRole('button', { name: 'Save copy changes' })).toBeVisible();
    const returnToRelease = within(ctaField).getByRole('region', { name: 'Return to release' });
    expect(within(returnToRelease).getByText('Fix before release')).toBeVisible();
    expect(
      within(returnToRelease).getByText('French (France) needs translation before release.'),
    ).toBeVisible();
    expect(within(returnToRelease).queryByText('Repair saved')).not.toBeInTheDocument();
    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    expect(within(releasePanel).getByText('Save before releasing')).toBeVisible();
    expect(
      within(
        within(releasePanel).getByRole('group', {
          name: 'Content items needing work',
        }),
      ).getByRole('button', {
        name: 'Write French (France) translation in CTA label for Welcome email',
      }),
    ).toBeVisible();
  });

  it('refreshes conflicting unavailable release translation reconnects beside the field', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-reconnect-conflict',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write French (France) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    expect(
      await within(ctaField).findByText('French (France) translation is unavailable.'),
    ).toBeVisible();
    await user.click(within(ctaField).getByRole('button', { name: 'Reconnect French (France)' }));

    expect(
      await within(ctaField).findByText('French (France) translation could not reconnect.'),
    ).toBeVisible();
    expect(
      within(ctaField).getByText(
        'Saved copy changed while reconnecting French (France). Review the refreshed source copy, then reconnect French (France) again.',
      ),
    ).toBeVisible();
    expect(within(ctaField).getByLabelText('CTA label source copy')).toHaveValue(
      'Start now refreshed',
    );
    expect(within(ctaField).getByLabelText('CTA label translator note')).toHaveValue(
      'Primary action label refreshed',
    );
    const reconnectAgain = within(ctaField).getByRole('button', {
      name: 'Reconnect French (France) again',
    });
    await waitFor(() => expect(reconnectAgain).toHaveFocus());
  });

  it('keeps removed release translation languages explicit', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-language-removed',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    expect(
      await within(ctaField).findByText('Japanese (Japan) is not set up for this copy.'),
    ).toBeVisible();
    expect(
      within(ctaField).getByText('Add Japanese (Japan) again, or continue with French (France).'),
    ).toBeVisible();
    const advancedSetup = within(ctaField).getByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(advancedSetup).toHaveFocus());
    expect(
      within(ctaField).getByRole('button', { name: 'Continue with French (France)' }),
    ).toBeVisible();
    expect(within(ctaField).queryByRole('button', { name: 'Switch language' })).toBeNull();
    expect(
      within(ctaField).queryByLabelText('Japanese (Japan) translation'),
    ).not.toBeInTheDocument();
  });

  it('opens a scoped release language chooser when multiple configured translations remain after removal', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-language-removed-multiple-alternates',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    expect(
      await within(ctaField).findByText('Japanese (Japan) is not set up for this copy.'),
    ).toBeVisible();
    expect(
      within(ctaField).getByText(
        'Add Japanese (Japan) again, or choose another translation language.',
      ),
    ).toBeVisible();
    const addJapanese = within(ctaField).getByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(addJapanese).toHaveFocus());
    expect(
      within(ctaField).queryByRole('button', {
        name: 'Continue with French (France)',
      }),
    ).toBeNull();
    expect(within(ctaField).queryByRole('button', { name: 'Switch language' })).toBeNull();
    expect(within(ctaField).queryByRole('button', { name: 'Hide translation editor' })).toBeNull();
    expect(within(ctaField).getByLabelText('Return to release')).toBeVisible();

    await user.click(within(ctaField).getByRole('button', { name: 'Choose another language' }));
    const frenchLocale = within(ctaField).getByRole('button', {
      name: 'French (France) Needs translation',
    });
    await waitFor(() => expect(frenchLocale).toHaveFocus());

    await user.click(within(ctaField).getByRole('button', { name: 'German (Germany) Approved' }));

    const germanTranslation = await within(ctaField).findByLabelText(
      'German (Germany) translation',
    );
    await waitFor(() => expect(germanTranslation).toHaveFocus());
    expect(
      within(ctaField).queryByText('Japanese (Japan) is not set up for this copy.'),
    ).not.toBeInTheDocument();
    expect(within(ctaField).getByLabelText('Return to release')).toBeVisible();

    await user.click(within(ctaField).getByRole('button', { name: 'Return to release' }));

    expect(document.getElementById('content-cms-release-panel')).toHaveFocus();
    await waitFor(() =>
      expect(
        within(releasePanel).queryByText('Japanese (Japan) needs translation before release.'),
      ).not.toBeInTheDocument(),
    );
    const refreshedBlockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    const refreshedCtaBlocker = within(refreshedBlockers).getByRole('button', {
      name: 'Write French (France) translation in CTA label for Welcome email',
    });
    expect(
      within(refreshedCtaBlocker).getByText('French (France) needs translation before release.'),
    ).toBeVisible();
    expect(refreshedCtaBlocker).toBeVisible();
  });

  it('rechecks a re-added release language instead of keeping removed release truth', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-language-removed-multiple-alternates',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    const addJapanese = await within(ctaField).findByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(addJapanese).toHaveFocus());

    await user.click(addJapanese);

    const japaneseTranslation = await within(ctaField).findByLabelText(
      'Japanese (Japan) translation',
    );
    await waitFor(() => expect(japaneseTranslation).toHaveFocus());

    await user.click(within(ctaField).getByRole('button', { name: 'Return to release' }));

    expect(document.getElementById('content-cms-release-panel')).toHaveFocus();
    await waitFor(() =>
      expect(
        within(releasePanel).queryByText('French (France) needs translation before release.'),
      ).not.toBeInTheDocument(),
    );
    const refreshedBlockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    const refreshedCtaBlocker = within(refreshedBlockers).getByRole('button', {
      name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
    });
    expect(
      within(refreshedCtaBlocker).getByText('Japanese (Japan) needs translation before release.'),
    ).toBeVisible();
    expect(refreshedCtaBlocker).toBeVisible();
  });

  it('keeps failed release language add-back local before returning through current release truth', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-language-add-failed',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    const addJapanese = await within(ctaField).findByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(addJapanese).toHaveFocus());

    await user.click(addJapanese);

    expect(await within(ctaField).findByText('Translation languages did not save')).toBeVisible();
    expect(
      within(ctaField).getByText(
        'Could not add translation languages. Try again. If it keeps failing, ask an admin to check language setup.',
      ),
    ).toBeVisible();
    expect(addJapanese).toBeEnabled();
    expect(within(ctaField).queryByLabelText('Japanese (Japan) translation')).toBeNull();
    expect(within(ctaField).getByRole('button', { name: 'Return to release' })).toBeVisible();

    await user.click(within(ctaField).getByRole('button', { name: 'Return to release' }));

    expect(document.getElementById('content-cms-release-panel')).toHaveFocus();
    await waitFor(() =>
      expect(
        within(releasePanel).queryByText('Japanese (Japan) needs translation before release.'),
      ).not.toBeInTheDocument(),
    );
    const refreshedBlockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    const refreshedCtaBlocker = within(refreshedBlockers).getByRole('button', {
      name: 'Write French (France) translation in CTA label for Welcome email',
    });
    expect(
      within(refreshedCtaBlocker).getByText('French (France) needs translation before release.'),
    ).toBeVisible();
    expect(refreshedCtaBlocker).toBeVisible();
  });

  it('retries failed release language add-back into the real requested translation task', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-language-add-failed',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    const addJapanese = await within(ctaField).findByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(addJapanese).toHaveFocus());

    await user.click(addJapanese);
    expect(await within(ctaField).findByText('Translation languages did not save')).toBeVisible();

    await user.click(addJapanese);

    const japaneseTranslation = await within(ctaField).findByLabelText(
      'Japanese (Japan) translation',
    );
    await waitFor(() => expect(japaneseTranslation).toHaveFocus());
    expect(within(ctaField).queryByText('Translation languages did not save')).toBeNull();

    await user.click(within(ctaField).getByRole('button', { name: 'Return to release' }));

    expect(document.getElementById('content-cms-release-panel')).toHaveFocus();
    const refreshedBlockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    const refreshedCtaBlocker = within(refreshedBlockers).getByRole('button', {
      name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
    });
    expect(
      within(refreshedCtaBlocker).getByText('Japanese (Japan) needs translation before release.'),
    ).toBeVisible();
    expect(
      within(refreshedBlockers).queryByText('French (France) needs translation before release.'),
    ).not.toBeInTheDocument();
  });

  it('recovers concurrent release language add-back into the real requested translation task', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-language-add-conflict',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    const addJapanese = await within(ctaField).findByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(addJapanese).toHaveFocus());

    await user.click(addJapanese);

    expect(await within(ctaField).findByText('Translation languages did not save')).toBeVisible();
    expect(addJapanese).toBeDisabled();
    expect(
      within(ctaField).getByText(
        'Translation languages changed before Japanese (Japan) was added. Current copy is refreshing; try Add Japanese (Japan) again if it is still not set up.',
      ),
    ).toBeVisible();

    const japaneseTranslation = await within(ctaField).findByLabelText(
      'Japanese (Japan) translation',
    );
    await waitFor(() => expect(japaneseTranslation).toHaveFocus());
    expect(within(ctaField).queryByText('Translation languages did not save')).toBeNull();

    await user.click(within(ctaField).getByRole('button', { name: 'Return to release' }));

    expect(document.getElementById('content-cms-release-panel')).toHaveFocus();
    const refreshedBlockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    const refreshedCtaBlocker = within(refreshedBlockers).getByRole('button', {
      name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
    });
    expect(
      within(refreshedCtaBlocker).getByText('Japanese (Japan) needs translation before release.'),
    ).toBeVisible();
    expect(
      within(refreshedBlockers).queryByText('French (France) needs translation before release.'),
    ).not.toBeInTheDocument();
  });

  it('re-enables concurrent release language add-back when refreshed copy still lacks the requested language', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-language-add-conflict-still-removed',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    const addJapanese = await within(ctaField).findByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(addJapanese).toHaveFocus());

    await user.click(addJapanese);

    expect(await within(ctaField).findByText('Translation languages did not save')).toBeVisible();
    expect(addJapanese).toBeDisabled();
    expect(
      within(ctaField).getByText(
        'Translation languages changed before Japanese (Japan) was added. Current copy is refreshing; try Add Japanese (Japan) again if it is still not set up.',
      ),
    ).toBeVisible();

    expect(
      await within(ctaField).findByText(
        'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
      ),
    ).toBeVisible();
    expect(addJapanese).toBeEnabled();
    expect(within(ctaField).queryByLabelText('Japanese (Japan) translation')).toBeNull();
    expect(
      screen.queryByText(
        'Translation languages changed before Japanese (Japan) was added. Current copy is refreshing; try Add Japanese (Japan) again if it is still not set up.',
      ),
    ).not.toBeInTheDocument();

    await user.click(within(ctaField).getByRole('button', { name: 'Return to release' }));

    expect(document.getElementById('content-cms-release-panel')).toHaveFocus();
    const refreshedBlockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    expect(
      within(refreshedBlockers).queryByText('Japanese (Japan) needs translation before release.'),
    ).not.toBeInTheDocument();
    const refreshedCtaBlocker = within(refreshedBlockers).getByRole('button', {
      name: 'Write French (France) translation in CTA label for Welcome email',
    });
    expect(
      within(refreshedCtaBlocker).getByText('French (France) needs translation before release.'),
    ).toBeVisible();
  });

  it('keeps concurrent release language add-back retryable when refreshed copy could not load', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-language-add-conflict-refresh-failed',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    const addJapanese = await within(ctaField).findByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(addJapanese).toHaveFocus());

    await user.click(addJapanese);

    expect(await within(ctaField).findByText('Translation languages did not save')).toBeVisible();
    expect(addJapanese).toBeDisabled();
    expect(
      within(ctaField).getByText(
        'Translation languages changed before Japanese (Japan) was added. Current copy is refreshing; try Add Japanese (Japan) again if it is still not set up.',
      ),
    ).toBeVisible();

    expect(
      await within(ctaField).findByText(
        'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
      ),
    ).toBeVisible();
    expect(within(ctaField).getByText('Translation status could not refresh.')).toBeVisible();
    expect(within(ctaField).getByRole('button', { name: 'Try again' })).toBeEnabled();
    expect(addJapanese).toBeEnabled();
    expect(within(ctaField).queryByLabelText('Japanese (Japan) translation')).toBeNull();
    expect(
      screen.queryByText(
        'Translation languages changed before Japanese (Japan) was added. Current copy is refreshing; try Add Japanese (Japan) again if it is still not set up.',
      ),
    ).not.toBeInTheDocument();

    await user.click(within(ctaField).getByRole('button', { name: 'Return to release' }));

    expect(document.getElementById('content-cms-release-panel')).toHaveFocus();
    const refreshedBlockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    expect(
      within(refreshedBlockers).queryByText('Japanese (Japan) needs translation before release.'),
    ).not.toBeInTheDocument();
    const refreshedCtaBlocker = within(refreshedBlockers).getByRole('button', {
      name: 'Write French (France) translation in CTA label for Welcome email',
    });
    expect(
      within(refreshedCtaBlocker).getByText('French (France) needs translation before release.'),
    ).toBeVisible();
  });

  it('retries failed concurrent release language refresh into the real requested translation task', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-language-add-conflict-refresh-failed',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    const addJapanese = await within(ctaField).findByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(addJapanese).toHaveFocus());

    await user.click(addJapanese);

    expect(
      await within(ctaField).findByText(
        'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
      ),
    ).toBeVisible();
    const tryAgain = within(ctaField).getByRole('button', { name: 'Try again' });
    await user.click(tryAgain);

    expect(addJapanese).toBeDisabled();
    const japaneseTranslation = await within(ctaField).findByLabelText(
      'Japanese (Japan) translation',
    );
    await waitFor(() => expect(japaneseTranslation).toHaveFocus());
    expect(within(ctaField).queryByText('Translation languages did not save')).toBeNull();
    expect(within(ctaField).queryByText('Translation status could not refresh.')).toBeNull();
    expect(
      screen.queryByText(
        'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
      ),
    ).not.toBeInTheDocument();

    await user.click(within(ctaField).getByRole('button', { name: 'Return to release' }));

    expect(document.getElementById('content-cms-release-panel')).toHaveFocus();
    const refreshedBlockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    const refreshedCtaBlocker = within(refreshedBlockers).getByRole('button', {
      name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
    });
    expect(
      within(refreshedCtaBlocker).getByText('Japanese (Japan) needs translation before release.'),
    ).toBeVisible();
    expect(
      within(refreshedBlockers).queryByText('French (France) needs translation before release.'),
    ).not.toBeInTheDocument();
  });

  it('re-enables concurrent release language add-back when readiness retry still lacks the requested language', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-language-add-conflict-refresh-failed-retry-still-removed',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    const addJapanese = await within(ctaField).findByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(addJapanese).toHaveFocus());

    await user.click(addJapanese);

    expect(
      await within(ctaField).findByText(
        'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
      ),
    ).toBeVisible();
    await user.click(within(ctaField).getByRole('button', { name: 'Try again' }));

    expect(addJapanese).toBeDisabled();
    expect(
      await within(ctaField).findByText(
        'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
      ),
    ).toBeVisible();
    expect(addJapanese).toBeEnabled();
    expect(within(ctaField).queryByText('Translation status could not refresh.')).toBeNull();
    expect(within(ctaField).queryByLabelText('Japanese (Japan) translation')).toBeNull();
    expect(
      screen.queryByText(
        'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
      ),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText(
        'Translation languages changed before Japanese (Japan) was added. Current copy is refreshing; try Add Japanese (Japan) again if it is still not set up.',
      ),
    ).not.toBeInTheDocument();

    await user.click(within(ctaField).getByRole('button', { name: 'Return to release' }));

    expect(document.getElementById('content-cms-release-panel')).toHaveFocus();
    const refreshedBlockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    expect(
      within(refreshedBlockers).queryByText('Japanese (Japan) needs translation before release.'),
    ).not.toBeInTheDocument();
    const refreshedCtaBlocker = within(refreshedBlockers).getByRole('button', {
      name: 'Write French (France) translation in CTA label for Welcome email',
    });
    expect(
      within(refreshedCtaBlocker).getByText('French (France) needs translation before release.'),
    ).toBeVisible();
  });

  it('retries concurrent release language add-back after readiness retry still lacks the requested language', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-language-add-conflict-refresh-failed-retry-still-removed',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    const addJapanese = await within(ctaField).findByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(addJapanese).toHaveFocus());

    await user.click(addJapanese);
    await within(ctaField).findByText(
      'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
    );
    await user.click(within(ctaField).getByRole('button', { name: 'Try again' }));
    await within(ctaField).findByText(
      'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
    );

    await user.click(addJapanese);

    const japaneseTranslation = await within(ctaField).findByLabelText(
      'Japanese (Japan) translation',
    );
    await waitFor(() => expect(japaneseTranslation).toHaveFocus());
    expect(within(ctaField).queryByText('Translation languages did not save')).toBeNull();
    expect(
      screen.queryByText(
        'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
      ),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText(
        'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
      ),
    ).not.toBeInTheDocument();

    await user.click(within(ctaField).getByRole('button', { name: 'Return to release' }));

    expect(document.getElementById('content-cms-release-panel')).toHaveFocus();
    const refreshedBlockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    const refreshedCtaBlocker = within(refreshedBlockers).getByRole('button', {
      name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
    });
    expect(
      within(refreshedCtaBlocker).getByText('Japanese (Japan) needs translation before release.'),
    ).toBeVisible();
    expect(
      within(refreshedBlockers).queryByText('French (France) needs translation before release.'),
    ).not.toBeInTheDocument();
  });

  it('replaces settled concurrent release language add-back recovery when Add conflicts again', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    const addJapanese = await within(ctaField).findByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(addJapanese).toHaveFocus());

    await user.click(addJapanese);
    await within(ctaField).findByText(
      'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
    );
    await user.click(within(ctaField).getByRole('button', { name: 'Try again' }));
    await within(ctaField).findByText(
      'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
    );

    await user.click(addJapanese);

    expect(addJapanese).toBeDisabled();
    expect(
      await within(ctaField).findByText(
        'Translation languages changed before Japanese (Japan) was added. Current copy is refreshing; try Add Japanese (Japan) again if it is still not set up.',
      ),
    ).toBeVisible();
    expect(
      screen.queryByText(
        'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
      ),
    ).not.toBeInTheDocument();

    expect(
      await within(ctaField).findByText(
        'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
      ),
    ).toBeVisible();
    expect(addJapanese).toBeEnabled();
    expect(
      screen.queryByText(
        'Translation languages changed before Japanese (Japan) was added. Current copy is refreshing; try Add Japanese (Japan) again if it is still not set up.',
      ),
    ).not.toBeInTheDocument();
    expect(within(ctaField).queryByText('Translation status could not refresh.')).toBeNull();

    await user.click(within(ctaField).getByRole('button', { name: 'Return to release' }));

    expect(document.getElementById('content-cms-release-panel')).toHaveFocus();
    const refreshedBlockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    expect(
      within(refreshedBlockers).queryByText('Japanese (Japan) needs translation before release.'),
    ).not.toBeInTheDocument();
    const refreshedCtaBlocker = within(refreshedBlockers).getByRole('button', {
      name: 'Write French (France) translation in CTA label for Welcome email',
    });
    expect(
      within(refreshedCtaBlocker).getByText('French (France) needs translation before release.'),
    ).toBeVisible();
  });

  it('falls back to failed refresh recovery when repeated release language add-back conflicts again', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    const addJapanese = await within(ctaField).findByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(addJapanese).toHaveFocus());

    await user.click(addJapanese);
    await within(ctaField).findByText(
      'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
    );
    await user.click(within(ctaField).getByRole('button', { name: 'Try again' }));
    await within(ctaField).findByText(
      'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
    );

    await user.click(addJapanese);

    expect(addJapanese).toBeDisabled();
    expect(
      await within(ctaField).findByText(
        'Translation languages changed before Japanese (Japan) was added. Current copy is refreshing; try Add Japanese (Japan) again if it is still not set up.',
      ),
    ).toBeVisible();
    expect(
      screen.queryByText(
        'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
      ),
    ).not.toBeInTheDocument();

    expect(
      await within(ctaField).findByText(
        'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
      ),
    ).toBeVisible();
    expect(within(ctaField).getByText('Translation status could not refresh.')).toBeVisible();
    expect(within(ctaField).getByRole('button', { name: 'Try again' })).toBeEnabled();
    expect(addJapanese).toBeEnabled();
    expect(within(ctaField).getByRole('button', { name: 'Return to release' })).toBeEnabled();
    expect(within(ctaField).queryByLabelText('Japanese (Japan) translation')).toBeNull();
    expect(
      screen.queryByText(
        'Translation languages changed before Japanese (Japan) was added. Current copy is refreshing; try Add Japanese (Japan) again if it is still not set up.',
      ),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText(
        'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
      ),
    ).not.toBeInTheDocument();

    await user.click(within(ctaField).getByRole('button', { name: 'Try again' }));
    expect(addJapanese).toBeDisabled();
    expect(
      await within(ctaField).findByText(
        'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
      ),
    ).toBeVisible();
    expect(addJapanese).toBeEnabled();
    expect(within(ctaField).queryByLabelText('Japanese (Japan) translation')).toBeNull();
    expect(within(ctaField).queryByText('Translation status could not refresh.')).toBeNull();
    expect(
      screen.queryByText(
        'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
      ),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText(
        'Translation languages changed before Japanese (Japan) was added. Current copy is refreshing; try Add Japanese (Japan) again if it is still not set up.',
      ),
    ).not.toBeInTheDocument();

    await user.click(within(ctaField).getByRole('button', { name: 'Return to release' }));

    expect(document.getElementById('content-cms-release-panel')).toHaveFocus();
    const refreshedBlockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    expect(
      within(refreshedBlockers).queryByText('Japanese (Japan) needs translation before release.'),
    ).not.toBeInTheDocument();
    const refreshedCtaBlocker = within(refreshedBlockers).getByRole('button', {
      name: 'Write French (France) translation in CTA label for Welcome email',
    });
    expect(
      within(refreshedCtaBlocker).getByText('French (France) needs translation before release.'),
    ).toBeVisible();
  });

  it('retries failed repeated release language add-back refresh into the requested translation', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    const addJapanese = await within(ctaField).findByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(addJapanese).toHaveFocus());

    await user.click(addJapanese);
    await within(ctaField).findByText(
      'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
    );
    await user.click(within(ctaField).getByRole('button', { name: 'Try again' }));
    await within(ctaField).findByText(
      'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
    );

    await user.click(addJapanese);
    await within(ctaField).findByText(
      'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
    );
    const tryAgain = within(ctaField).getByRole('button', { name: 'Try again' });
    await user.click(tryAgain);

    expect(addJapanese).toBeDisabled();
    const japaneseTranslation = await within(ctaField).findByLabelText(
      'Japanese (Japan) translation',
    );
    await waitFor(() => expect(japaneseTranslation).toHaveFocus());
    expect(within(ctaField).queryByText('Translation languages did not save')).toBeNull();
    expect(within(ctaField).queryByText('Translation status could not refresh.')).toBeNull();
    expect(
      screen.queryByText(
        'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
      ),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText(
        'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
      ),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText(
        'Translation languages changed before Japanese (Japan) was added. Current copy is refreshing; try Add Japanese (Japan) again if it is still not set up.',
      ),
    ).not.toBeInTheDocument();

    await user.click(within(ctaField).getByRole('button', { name: 'Return to release' }));

    expect(document.getElementById('content-cms-release-panel')).toHaveFocus();
    const refreshedBlockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    const refreshedCtaBlocker = within(refreshedBlockers).getByRole('button', {
      name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
    });
    expect(
      within(refreshedCtaBlocker).getByText('Japanese (Japan) needs translation before release.'),
    ).toBeVisible();
    expect(
      within(refreshedBlockers).queryByText('French (France) needs translation before release.'),
    ).not.toBeInTheDocument();
  });

  it('retries failed repeated release language add-back refresh into honest local retry when Japanese stays removed', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    const addJapanese = await within(ctaField).findByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(addJapanese).toHaveFocus());

    await user.click(addJapanese);
    await within(ctaField).findByText(
      'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
    );
    await user.click(within(ctaField).getByRole('button', { name: 'Try again' }));
    await within(ctaField).findByText(
      'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
    );

    await user.click(addJapanese);
    await within(ctaField).findByText(
      'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
    );
    await user.click(within(ctaField).getByRole('button', { name: 'Try again' }));

    expect(addJapanese).toBeDisabled();
    expect(
      await within(ctaField).findByText(
        'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
      ),
    ).toBeVisible();
    expect(addJapanese).toBeEnabled();
    expect(within(ctaField).queryByLabelText('Japanese (Japan) translation')).toBeNull();
    expect(within(ctaField).queryByText('Translation status could not refresh.')).toBeNull();
    expect(
      screen.queryByText(
        'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
      ),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText(
        'Translation languages changed before Japanese (Japan) was added. Current copy is refreshing; try Add Japanese (Japan) again if it is still not set up.',
      ),
    ).not.toBeInTheDocument();

    await user.click(addJapanese);
    expect(addJapanese).toBeDisabled();
    expect(
      within(ctaField).getByText(
        'Translation languages changed before Japanese (Japan) was added. Current copy is refreshing; try Add Japanese (Japan) again if it is still not set up.',
      ),
    ).toBeVisible();
    expect(
      screen.queryByText(
        'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
      ),
    ).not.toBeInTheDocument();
    await within(ctaField).findByText(
      'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
    );
    expect(addJapanese).toBeEnabled();
    expect(
      screen.queryByText(
        'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
      ),
    ).not.toBeInTheDocument();

    await user.click(within(ctaField).getByRole('button', { name: 'Return to release' }));

    expect(document.getElementById('content-cms-release-panel')).toHaveFocus();
    const refreshedBlockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    expect(
      within(refreshedBlockers).queryByText('Japanese (Japan) needs translation before release.'),
    ).not.toBeInTheDocument();
    const refreshedCtaBlocker = within(refreshedBlockers).getByRole('button', {
      name: 'Write French (France) translation in CTA label for Welcome email',
    });
    expect(
      within(refreshedCtaBlocker).getByText('French (France) needs translation before release.'),
    ).toBeVisible();
  });

  it('retries third failed repeated release language add-back refresh into the requested translation', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-restored',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    const addJapanese = await within(ctaField).findByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(addJapanese).toHaveFocus());

    await user.click(addJapanese);
    await within(ctaField).findByText(
      'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
    );
    await user.click(within(ctaField).getByRole('button', { name: 'Try again' }));
    await within(ctaField).findByText(
      'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
    );

    await user.click(addJapanese);
    await within(ctaField).findByText(
      'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
    );
    await user.click(within(ctaField).getByRole('button', { name: 'Try again' }));
    expect(
      await within(ctaField).findByText(
        'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
      ),
    ).toBeVisible();
    expect(addJapanese).toBeEnabled();
    expect(within(ctaField).queryByLabelText('Japanese (Japan) translation')).toBeNull();
    expect(within(ctaField).queryByText('Translation status could not refresh.')).toBeNull();

    await user.click(addJapanese);
    expect(addJapanese).toBeDisabled();
    expect(
      within(ctaField).getByText(
        'Translation languages changed before Japanese (Japan) was added. Current copy is refreshing; try Add Japanese (Japan) again if it is still not set up.',
      ),
    ).toBeVisible();
    expect(
      screen.queryByText(
        'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
      ),
    ).not.toBeInTheDocument();
    await within(ctaField).findByText(
      'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
    );
    expect(addJapanese).toBeEnabled();
    expect(
      screen.queryByText(
        'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
      ),
    ).not.toBeInTheDocument();

    await user.click(within(ctaField).getByRole('button', { name: 'Try again' }));
    expect(addJapanese).toBeDisabled();

    const japaneseTranslation = await within(ctaField).findByLabelText(
      'Japanese (Japan) translation',
    );
    await waitFor(() => expect(japaneseTranslation).toHaveFocus());
    expect(within(ctaField).queryByText('Translation languages did not save')).toBeNull();
    expect(within(ctaField).queryByText('Translation status could not refresh.')).toBeNull();
    expect(
      screen.queryByText(
        'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
      ),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText(
        'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
      ),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText(
        'Translation languages changed before Japanese (Japan) was added. Current copy is refreshing; try Add Japanese (Japan) again if it is still not set up.',
      ),
    ).not.toBeInTheDocument();

    await user.click(within(ctaField).getByRole('button', { name: 'Return to release' }));

    expect(document.getElementById('content-cms-release-panel')).toHaveFocus();
    const refreshedBlockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    const refreshedCtaBlocker = within(refreshedBlockers).getByRole('button', {
      name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
    });
    expect(
      within(refreshedCtaBlocker).getByText('Japanese (Japan) needs translation before release.'),
    ).toBeVisible();
    expect(
      within(refreshedBlockers).queryByText('French (France) needs translation before release.'),
    ).not.toBeInTheDocument();
  });

  it('retries direct release language add-back after failed repeated readiness retry still lacks the requested language', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-target-language-add-conflict-refresh-failed-retry-still-removed-conflict-again-refresh-failed-retry-still-removed-add-restored',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    const addJapanese = await within(ctaField).findByRole('button', {
      name: 'Add Japanese (Japan)',
    });
    await waitFor(() => expect(addJapanese).toHaveFocus());

    await user.click(addJapanese);
    await within(ctaField).findByText(
      'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
    );
    await user.click(within(ctaField).getByRole('button', { name: 'Try again' }));
    await within(ctaField).findByText(
      'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
    );

    await user.click(addJapanese);
    await within(ctaField).findByText(
      'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
    );
    await user.click(within(ctaField).getByRole('button', { name: 'Try again' }));
    expect(
      await within(ctaField).findByText(
        'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
      ),
    ).toBeVisible();
    expect(addJapanese).toBeEnabled();
    expect(within(ctaField).queryByLabelText('Japanese (Japan) translation')).toBeNull();
    expect(within(ctaField).queryByText('Translation status could not refresh.')).toBeNull();

    await user.click(addJapanese);

    const japaneseTranslation = await within(ctaField).findByLabelText(
      'Japanese (Japan) translation',
    );
    await waitFor(() => expect(japaneseTranslation).toHaveFocus());
    expect(within(ctaField).queryByText('Translation languages did not save')).toBeNull();
    expect(within(ctaField).queryByText('Translation status could not refresh.')).toBeNull();
    expect(
      screen.queryByText(
        'Current copy could not refresh after Japanese (Japan) changed. Try Add Japanese (Japan) again, refresh translation status, or return to release to check current translation languages.',
      ),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText(
        'Current copy refreshed and Japanese (Japan) is still not set up. Try Add Japanese (Japan) again, or return to release to work from current translation languages.',
      ),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText(
        'Translation languages changed before Japanese (Japan) was added. Current copy is refreshing; try Add Japanese (Japan) again if it is still not set up.',
      ),
    ).not.toBeInTheDocument();

    await user.click(within(ctaField).getByRole('button', { name: 'Return to release' }));

    expect(document.getElementById('content-cms-release-panel')).toHaveFocus();
    const refreshedBlockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    const refreshedCtaBlocker = within(refreshedBlockers).getByRole('button', {
      name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
    });
    expect(
      within(refreshedCtaBlocker).getByText('Japanese (Japan) needs translation before release.'),
    ).toBeVisible();
    expect(
      within(refreshedBlockers).queryByText('French (France) needs translation before release.'),
    ).not.toBeInTheDocument();
  });

  it('routes seeded multi-locale release blockers to the exact language', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-locale-blocker',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });
    expect(within(blockers).getByText('CTA label:')).toBeVisible();
    expect(
      within(blockers).getByText('Japanese (Japan) needs translation before release.'),
    ).toBeVisible();
    expect(
      within(blockers).queryByText('French (France) needs translation before release.'),
    ).not.toBeInTheDocument();

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    expect(ctaField).toHaveClass('is-active');
    const ctaJapaneseTranslation = await within(ctaField).findByLabelText(
      'Japanese (Japan) translation',
    );
    await waitFor(() => expect(ctaJapaneseTranslation).toHaveFocus());
    await user.click(within(ctaField).getByRole('button', { name: 'Switch language' }));
    await user.click(within(ctaField).getByRole('button', { name: 'French (France) Approved' }));
    expect(await within(ctaField).findByLabelText('French (France) translation')).toBeVisible();

    await user.click(
      within(
        screen.getByRole('group', {
          name: 'Content items needing work',
        }),
      ).getByRole('button', {
        name: 'Write Japanese (Japan) translation in CTA label for Welcome email',
      }),
    );

    const reopenedJapaneseTranslation = await within(ctaField).findByLabelText(
      'Japanese (Japan) translation',
    );
    await waitFor(() => expect(reopenedJapaneseTranslation).toHaveFocus());
  });

  it('shows the saved repair refresh failure without a backend save', () => {
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-repair-refresh-failed',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    expect(within(releasePanel).getByText('Saved repair needs another check')).toBeVisible();
    expect(
      within(releasePanel).getByText(
        'Your repair saved, but this release could not refresh. Try Release approved copy again. If it keeps failing, ask an admin to check this release.',
      ),
    ).toBeVisible();
    expect(
      within(releasePanel).getByRole('button', { name: 'Release approved copy' }),
    ).toBeEnabled();

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    const returnToRelease = within(ctaField).getByRole('region', { name: 'Return to release' });
    expect(within(returnToRelease).getByText('Repair saved')).toBeVisible();
    expect(
      within(returnToRelease).queryByText('Japanese (Japan) needs translation before release.'),
    ).not.toBeInTheDocument();
    expect(
      within(ctaField).getByRole('heading', { name: 'Write Japanese (Japan) translation' }),
    ).toBeVisible();
  });

  it('keeps blank optional copy out of missing-required translation chrome', () => {
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=optional-wait');

    render(<ContentCmsAuthoringPreview />);

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    expect(within(ctaField).getByText('Optional copy can wait')).toBeVisible();
    expect(within(ctaField).queryByText('Needs source copy')).not.toBeInTheDocument();
    expect(within(ctaField).queryByText('Source not saved yet')).not.toBeInTheDocument();
    expect(
      within(ctaField).queryByLabelText('CTA label translation status'),
    ).not.toBeInTheDocument();
    expect(within(ctaField).getByText('Add when writing this copy')).toBeVisible();
    expect(within(ctaField).getByLabelText('CTA label source copy')).not.toBeRequired();
    expect(within(ctaField).getByLabelText('CTA label translator note')).not.toBeRequired();
    expect(
      within(ctaField).queryByRole('button', { name: 'Save source copy' }),
    ).not.toBeInTheDocument();
  });

  it('keeps required unsaved source copy out of translation chrome', () => {
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=required-source-empty',
    );

    render(<ContentCmsAuthoringPreview />);

    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement)) {
      throw new Error('Headline field not found');
    }
    expect(within(headlineField).getByText('Needs source copy')).toBeVisible();
    expect(
      within(headlineField).queryByText('Save source copy to start translations'),
    ).not.toBeInTheDocument();
    expect(within(headlineField).queryByText('Source not saved yet')).not.toBeInTheDocument();
    expect(
      within(headlineField).queryByLabelText('Headline translation status'),
    ).not.toBeInTheDocument();
  });

  it('keeps source-only translation setup behind required saved copy', () => {
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=source-only-required-source-empty',
    );

    render(<ContentCmsAuthoringPreview />);

    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement)) {
      throw new Error('Headline field not found');
    }
    expect(within(headlineField).getByText('Needs source copy')).toBeVisible();
    expect(
      screen.queryByRole('region', { name: 'Choose translation languages' }),
    ).not.toBeInTheDocument();
  });

  it('keeps draft release hidden while translation readiness is loading', () => {
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=release-loading');

    render(<ContentCmsAuthoringPreview />);

    expect(screen.queryByRole('region', { name: 'Release this copy' })).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Include in next release' }),
    ).not.toBeInTheDocument();
  });

  it('shows the direct ready-for-release handoff before inclusion', () => {
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=ready-for-release');

    render(<ContentCmsAuthoringPreview />);

    const releaseHandoff = screen.getByLabelText('Ready for release');
    expect(
      within(releaseHandoff).getByRole('button', { name: 'Include in next release' }),
    ).toBeVisible();
    expect(screen.getAllByRole('button', { name: 'Include in next release' })).toHaveLength(1);
    expect(screen.queryByRole('region', { name: 'Release this copy' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Go to release' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Review release' })).not.toBeInTheDocument();
    expect(
      screen.queryByRole('heading', { name: 'Release approved copy' }),
    ).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Release approved copy' })).not.toBeInTheDocument();
  });

  it('blocks item save until started optional copy has translator context', () => {
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=optional-source-draft',
    );

    render(<ContentCmsAuthoringPreview />);

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    expect(within(ctaField).getByText('Unsaved changes')).toBeVisible();
    expect(within(ctaField).getByText('Required for translation')).toBeVisible();
    expect(within(ctaField).getByLabelText('CTA label source copy')).toHaveValue('Start free');
    expect(within(ctaField).getByLabelText('CTA label translator note')).toHaveValue('');
    expect(within(ctaField).getByRole('button', { name: 'Save source copy' })).toBeDisabled();
    expect(screen.getByText('Finish source copy')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Save copy changes' })).toBeDisabled();
  });

  it('blocks item save until dirty content item details keep their required name', async () => {
    const user = userEvent.setup();
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=blank-item-name');

    render(<ContentCmsAuthoringPreview />);

    expect(screen.getByText('Name this content item')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Save copy changes' })).toBeDisabled();

    await openSavedItemDetails(user);
    const contentItemDetails = screen.getByLabelText('Content item details');
    const contentItemName = within(contentItemDetails).getByLabelText('Content item name');
    expect(contentItemName).toHaveValue('');
    expect(contentItemName).toHaveAttribute('aria-invalid', 'true');
    expect(
      within(contentItemDetails).getByText('Name this content item before saving.'),
    ).toBeVisible();
    expect(
      within(contentItemDetails).getByRole('button', { name: 'Save copy details' }),
    ).toBeDisabled();
  });

  it('keeps missing translation on a real writing step before save actions', async () => {
    const user = userEvent.setup();
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=missing-translation');

    render(<ContentCmsAuthoringPreview />);

    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement)) {
      throw new Error('Headline field not found');
    }

    await user.click(
      within(headlineField).getByRole('button', { name: 'Write French (France) translation' }),
    );
    expect(within(headlineField).getByLabelText('French (France) translation')).toHaveValue('');
    const translationEditor = within(headlineField).getByLabelText('Headline translation editor');
    expect(within(translationEditor).getByText('Needs translation')).toBeVisible();
    expect(
      translationEditor.querySelector(
        '.content-cms-admin-page__inline-translation-next-step-header .content-cms-admin-page__inline-translation-current-state',
      ),
    ).toBeNull();
    expect(within(headlineField).getAllByText('Write French (France) translation')).toHaveLength(1);
    expect(within(headlineField).getByText('Start this translation')).toBeVisible();
    expect(
      within(headlineField).getByText(
        'Write the translation to unlock draft, review, and release actions.',
      ),
    ).toBeVisible();
    expect(within(headlineField).queryByText('Save this translation')).not.toBeInTheDocument();
    expect(
      within(headlineField).queryByRole('group', { name: 'Save this translation' }),
    ).not.toBeInTheDocument();
    expect(within(headlineField).getByRole('button', { name: 'Other actions' })).toBeVisible();
  });

  it('keeps broken saved content in author repair language', () => {
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=saved-content-repair',
    );

    render(<ContentCmsAuthoringPreview />);

    expect(
      screen.getByText('This content item needs repair before source copy can be edited.'),
    ).toBeVisible();
    expect(screen.getByRole('button', { name: 'Repair content item' })).toBeVisible();
    expect(screen.queryByRole('heading', { name: 'Write the copy' })).not.toBeInTheDocument();
    expect(screen.queryByText(/technical repair/i)).not.toBeInTheDocument();
  });

  it('keeps new zero-field content items on repair recovery', async () => {
    const user = userEvent.setup();
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=new-item-no-fields');

    render(<ContentCmsAuthoringPreview />);

    await openNewContentItemForm(user);
    const newContentItemForm = screen
      .getByRole('heading', { name: 'Write a new content item' })
      .closest('form');
    if (!(newContentItemForm instanceof HTMLElement)) {
      throw new Error('New content item form not found');
    }
    expect(
      within(newContentItemForm).getByText('This content item has no fields to write yet.'),
    ).toBeVisible();
    expect(
      within(newContentItemForm).getByRole('button', { name: 'Repair content item' }),
    ).toBeVisible();
    expect(
      within(newContentItemForm).queryByText('Add a field before saving a content item.'),
    ).not.toBeInTheDocument();
    expect(
      within(newContentItemForm).getByRole('button', { name: 'Save content item' }),
    ).toBeDisabled();
  });

  it('keeps optional new content item placement behind details until opened', async () => {
    const user = userEvent.setup();

    render(<ContentCmsAuthoringPreview />);

    await openNewContentItemForm(user);
    const newContentItemForm = screen
      .getByRole('heading', { name: 'Write a new content item' })
      .closest('form');
    if (!(newContentItemForm instanceof HTMLElement)) {
      throw new Error('New content item form not found');
    }
    const detailsTrigger = within(newContentItemForm).getByRole('button', {
      name: 'Content item details',
    });
    expect(detailsTrigger).toHaveAttribute('aria-expanded', 'false');
    expect(within(newContentItemForm).queryByLabelText('Where this item appears')).toBeNull();

    await user.click(detailsTrigger);

    const contentItemDetails = within(newContentItemForm).getByLabelText('Content item details');
    expect(detailsTrigger).toHaveAttribute('aria-controls', contentItemDetails.id);
    expect(within(contentItemDetails).getByLabelText('Where this item appears')).toBeVisible();
  });

  it('shows the author release start state before a readiness check', () => {
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=release-start');

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const writeCopyHeading = screen.getByRole('heading', { name: 'Write the copy' });
    expect(
      releasePanel.compareDocumentPosition(writeCopyHeading) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBe(0);
    expect(within(releasePanel).getByText('Release not started')).toBeVisible();
    expect(
      within(releasePanel).getByText(
        'Release approved copy checks every included content item before it goes live.',
      ),
    ).toBeVisible();
    expect(within(releasePanel).getByText('1 content item included')).toBeVisible();
    expect(
      within(releasePanel).queryByRole('group', {
        name: 'Included content items',
      }),
    ).not.toBeInTheDocument();
    expect(
      within(releasePanel).queryByRole('button', { name: 'Show release details' }),
    ).not.toBeInTheDocument();
    expect(
      within(releasePanel).queryByRole('button', { name: 'Review release' }),
    ).not.toBeInTheDocument();
    expect(within(releasePanel).getByRole('button', { name: 'Release approved copy' })).toHaveClass(
      'settings-button--primary',
    );
    expect(
      within(releasePanel).getByRole('button', { name: 'Release approved copy' }),
    ).toBeEnabled();
  });

  it('shows why a previous release needs another release', async () => {
    const user = userEvent.setup();
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=release-changed');

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    expect(within(releasePanel).getByText('Ready to release')).toBeVisible();
    expect(
      within(releasePanel).getByText(
        '1 content item included · 5 changes since last release · 1 needs action',
      ),
    ).toBeVisible();
    expect(within(releasePanel).queryByText('Changed since last release')).not.toBeInTheDocument();
    await openAuthorReleaseDetails(user, releasePanel);
    expect(within(releasePanel).getByText('Changed since last release')).toBeVisible();
    const changedWelcomeEmail = within(releasePanel).getByRole('region', {
      name: 'Welcome email',
    });
    expect(within(changedWelcomeEmail).getByText('4 changes · 1 needs action')).toBeVisible();
    const changedHeadline = within(changedWelcomeEmail).getByRole('group', {
      name: 'Headline changes',
    });
    expect(within(changedHeadline).getByText('3 changes · 1 needs action')).toBeVisible();
    const changedHeadlineRows = within(changedHeadline).getAllByRole('button');
    expect(changedHeadlineRows).toHaveLength(3);
    expect(changedHeadlineRows[0]).toHaveAccessibleName(
      'Review German (Germany) translation in Headline for Welcome email',
    );
    expect(changedHeadlineRows[0]).toHaveClass('is-action-needed');
    expect(within(changedHeadline).getByText('German (Germany) needs review.')).toBeVisible();
    expect(within(changedHeadline).getByText('French (France) translation changed.')).toBeVisible();
    expect(
      within(changedWelcomeEmail).getByRole('group', { name: 'CTA label changes' }),
    ).toBeVisible();
    expect(
      within(releasePanel).queryByText('Spanish (Spain) was added to this release.'),
    ).not.toBeInTheDocument();
    const showMoreChanges = within(releasePanel).getByRole('button', {
      name: 'Show 1 more change',
    });
    expect(showMoreChanges).toHaveAttribute('aria-expanded', 'false');
    await user.click(showMoreChanges);
    expect(
      within(releasePanel).getByText('Spanish (Spain) was added to this release.'),
    ).toBeVisible();
    expect(within(releasePanel).getByRole('region', { name: 'Release languages' })).toBeVisible();
    const showFewerChanges = within(releasePanel).getByRole('button', {
      name: 'Show fewer changes',
    });
    expect(showFewerChanges).toHaveAttribute('aria-expanded', 'true');
    await user.click(showFewerChanges);
    expect(
      within(releasePanel).queryByText('Spanish (Spain) was added to this release.'),
    ).not.toBeInTheDocument();
    await user.click(
      within(releasePanel).getByRole('button', {
        name: 'Review French (France) translation in Headline for Welcome email',
      }),
    );
    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement)) {
      throw new Error('Headline field not found');
    }
    expect(headlineField).toHaveClass('is-active');
    const returnToRelease = within(headlineField).getByLabelText('Return to release');
    expect(within(returnToRelease).getByText('Opened from release review')).toBeVisible();
    expect(within(returnToRelease).getByText('French (France) translation changed.')).toBeVisible();
    expect(within(returnToRelease).getByText('Return after reviewing this field.')).toBeVisible();
    const frenchTranslation = await within(headlineField).findByLabelText(
      'French (France) translation',
    );
    await waitFor(() => expect(frenchTranslation).toHaveFocus());
    const translationComparison = within(headlineField)
      .getByText('Compare with the last released translation.')
      .closest('.content-cms-admin-page__source-copy-refresh');
    if (!(translationComparison instanceof HTMLElement)) {
      throw new Error('Translation comparison not found');
    }
    expect(
      within(translationComparison).getByText('Last released French (France) translation'),
    ).toBeVisible();
    expect(within(translationComparison).getByText('Bienvenue chez Acme.')).toBeVisible();
    expect(
      within(translationComparison).getByText('Current saved French (France) translation'),
    ).toBeVisible();
    expect(
      within(translationComparison).getByText(
        'Bienvenue chez Acme. Demarrez votre premier projet en quelques minutes.',
      ),
    ).toBeVisible();
    await user.click(within(returnToRelease).getByRole('button', { name: 'Return to release' }));
    expect(document.getElementById('content-cms-release-panel')).toHaveFocus();
    await user.click(
      within(releasePanel).getByRole('button', {
        name: 'Review CTA label in Welcome email',
      }),
    );
    const ctaLabelField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaLabelField instanceof HTMLElement)) {
      throw new Error('CTA label field not found');
    }
    expect(ctaLabelField).toHaveClass('is-release-review-target');
    expect(within(ctaLabelField).getByText('Review from release')).toBeVisible();
    const ctaLabelReturnToRelease = within(ctaLabelField).getByLabelText('Return to release');
    expect(within(ctaLabelReturnToRelease).getByText('Added to this content item.')).toBeVisible();
    await user.click(
      within(ctaLabelReturnToRelease).getByRole('button', { name: 'Return to release' }),
    );
    expect(document.getElementById('content-cms-release-panel')).toHaveFocus();
    expect(within(releasePanel).getByRole('button', { name: 'Release approved copy' })).toHaveClass(
      'settings-button--primary',
    );
  });

  it('keeps current stale release changes before other included items', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-changed-multi-item',
    );

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    expect(
      within(releasePanel).getByText('2 content items included · 6 changes since last release'),
    ).toBeVisible();
    await openAuthorReleaseDetails(user, releasePanel);
    const includedItems = within(releasePanel).getByRole('group', {
      name: 'Included content items',
    });
    expect(within(includedItems).getByText('2 content items will release.')).toBeVisible();
    const currentChanges = within(releasePanel).getByRole('region', {
      name: 'Welcome email',
    });
    const followUpChanges = within(releasePanel).getByRole('region', {
      name: 'Follow-up email',
    });
    expect(within(currentChanges).getByText('Current content item')).toBeVisible();
    const currentHeadline = within(currentChanges).getByRole('group', {
      name: 'Headline changes',
    });
    expect(within(currentHeadline).getByText('Current field')).toBeVisible();
    expect(within(currentHeadline).getByText('Source copy changed.')).toBeVisible();
    expect(within(followUpChanges).queryByText('Current field')).not.toBeInTheDocument();
    expect(
      currentChanges.compareDocumentPosition(followUpChanges) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    await user.click(
      within(currentHeadline).getByRole('button', {
        name: 'Review source copy in Headline for Welcome email',
      }),
    );
    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement)) {
      throw new Error('Headline field not found');
    }
    const headlineSourceCopy = within(headlineField).getByLabelText('Headline source copy');
    const headlineSourceCopyField = headlineSourceCopy.closest(
      '.content-cms-admin-page__copy-field',
    );
    if (!(headlineSourceCopyField instanceof HTMLElement)) {
      throw new Error('Headline source copy field not found');
    }
    expect(headlineSourceCopyField).toHaveClass('is-release-review-target');
    expect(within(headlineSourceCopyField).getByText('Review from release')).toBeVisible();
    await waitFor(() => expect(headlineSourceCopy).toHaveFocus());
    const sourceCopyComparison = within(headlineField)
      .getByText('Compare with the last released copy.')
      .closest('.content-cms-admin-page__source-copy-refresh');
    if (!(sourceCopyComparison instanceof HTMLElement)) {
      throw new Error('Source copy comparison not found');
    }
    expect(within(sourceCopyComparison).getByText('Last released source copy')).toBeVisible();
    expect(within(sourceCopyComparison).getByText('Welcome to Acme.')).toBeVisible();
    expect(within(sourceCopyComparison).getByText('Current source copy')).toBeVisible();
    const sourceCopyReturnToRelease = within(headlineField).getByLabelText('Return to release');
    expect(within(sourceCopyReturnToRelease).getByText('Source copy changed.')).toBeVisible();
    expect(
      within(sourceCopyReturnToRelease).getByText('Return after reviewing this field.'),
    ).toBeVisible();
    await user.click(screen.getByRole('heading', { name: 'CTA label' }));
    const siblingCtaLabelField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(siblingCtaLabelField instanceof HTMLElement)) {
      throw new Error('CTA label field not found');
    }
    expect(siblingCtaLabelField).toHaveClass('is-active');
    expect(within(headlineField).queryByLabelText('Return to release')).not.toBeInTheDocument();
    const welcomeEditor = screen
      .getByRole('heading', { name: 'Welcome email', level: 2 })
      .closest('.content-cms-admin-page__editor');
    if (!(welcomeEditor instanceof HTMLElement)) {
      throw new Error('Welcome editor not found');
    }
    const fallbackReturnToRelease = within(welcomeEditor).getByLabelText('Return to release');
    expect(within(fallbackReturnToRelease).getByText('Source copy changed.')).toBeVisible();
    expect(
      within(fallbackReturnToRelease).getByText('Return after reviewing this release change.'),
    ).toBeVisible();
    await user.click(
      within(fallbackReturnToRelease).getByRole('button', { name: 'Return to release' }),
    );
    expect(document.getElementById('content-cms-release-panel')).toHaveFocus();
    expect(within(welcomeEditor).queryByLabelText('Return to release')).not.toBeInTheDocument();
    expect(
      within(releasePanel).queryByText('Spanish (Spain) was added to this release.'),
    ).not.toBeInTheDocument();

    await user.click(within(releasePanel).getByRole('button', { name: 'Show 2 more changes' }));

    expect(
      within(releasePanel).getByText('Spanish (Spain) was added to this release.'),
    ).toBeVisible();

    await user.click(within(releasePanel).getByRole('button', { name: 'Review Follow-up email' }));

    const followUpEditor = screen
      .getByRole('heading', { name: 'Follow-up email', level: 2 })
      .closest('.content-cms-admin-page__editor');
    if (!(followUpEditor instanceof HTMLElement)) {
      throw new Error('Follow-up editor not found');
    }
    expect(followUpEditor).toHaveClass('is-release-review-target');
    expect(within(followUpEditor).getByText('Review from release')).toBeVisible();
    const returnToRelease = within(followUpEditor).getByLabelText('Return to release');
    expect(within(returnToRelease).getByText('Included in this release.')).toBeVisible();
    expect(
      within(returnToRelease).getByText('Return after reviewing this content item.'),
    ).toBeVisible();
  });

  it('returns from item-level release review without a field target', async () => {
    const user = userEvent.setup();
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-changed-item-review',
    );

    render(<ContentCmsAuthoringPreview />);

    const followUpEditor = screen
      .getByRole('heading', { name: 'Follow-up email', level: 2 })
      .closest('.content-cms-admin-page__editor');
    if (!(followUpEditor instanceof HTMLElement)) {
      throw new Error('Follow-up editor not found');
    }
    expect(followUpEditor).toHaveClass('is-release-review-target');
    expect(within(followUpEditor).getByText('Review from release')).toBeVisible();
    const returnToRelease = within(followUpEditor).getByLabelText('Return to release');
    expect(within(returnToRelease).getByText('Opened from release review')).toBeVisible();
    expect(within(returnToRelease).getByText('Included in this release.')).toBeVisible();
    expect(
      within(returnToRelease).getByText('Return after reviewing this content item.'),
    ).toBeVisible();

    await user.click(within(returnToRelease).getByRole('button', { name: 'Return to release' }));

    expect(document.getElementById('content-cms-release-panel')).toHaveFocus();
  });

  it('keeps previous release repairs direct while loading passive overflow', async () => {
    const user = userEvent.setup();
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=release-overflow');

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    expect(
      within(releasePanel).getByText(
        '1 content item included · 6+ changes since last release · 2 need action',
      ),
    ).toBeVisible();
    await openAuthorReleaseDetails(user, releasePanel);
    expect(within(releasePanel).getByText('Japanese (Japan) translation is needed.')).toBeVisible();
    expect(
      within(releasePanel).getByRole('button', {
        name: 'Write Japanese (Japan) translation in Headline for Welcome email',
      }),
    ).toBeVisible();
    await user.click(
      within(releasePanel).getByRole('button', {
        name: 'Write Japanese (Japan) translation in Headline for Welcome email',
      }),
    );
    const headlineField = screen
      .getByRole('heading', { name: 'Headline' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(headlineField instanceof HTMLElement)) {
      throw new Error('Headline field not found');
    }
    const returnToRelease = within(headlineField).getByLabelText('Return to release');
    expect(
      within(returnToRelease).getByText('Japanese (Japan) translation is needed.'),
    ).toBeVisible();
    expect(within(returnToRelease).getByText('Fix before release')).toBeVisible();
    expect(within(returnToRelease).getByText('Fix this before release')).toBeVisible();
    expect(within(returnToRelease).getByText('Return when this field is ready.')).toBeVisible();
    await user.click(within(returnToRelease).getByRole('button', { name: 'Return to release' }));
    expect(
      within(releasePanel).getByText(
        'More release changes are included. Load every change to review them here.',
      ),
    ).toBeVisible();
    expect(
      within(releasePanel).queryByText('Korean (South Korea) was removed from this release.'),
    ).not.toBeInTheDocument();
    await user.click(within(releasePanel).getByRole('button', { name: 'Load every change' }));
    expect(
      within(releasePanel).getByText('Korean (South Korea) was removed from this release.'),
    ).toBeVisible();
    const removedLegacySubtitle = within(releasePanel).getByRole('group', {
      name: 'Legacy subtitle changes',
    });
    expect(
      within(removedLegacySubtitle).getByText('Removed from this content item.'),
    ).toBeVisible();
    const removedLegacyFooter = within(releasePanel).getByRole('region', {
      name: 'Legacy footer',
    });
    expect(within(removedLegacyFooter).getByText('Removed from this release.')).toBeVisible();
    expect(
      within(releasePanel).queryByRole('button', {
        name: 'Open Legacy subtitle in Welcome email',
      }),
    ).not.toBeInTheDocument();
    expect(
      within(releasePanel).queryByRole('button', { name: 'Open Legacy footer' }),
    ).not.toBeInTheDocument();
    expect(within(releasePanel).getByRole('region', { name: 'Release languages' })).toBeVisible();
    expect(
      within(releasePanel).queryByRole('button', { name: 'Load every change' }),
    ).not.toBeInTheDocument();
    expect(
      within(releasePanel).getByRole('button', { name: 'Show fewer changes' }),
    ).toHaveAttribute('aria-expanded', 'true');
  });

  it('shows the author release check before confirmation', () => {
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=release-checking');

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    expect(within(releasePanel).getByText('Checking release')).toBeVisible();
    expect(
      within(releasePanel).getByText('Checking every included content item before confirmation.'),
    ).toBeVisible();
    expect(
      within(releasePanel).getByRole('button', { name: 'Checking release...' }),
    ).toBeDisabled();
    expect(within(releasePanel).queryByRole('button', { name: 'Review release' })).toBeNull();
  });

  it('shows the exact included item in the author release confirmation', () => {
    window.history.pushState(
      {},
      '',
      '/settings/system/content-cms?cmsPreview=release-confirmation',
    );

    render(<ContentCmsAuthoringPreview />);

    const confirmationDialog = screen.getByRole('alertdialog', { name: 'Release approved copy?' });
    expect(
      within(confirmationDialog).getByText(
        'Release Welcome email from Growth email copy using every translation language?',
      ),
    ).toBeVisible();
    expect(within(confirmationDialog).getByRole('button', { name: 'Release copy' })).toBeVisible();
  });

  it('shows the author release request after confirmation', () => {
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=release-publishing');

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    expect(within(releasePanel).getByText('Releasing copy')).toBeVisible();
    expect(
      within(releasePanel).getByText('Creating the release for every included content item.'),
    ).toBeVisible();
    expect(within(releasePanel).getByRole('button', { name: 'Releasing...' })).toBeDisabled();
  });

  it('shows the released content item after success', () => {
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=release-success');

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    expect(within(releasePanel).getByText('Released copy')).toBeVisible();
    expect(within(releasePanel).getByText('Released Welcome email in version 7.')).toBeVisible();
    expect(within(releasePanel).queryByText('Release not started')).not.toBeInTheDocument();
    expect(within(releasePanel).getByRole('button', { name: 'Check release again' })).toBeEnabled();
  });

  it('shows the seeded release retry state without reopening admin tools', () => {
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=release-retry');

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    expect(within(releasePanel).getByText('Ready to release')).toBeVisible();
    expect(within(releasePanel).getByText('Release did not finish')).toBeVisible();
    expect(
      within(releasePanel).getByText(
        'The release request did not finish. Try Release approved copy again. If it keeps failing, ask an admin to check this release.',
      ),
    ).toBeVisible();
    expect(
      within(releasePanel).queryByRole('button', { name: 'Review release' }),
    ).not.toBeInTheDocument();
    expect(within(releasePanel).getByRole('button', { name: 'Release approved copy' })).toHaveClass(
      'settings-button--primary',
    );
  });

  it('keeps release-blocker source copy edits wired to the author release gate', async () => {
    const user = userEvent.setup();
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=release-blocker');

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    expect(
      within(releasePanel).queryByRole('button', { name: 'Review release' }),
    ).not.toBeInTheDocument();
    expect(
      within(releasePanel).getByRole('button', { name: 'Release approved copy' }),
    ).toBeEnabled();

    await user.type(screen.getByRole('textbox', { name: 'Headline source copy' }), ' updated');

    expect(
      within(releasePanel).getByText('Save visible changes before releasing approved copy.'),
    ).toBeVisible();
    expect(
      within(releasePanel).queryByRole('button', { name: 'Review release' }),
    ).not.toBeInTheDocument();
  });

  it('keeps release-blocker dirty translation visible through source copy drafts', async () => {
    const user = userEvent.setup();
    window.history.pushState({}, '', '/settings/system/content-cms?cmsPreview=release-blocker');

    render(<ContentCmsAuthoringPreview />);

    const releasePanel = screen
      .getByRole('heading', { name: 'Release approved copy' })
      .closest('section');
    if (!(releasePanel instanceof HTMLElement)) {
      throw new Error('Release panel not found');
    }
    const blockers = within(releasePanel).getByRole('group', {
      name: 'Content items needing work',
    });

    await user.click(
      within(blockers).getByRole('button', {
        name: 'Write French (France) translation in CTA label for Welcome email',
      }),
    );

    const ctaField = screen
      .getByRole('heading', { name: 'CTA label' })
      .closest('.content-cms-admin-page__field-outline-card');
    if (!(ctaField instanceof HTMLElement)) {
      throw new Error('CTA field not found');
    }
    await user.type(
      await within(ctaField).findByLabelText('French (France) translation'),
      ' edited',
    );
    await user.type(within(ctaField).getByLabelText('CTA label source copy'), ' edited');

    expect(
      within(releasePanel).getByText('Save translation edits before releasing approved copy.'),
    ).toBeVisible();
    expect(
      within(
        within(releasePanel).getByRole('group', {
          name: 'Content items needing work',
        }),
      ).getByRole('button', {
        name: 'Write French (France) translation in CTA label for Welcome email',
      }),
    ).toBeVisible();
    expect(within(ctaField).getByText('Save source changes before translating')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Save copy changes' })).toBeVisible();
  });
});
