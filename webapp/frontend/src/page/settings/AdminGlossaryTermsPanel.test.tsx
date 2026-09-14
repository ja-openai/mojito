import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type * as GlossariesApi from '../../api/glossaries';
import { AdminGlossaryTermsPanel } from './AdminGlossaryTermsPanel';

const mocks = vi.hoisted(() => ({
  fetchGlossaryTerms: vi.fn<typeof GlossariesApi.fetchGlossaryTerms>(),
  fetchGlossaryTerm: vi.fn<typeof GlossariesApi.fetchGlossaryTerm>(),
  updateGlossaryTerm: vi.fn<typeof GlossariesApi.updateGlossaryTerm>(),
  role: 'ROLE_PM',
}));

vi.mock('../../api/glossaries', async (importOriginal) => ({
  ...(await importOriginal<typeof GlossariesApi>()),
  fetchGlossaryTerms: mocks.fetchGlossaryTerms,
  fetchGlossaryTerm: mocks.fetchGlossaryTerm,
  updateGlossaryTerm: mocks.updateGlossaryTerm,
}));

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({ role: mocks.role, canTranslateAllLocales: true }),
}));

const glossary: GlossariesApi.ApiGlossaryDetail = {
  id: 1,
  name: 'Product terms',
  enabled: true,
  priority: 0,
  scopeMode: 'GLOBAL',
  backingRepository: { id: 2, name: 'glossary-product' },
  assetPath: 'glossary.json',
  localeTags: ['fr-FR'],
  repositories: [],
  excludedRepositories: [],
};

let savedTerm: GlossariesApi.ApiGlossaryTerm;

function renderPanel() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AdminGlossaryTermsPanel
          glossary={glossary}
          repositoryOptions={[]}
          initialOpenTermId={42}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AdminGlossaryTermsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    mocks.role = 'ROLE_PM';
    savedTerm = {
      tmTextUnitId: 42,
      termKey: 'workspace',
      source: 'Workspace',
      definition: 'A shared place to work.',
      status: 'APPROVED',
      caseSensitive: false,
      doNotTranslate: false,
      translations: [{ localeTag: 'fr-FR', target: 'Espace de travail' }],
      evidence: [
        { evidenceType: 'NOTE', caption: 'Added to keep product naming consistent.' },
        { evidenceType: 'SCREENSHOT', imageKey: 'workspace.png', caption: 'Workspace header' },
        { evidenceType: 'STRING_USAGE', tmTextUnitId: 99, caption: 'Create workspace action' },
        { evidenceType: 'CODE_REF', caption: 'src/workspace.tsx' },
      ],
    };
    mocks.fetchGlossaryTerms.mockImplementation(() =>
      Promise.resolve({
        terms: [savedTerm],
        totalCount: 1,
        localeTags: ['fr-FR'],
      }),
    );
    mocks.fetchGlossaryTerm.mockImplementation(() => Promise.resolve(savedTerm));
    mocks.updateGlossaryTerm.mockImplementation((_glossaryId, _termId, request) => {
      savedTerm = { ...savedTerm, evidence: request.evidence ?? [] };
      return Promise.resolve(savedTerm);
    });
  });

  it.each([
    {
      label: 'named',
      creator: { id: 7, username: 'creator@example.com', commonName: 'Taylor Lane' },
      expected: 'Taylor Lane',
    },
    {
      label: 'username-only',
      creator: { id: 7, username: 'creator@example.com' },
      expected: 'creator@example.com',
    },
    { label: 'unknown', creator: null, expected: 'Unknown' },
  ])('shows the $label source creator to a read-only translator', async ({ creator, expected }) => {
    mocks.role = 'ROLE_TRANSLATOR';
    savedTerm.sourceCreatedBy = creator;
    renderPanel();
    const author = await screen.findByLabelText('Source creator');
    expect(within(author).getByText('Created by')).toHaveAttribute(
      'title',
      'Creator recorded for the current source version.',
    );
    expect(within(author).getByText(expected)).toBeInTheDocument();
    expect(within(author).queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('saves and reopens decision notes with links without replacing the term or other evidence', async () => {
    savedTerm.sourceCreatedBy = {
      id: 7,
      username: 'creator@example.com',
      commonName: 'Taylor Lane',
    };
    const panel = renderPanel();
    const existingNote = await screen.findByRole('textbox', { name: 'Decision note 1' });
    const updatedNote = 'Changed after product review: https://docs.example.com/naming';
    fireEvent.change(existingNote, { target: { value: updatedNote } });
    fireEvent.click(screen.getByRole('button', { name: 'Add decision note' }));
    const secondNote = 'Approved in https://example.slack.com/archives/C123/p456';
    fireEvent.change(screen.getByRole('textbox', { name: 'Decision note 2' }), {
      target: { value: secondNote },
    });

    expect(screen.getByRole('link', { name: 'https://docs.example.com/naming' })).toHaveAttribute(
      'href',
      'https://docs.example.com/naming',
    );
    expect(screen.getByRole('textbox', { name: 'Decision note 1' })).toHaveAttribute(
      'maxlength',
      '1024',
    );
    expect(
      within(screen.getByRole('region', { name: 'References' })).getAllByRole('combobox'),
    ).toHaveLength(3);
    fireEvent.click(screen.getByRole('button', { name: 'Save term' }));
    const confirmation = screen.getByRole('alertdialog', { name: 'Save glossary term' });
    expect(mocks.updateGlossaryTerm).not.toHaveBeenCalled();
    fireEvent.click(within(confirmation).getByRole('button', { name: 'Save term' }));
    await waitFor(() => expect(mocks.updateGlossaryTerm).toHaveBeenCalledTimes(1));

    const request = mocks.updateGlossaryTerm.mock.calls[0][2];
    expect(request).toMatchObject({
      source: 'Workspace',
      sourceComment: 'A shared place to work.',
      replaceTerm: false,
      evidence: [
        { evidenceType: 'NOTE', caption: updatedNote },
        { evidenceType: 'SCREENSHOT', imageKey: 'workspace.png', caption: 'Workspace header' },
        { evidenceType: 'STRING_USAGE', tmTextUnitId: 99, caption: 'Create workspace action' },
        { evidenceType: 'CODE_REF', caption: 'src/workspace.tsx' },
        { evidenceType: 'NOTE', caption: secondNote },
      ],
    });
    expect(request).not.toHaveProperty('translations');
    expect(request).not.toHaveProperty('sourceCreatedBy');
    await screen.findByText('Saved glossary term Workspace.');
    panel.unmount();
    renderPanel();
    expect(await screen.findByRole('textbox', { name: 'Decision note 1' })).toHaveValue(
      updatedNote,
    );
    expect(screen.getByRole('textbox', { name: 'Decision note 2' })).toHaveValue(secondNote);
    expect(
      within(screen.getByLabelText('Source creator')).getByText('Taylor Lane'),
    ).toBeInTheDocument();
  });

  it('removes a decision note while retaining usage and screenshot references', async () => {
    renderPanel();
    fireEvent.click(await screen.findByRole('button', { name: 'Remove decision note 1' }));
    expect(screen.getByText('No decision notes yet.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Save term' }));
    const confirmation = screen.getByRole('alertdialog', { name: 'Save glossary term' });
    expect(mocks.updateGlossaryTerm).not.toHaveBeenCalled();
    fireEvent.click(within(confirmation).getByRole('button', { name: 'Save term' }));
    await waitFor(() => expect(mocks.updateGlossaryTerm).toHaveBeenCalledTimes(1));
    expect(savedTerm.evidence.map((evidence) => evidence.evidenceType)).toEqual([
      'SCREENSHOT',
      'STRING_USAGE',
      'CODE_REF',
    ]);
  });

  it('cancels a save without losing the editor changes and prompts again on retry', async () => {
    renderPanel();
    fireEvent.change(await screen.findByLabelText('Part of speech'), {
      target: { value: 'noun' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save term' }));
    const confirmation = screen.getByRole('alertdialog', { name: 'Save glossary term' });
    const focusedNote = within(confirmation).getByLabelText('Decision note (optional)');
    expect(focusedNote).toHaveFocus();
    fireEvent.keyDown(focusedNote, { key: 'Tab', shiftKey: true });
    const confirmButton = within(confirmation).getByRole('button', { name: 'Save term' });
    expect(confirmButton).toHaveFocus();
    fireEvent.keyDown(confirmButton, { key: 'Tab' });
    expect(focusedNote).toHaveFocus();
    fireEvent.click(within(confirmation).getByRole('button', { name: 'Cancel' }));
    expect(mocks.updateGlossaryTerm).not.toHaveBeenCalled();
    expect(screen.getByLabelText('Part of speech')).toHaveValue('noun');
    fireEvent.click(screen.getByRole('button', { name: 'Save term' }));
    const reopened = screen.getByRole('alertdialog', { name: 'Save glossary term' });
    fireEvent.change(within(reopened).getByLabelText('Decision note (optional)'), {
      target: { value: 'Confirmed the grammatical role. https://docs.example.com/terms' },
    });
    fireEvent.click(within(reopened).getByRole('button', { name: 'Save term' }));
    await waitFor(() => expect(mocks.updateGlossaryTerm).toHaveBeenCalledTimes(1));
    expect(mocks.updateGlossaryTerm.mock.calls[0][2]).toMatchObject({
      partOfSpeech: 'noun',
      replaceTerm: false,
      evidence: [
        ...savedTerm.evidence.slice(0, 4),
        {
          evidenceType: 'NOTE',
          caption: 'Confirmed the grammatical role. https://docs.example.com/terms',
        },
      ],
    });
  });

  it('keeps the replacement note on failure and saves it once with the copy options on retry', async () => {
    mocks.updateGlossaryTerm.mockRejectedValueOnce(new Error('Temporary save failure'));
    renderPanel();
    fireEvent.change(await screen.findByLabelText('Definition'), {
      target: { value: 'A shared place for a team.' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Replace term' }));
    const confirmation = screen.getByRole('alertdialog', { name: 'Replace glossary term' });
    expect(mocks.updateGlossaryTerm).not.toHaveBeenCalled();
    const note = within(confirmation).getByLabelText('Decision note (optional)');
    fireEvent.change(note, { target: { value: 'Clarified team scope.' } });
    fireEvent.change(within(confirmation).getByLabelText('Copied translation status'), {
      target: { value: 'REVIEW_NEEDED' },
    });
    fireEvent.click(within(confirmation).getByRole('button', { name: 'Replace term' }));
    expect(await within(confirmation).findByRole('alert')).toHaveTextContent(
      'Temporary save failure',
    );
    expect(note).toHaveValue('Clarified team scope.');
    fireEvent.click(within(confirmation).getByRole('button', { name: 'Replace term' }));
    await screen.findByText('Saved glossary term Workspace.');
    expect(mocks.updateGlossaryTerm).toHaveBeenCalledTimes(2);
    const firstRequest = mocks.updateGlossaryTerm.mock.calls[0][2];
    const retriedRequest = mocks.updateGlossaryTerm.mock.calls[1][2];
    expect(retriedRequest).toEqual(firstRequest);
    expect(retriedRequest).toMatchObject({
      sourceComment: 'A shared place for a team.',
      replaceTerm: true,
      copyTranslationsOnReplace: true,
      copyTranslationStatus: 'REVIEW_NEEDED',
    });
    expect(
      retriedRequest.evidence?.filter((item) => item.caption === 'Clarified team scope.'),
    ).toHaveLength(1);
    expect(retriedRequest.evidence).toHaveLength(5);
  });

  it('lets translators read existing decision notes and links without source-term editing controls', async () => {
    mocks.role = 'ROLE_TRANSLATOR';
    savedTerm.evidence[0].caption = 'Added after review: https://docs.example.com/naming';
    renderPanel();
    const notes = await screen.findByRole('region', { name: 'Decision notes' });
    expect(within(notes).getByText(/Added after review/)).toBeInTheDocument();
    expect(within(notes).getByRole('link')).toHaveAttribute(
      'href',
      'https://docs.example.com/naming',
    );
    expect(within(notes).queryByRole('textbox')).not.toBeInTheDocument();
    expect(within(notes).queryByRole('button')).not.toBeInTheDocument();
  });
});
