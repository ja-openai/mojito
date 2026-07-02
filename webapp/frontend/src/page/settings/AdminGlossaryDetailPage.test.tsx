import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AdminGlossaryDetailPage } from './AdminGlossaryDetailPage';

const mocks = vi.hoisted(() => ({
  deleteGlossary: vi.fn(),
  downloadBlob: vi.fn(),
  exportInflectionProfilePack: vi.fn(),
  fetchCompiledInflectionProfilePack: vi.fn(),
  fetchGlossary: vi.fn(),
  fetchInflectionProfiles: vi.fn(),
  importInflectionProfiles: vi.fn(),
  reviewInflectionProfile: vi.fn(),
  updateGlossary: vi.fn(),
  upsertInflectionProfile: vi.fn(),
}));

vi.mock('../../api/glossaries', () => ({
  deleteGlossary: mocks.deleteGlossary,
  exportInflectionProfilePack: mocks.exportInflectionProfilePack,
  fetchCompiledInflectionProfilePack: mocks.fetchCompiledInflectionProfilePack,
  fetchGlossary: mocks.fetchGlossary,
  fetchInflectionProfiles: mocks.fetchInflectionProfiles,
  importInflectionProfiles: mocks.importInflectionProfiles,
  reviewInflectionProfile: mocks.reviewInflectionProfile,
  updateGlossary: mocks.updateGlossary,
  upsertInflectionProfile: mocks.upsertInflectionProfile,
}));

vi.mock('../../hooks/useLocales', () => ({
  useLocales: () => ({ data: [{ bcp47Tag: 'fr-FR' }], isLoading: false, isError: false }),
}));

vi.mock('../../hooks/useRepositories', () => ({
  REPOSITORIES_QUERY_KEY: ['repositories'],
  useRepositories: () => ({
    data: [{ id: 10, name: 'web', repositoryLocales: [] }],
    isLoading: false,
    isError: false,
  }),
}));

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({
    username: 'admin',
    role: 'ROLE_ADMIN',
    canTranslateAllLocales: true,
    userLocales: [],
  }),
}));

vi.mock('../workbench/workbench-import-export', () => ({
  downloadBlob: mocks.downloadBlob,
}));

const glossary = {
  id: 42,
  name: 'Product glossary',
  description: null,
  enabled: true,
  priority: 0,
  scopeMode: 'GLOBAL',
  backingRepository: { id: 10, name: 'web' },
  assetPath: 'checkout.json',
  localeTags: ['fr-FR'],
  repositories: [],
  excludedRepositories: [],
};

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      mutations: { retry: false },
      queries: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/settings/system/glossaries/42']}>
        <Routes>
          <Route
            path="/settings/system/glossaries/:glossaryId"
            element={<AdminGlossaryDetailPage />}
          />
          <Route path="/repositories" element={<div>Repositories</div>} />
          <Route path="/glossaries" element={<div>Glossaries</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function readBlobAsText(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(reader.error ?? new Error('Failed to read blob.'));
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '');
    reader.readAsText(blob);
  });
}

describe('AdminGlossaryDetailPage inflection profile packs', () => {
  beforeEach(() => {
    Object.values(mocks).forEach((mock) => mock.mockReset());
    mocks.fetchGlossary.mockResolvedValue(glossary);
    mocks.fetchInflectionProfiles.mockResolvedValue({ profiles: [] });
  });

  it('downloads exported authoring profile packs with the backend attachment filename', async () => {
    const user = userEvent.setup();
    const content = '{"schema":"mojito-mf2-inflection/term-inflection-profile-pack/v0"}';
    mocks.exportInflectionProfilePack.mockResolvedValue({
      content,
      filename: 'glossary-42-inflection-fr-FR.json',
      pack: {},
      profileCount: 1,
    });

    renderPage();

    const exportButton = await screen.findByRole('button', { name: 'Export profile pack' });
    await waitFor(() => expect(exportButton).toBeEnabled());
    await user.click(exportButton);

    await waitFor(() =>
      expect(mocks.exportInflectionProfilePack).toHaveBeenCalledWith(42, 'fr-FR'),
    );
    expect(mocks.downloadBlob).toHaveBeenCalledWith(
      expect.any(Blob),
      'glossary-42-inflection-fr-FR.json',
    );
    const [blob] = mocks.downloadBlob.mock.calls[0] as [Blob, string];
    await expect(readBlobAsText(blob)).resolves.toBe(content);
    expect(await screen.findByText('Exported 1 inflection profiles for fr-FR.')).toBeVisible();
  });

  it('imports selected authoring profile pack JSON through the validating endpoint', async () => {
    const user = userEvent.setup();
    mocks.importInflectionProfiles.mockResolvedValue({
      localeTag: 'fr-FR',
      profileCount: 1,
      createdProfileCount: 1,
      updatedProfileCount: 0,
      profiles: [],
    });

    const { container } = renderPage();

    await screen.findByRole('button', { name: 'Import profile pack' });
    const input = container.querySelector('input[type="file"]');
    expect(input).toBeInstanceOf(HTMLInputElement);

    const file = new File(['{"schema":"pack"}'], 'fr-pack.json', {
      type: 'application/json',
    });
    await user.upload(input as HTMLInputElement, file);

    await waitFor(() =>
      expect(mocks.importInflectionProfiles).toHaveBeenCalledWith(42, '{"schema":"pack"}'),
    );
    expect(
      await screen.findByText(
        'Imported 1 inflection profiles from fr-pack.json: 1 created, 0 updated.',
      ),
    ).toBeVisible();
  });

  it('shows missing form keys from inflection profile review responses', async () => {
    mocks.fetchInflectionProfiles.mockResolvedValue({
      profiles: [
        {
          id: 7,
          glossaryTermMetadataId: 8,
          tmTextUnitId: 9,
          termId: 'ar.explicit.mother',
          source: 'mother',
          localeTag: 'fr-FR',
          schema: 'mojito-mf2-inflection/term-inflection-profile-pack/v0',
          status: 'REVIEW_NEEDED',
          morphologyJson: '{"partOfSpeech":"noun"}',
          formsJson: '{"construct.genitive.singular":"term"}',
          diagnosticsJson:
            '[{"reason":"missing-form-cell","formKey":"construct.genitive.dual","message":"Missing dual"}]',
          missingFormKeys: ['construct.genitive.dual', 'construct.accusative.dual'],
          provenanceJson: '{"source":"unit-test"}',
        },
      ],
    });

    renderPage();

    expect(await screen.findByText('ar.explicit.mother')).toBeVisible();
    expect(
      await screen.findByText('Missing forms: construct.accusative.dual, construct.genitive.dual'),
    ).toBeVisible();
    expect(
      screen.getByText('Missing dual · missing-form-cell · construct.genitive.dual'),
    ).toBeVisible();
  });

  it('shows Turkish explicit-template review diagnostics without missing form keys', async () => {
    mocks.fetchInflectionProfiles.mockResolvedValue({
      profiles: [
        {
          id: 8,
          glossaryTermMetadataId: 9,
          tmTextUnitId: 10,
          termId: 'tr.explicit.cakmak',
          source: 'çakmak',
          localeTag: 'tr',
          schema: 'mojito-mf2-inflection/term-inflection-profile-pack/v0',
          status: 'REVIEW_NEEDED',
          morphologyJson: '{"partOfSpeech":"noun"}',
          formsJson: '{"bare.singular":"çakmak"}',
          diagnosticsJson:
            '[{"reason":"requires-explicit-review","code":"turkish-explicit-template-review","message":"Turkish supplemental exception requires explicit template forms","termId":"tr.explicit.cakmak"}]',
          missingFormKeys: [],
          provenanceJson: '{"source":"tr-suffix-survey"}',
        },
      ],
    });

    renderPage();

    expect(await screen.findByText('tr.explicit.cakmak')).toBeVisible();
    expect(screen.queryByText(/Missing forms:/u)).toBeNull();
    expect(
      screen.getByText(
        'Turkish supplemental exception requires explicit template forms · requires-explicit-review · turkish-explicit-template-review',
      ),
    ).toBeVisible();
  });

  it('shows skipped profile diagnostics from the compiled preview context', async () => {
    const user = userEvent.setup();
    mocks.fetchInflectionProfiles.mockResolvedValue({
      profiles: [
        {
          id: 11,
          glossaryTermMetadataId: 12,
          tmTextUnitId: 13,
          termId: 'ar.explicit.mother',
          source: 'mother',
          localeTag: 'fr-FR',
          schema: 'mojito-mf2-inflection/term-inflection-profile-pack/v0',
          status: 'DISABLED',
          morphologyJson: '{"partOfSpeech":"noun"}',
          formsJson: '{"construct.genitive.singular":"term"}',
          diagnosticsJson:
            '[{"reason":"missing-form-cell","formKey":"construct.genitive.dual","message":"Missing dual"}]',
          missingFormKeys: ['construct.genitive.dual'],
          provenanceJson: '{"source":"unit-test"}',
        },
      ],
    });
    mocks.fetchCompiledInflectionProfilePack.mockResolvedValue({
      approvedProfileCount: 1,
      skippedProfileCount: 1,
      runtimeExport: 'closed-world-glossary-approved-profile-forms',
      compositionMode: 'explicit-form-rows-v0',
      profileCount: 1,
      formCount: 4,
      content: '{"schema":"compiled"}',
      pack: {},
    });

    renderPage();

    const previewButton = await screen.findByRole('button', { name: 'Preview compiled pack' });
    expect(
      screen.getByText('Check generated term-form diagnostics before checked V0 compiled export.'),
    ).toBeVisible();
    await waitFor(() => expect(previewButton).toBeEnabled());
    await user.click(previewButton);

    expect(await screen.findByText('Skipped profile diagnostics')).toBeVisible();
    expect(screen.getAllByText(/ar\.explicit\.mother/u).length).toBeGreaterThanOrEqual(2);
    expect(
      screen.getAllByText(/Missing forms: construct\.genitive\.dual/u).length,
    ).toBeGreaterThanOrEqual(2);
    expect(
      screen.getAllByText(/Missing dual · missing-form-cell · construct\.genitive\.dual/u).length,
    ).toBeGreaterThanOrEqual(2);
  });

  it('shows compiled preview policy and counts for approved explicit rows', async () => {
    const user = userEvent.setup();
    mocks.fetchInflectionProfiles.mockResolvedValue({
      profiles: [
        {
          id: 12,
          glossaryTermMetadataId: 13,
          tmTextUnitId: 14,
          termId: 'ar.explicit.message',
          source: 'message',
          localeTag: 'fr-FR',
          schema: 'mojito-mf2-inflection/term-inflection-profile-pack/v0',
          status: 'APPROVED',
          morphologyJson: '{"partOfSpeech":"noun","gender":"feminine"}',
          formsJson: '{"construct.genitive.dual":"رسالتي","indefinite.genitive.plural":"رسائل"}',
          diagnosticsJson: '[]',
          missingFormKeys: [],
          provenanceJson: '{"reviewedBy":"translator"}',
        },
      ],
    });
    const content = '{"schema":"compiled","strings":["ar.explicit.message","رسالتي"]}';
    mocks.fetchCompiledInflectionProfilePack.mockResolvedValue({
      approvedProfileCount: 1,
      skippedProfileCount: 0,
      runtimeExport: 'closed-world-glossary-approved-profile-forms',
      compositionMode: 'explicit-form-rows-v0',
      profileCount: 1,
      formCount: 2,
      content,
      pack: {},
    });

    renderPage();

    const previewButton = await screen.findByRole('button', { name: 'Preview compiled pack' });
    await waitFor(() => expect(previewButton).toBeEnabled());
    await user.click(previewButton);

    expect(
      await screen.findByText(
        'Compiled pack preview is ready (explicit-form-rows-v0): 1 approved, 0 skipped.',
      ),
    ).toBeVisible();
    expect(
      await screen.findByText((text) =>
        [
          '1 runtime profiles',
          '2 forms',
          `${content.length} bytes`,
          '1 approved',
          '0 skipped',
          'runtime export: closed-world-glossary-approved-profile-forms',
          'composition: explicit-form-rows-v0',
        ].every((part) => text.includes(part)),
      ),
    ).toBeVisible();
    expect(screen.queryByText('Skipped profile diagnostics')).toBeNull();
  });

  it('saves reviewed form-grid edits through the review endpoint before approving', async () => {
    const user = userEvent.setup();
    mocks.fetchInflectionProfiles.mockResolvedValue({
      profiles: [
        {
          id: 7,
          glossaryTermMetadataId: 8,
          tmTextUnitId: 9,
          termId: 'he.reviewed.hand',
          source: 'hand',
          localeTag: 'fr-FR',
          schema: 'mojito-mf2-inflection/term-inflection-profile-pack/v0',
          status: 'REVIEW_NEEDED',
          morphologyJson: '{"partOfSpeech":"noun"}',
          formsJson: '{"bare.singular":"hand"}',
          diagnosticsJson:
            '[{"reason":"missing-form-cell","formKey":"construct.dual","message":"Missing dual"}]',
          missingFormKeys: ['construct.dual'],
          provenanceJson: '{"source":"dictionary-prefill"}',
        },
      ],
    });
    mocks.reviewInflectionProfile.mockResolvedValue({
      id: 7,
      glossaryTermMetadataId: 8,
      tmTextUnitId: 9,
      termId: 'he.reviewed.hand',
      source: 'hand',
      localeTag: 'fr-FR',
      schema: 'mojito-mf2-inflection/term-inflection-profile-pack/v0',
      status: 'APPROVED',
      morphologyJson: '{"partOfSpeech":"noun"}',
      formsJson: '{"bare.singular":"hand","construct.dual":"dual-hand"}',
      diagnosticsJson: '[]',
      provenanceJson: '{"source":"dictionary-prefill"}',
    });

    renderPage();

    await user.click(await screen.findByRole('button', { name: 'Edit profile' }));
    await user.click(screen.getByRole('button', { name: 'Add form' }));
    await user.clear(screen.getByLabelText('Form key for bare.plural'));
    await user.type(screen.getByLabelText('Form key for new form'), 'construct.dual');
    await user.type(screen.getByLabelText('Form text for construct.dual'), 'dual-hand');
    await user.click(screen.getByRole('button', { name: 'Save and approve' }));

    await waitFor(() =>
      expect(mocks.reviewInflectionProfile).toHaveBeenCalledWith(42, 9, 'fr-FR', {
        status: 'APPROVED',
        morphologyJson: '{"partOfSpeech":"noun"}',
        formsJson: '{"bare.singular":"hand","construct.dual":"dual-hand"}',
        diagnosticsJson: '[]',
        provenanceJson: '{"source":"dictionary-prefill"}',
      }),
    );
    expect(mocks.upsertInflectionProfile).not.toHaveBeenCalled();
    expect(await screen.findByText('he.reviewed.hand is now APPROVED.')).toBeVisible();
  });
});
