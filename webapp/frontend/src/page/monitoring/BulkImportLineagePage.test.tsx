import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BulkImportLineagePage } from './BulkImportLineagePage';

const mocks = vi.hoisted(() => ({
  fetchBulkImportInput: vi.fn(),
  fetchBulkImportOutput: vi.fn(),
  fetchBulkImportRuns: vi.fn(),
  role: 'ROLE_ADMIN',
}));

vi.mock('../../api/monitoring', () => ({
  fetchBulkImportInput: mocks.fetchBulkImportInput,
  fetchBulkImportOutput: mocks.fetchBulkImportOutput,
  fetchBulkImportRuns: mocks.fetchBulkImportRuns,
}));

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({ username: 'admin', role: mocks.role }),
}));

const run = {
  runId: 'run-123',
  createdDate: '2026-08-27T18:00:00Z',
  completedDate: '2026-08-27T18:01:00Z',
  repositoryId: 7,
  repositoryName: 'checkout',
  assetId: 9,
  assetPath: 'checkout.json',
  locale: 'fr-FR',
  pollableTaskId: 44,
  initiatingUserId: 2,
  actorType: 'HUMAN',
  actorIdentity: 'operator@example.com',
  source: 'TEXT_UNITS_BATCH_API',
  importMode: 'ALWAYS_IMPORT',
  integrityChecksType: 'SKIP',
  status: 'COMPLETED',
  requestedCount: 1,
  importedCount: 1,
  skippedCount: 0,
  inputPayloadBlobName: 'run-123/input.json',
  outputPayloadBlobName: 'run-123/output.json',
  errorMessage: null,
};

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/monitoring/bulk-imports']}>
      <Routes>
        <Route path="/monitoring/bulk-imports" element={<BulkImportLineagePage />} />
        <Route path="/repositories" element={<div>Repositories</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('BulkImportLineagePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    mocks.role = 'ROLE_ADMIN';
    mocks.fetchBulkImportRuns.mockResolvedValue([run]);
    mocks.fetchBulkImportInput.mockResolvedValue({
      runId: run.runId,
      repository: run.repositoryName,
      locale: run.locale,
      assetPath: run.assetPath,
      source: run.source,
      importMode: run.importMode,
      integrityChecksType: run.integrityChecksType,
      textUnits: [
        {
          tmTextUnitId: 12,
          name: 'checkout.button',
          source: 'Checkout',
          target: 'Paiement',
          includedInLocalizedFile: true,
          translatorIdentity: 'translator@example.com',
          reviewerIdentity: 'reviewer@example.com',
        },
      ],
    });
    mocks.fetchBulkImportOutput.mockResolvedValue({
      runId: run.runId,
      status: 'COMPLETED',
      requestedCount: 1,
      importedCount: 1,
      skippedCount: 0,
      textUnits: [
        {
          tmTextUnitId: 12,
          name: 'checkout.button',
          previousTmTextUnitVariantId: 31,
          resultingTmTextUnitVariantId: 32,
          status: 'IMPORTED',
          translatorIdentity: 'translator@example.com',
          reviewerIdentity: 'reviewer@example.com',
        },
      ],
    });
  });

  it('shows a resizable run list and combines payloads into a readable side-pane report', async () => {
    renderPage();

    const runList = await screen.findByRole('complementary', { name: 'Bulk import runs' });
    expect(within(runList).getByText('checkout.json')).toBeInTheDocument();
    expect(within(runList).getByText('operator@example.com')).toBeInTheDocument();
    const resizeHandle = screen.getByRole('separator', { name: 'Resize bulk import run list' });
    expect(resizeHandle).toHaveAttribute('aria-valuenow', '42');
    fireEvent.keyDown(resizeHandle, { key: 'ArrowRight' });
    expect(resizeHandle).toHaveAttribute('aria-valuenow', '44');
    expect(window.localStorage.getItem('bulk-import-lineage:run-list-width-percent')).toBe('44');
    expect(screen.getByRole('region', { name: 'Selected bulk import report' })).toBeInTheDocument();
    await waitFor(() => expect(mocks.fetchBulkImportRuns).toHaveBeenCalledWith(50));
    await waitFor(() => expect(mocks.fetchBulkImportInput).toHaveBeenCalledWith(run.runId));
    await waitFor(() => expect(mocks.fetchBulkImportOutput).toHaveBeenCalledWith(run.runId));

    const report = await screen.findByRole('table', { name: 'Bulk import text units' });
    expect(within(report).getByText('checkout.button')).toBeInTheDocument();
    expect(within(report).getByText('Checkout')).toBeInTheDocument();
    expect(within(report).getByText('Paiement')).toBeInTheDocument();
    expect(within(report).getByText('IMPORTED')).toBeInTheDocument();
    expect(within(report).getByText('31 → 32')).toBeInTheDocument();

    expect(screen.getByRole('link', { name: 'Open normalized input JSON' })).toHaveAttribute(
      'href',
      `/api/monitoring/import-lineage/${run.runId}/input`,
    );
    expect(screen.getByRole('link', { name: 'Open result JSON' })).toHaveAttribute(
      'href',
      `/api/monitoring/import-lineage/${run.runId}/output`,
    );
  });

  it('selects a run from the compact master table', async () => {
    const secondRun = {
      ...run,
      runId: 'run-456',
      repositoryName: 'billing',
      assetPath: 'billing.json',
      actorIdentity: 'billing-operator@example.com',
      inputPayloadBlobName: null,
      outputPayloadBlobName: null,
    };
    mocks.fetchBulkImportRuns.mockResolvedValue([run, secondRun]);
    renderPage();

    const secondRunButton = await screen.findByRole('button', { name: /billing billing.json/i });
    expect(secondRunButton).toHaveAttribute('aria-pressed', 'false');

    fireEvent.click(secondRunButton);

    expect(secondRunButton).toHaveAttribute('aria-pressed', 'true');
    expect(
      within(screen.getByRole('region', { name: 'Selected bulk import report' })).getByText(
        secondRun.runId,
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Open normalized input JSON' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Open result JSON' })).toBeNull();
  });

  it.each([
    {
      name: 'stable AI service identities',
      source: 'AI_TRANSLATE',
      actorIdentity: 'ai-translate',
      translatorIdentity: 'ai-translate',
      reviewerIdentity: 'NOT_REVIEWED',
      expectedActor: 'AI Translate service',
      expectedTranslator: 'AI Translate service',
      expectedReviewer: 'Not reviewed',
    },
    {
      name: 'stable machine translation service identities',
      source: 'MACHINE_TRANSLATION',
      actorIdentity: 'machine-translation',
      translatorIdentity: 'machine-translation',
      reviewerIdentity: 'NOT_REVIEWED',
      expectedActor: 'Machine Translation service',
      expectedTranslator: 'Machine Translation service',
      expectedReviewer: 'Not reviewed',
    },
    {
      name: 'manual AI workflow attribution',
      source: 'AI_TRANSLATE',
      actorIdentity: 'operator@example.com',
      translatorIdentity: 'translator@example.com',
      reviewerIdentity: 'reviewer@example.com',
      expectedActor: 'operator@example.com',
      expectedTranslator: 'translator@example.com',
      expectedReviewer: 'reviewer@example.com',
    },
    {
      name: 'historical unknown AI attribution',
      source: 'AI_TRANSLATE',
      actorIdentity: 'UNKNOWN',
      translatorIdentity: 'UNKNOWN',
      reviewerIdentity: 'UNKNOWN',
      expectedActor: 'Not recorded (AI Translate workflow)',
      expectedTranslator: 'Not recorded (AI Translate workflow)',
      expectedReviewer: 'Not recorded',
    },
    {
      name: 'historical unknown machine translation attribution',
      source: 'MACHINE_TRANSLATION',
      actorIdentity: 'UNKNOWN',
      translatorIdentity: 'UNKNOWN',
      reviewerIdentity: 'UNKNOWN',
      expectedActor: 'Not recorded (Machine Translation workflow)',
      expectedTranslator: 'Not recorded (Machine Translation workflow)',
      expectedReviewer: 'Not recorded',
    },
    {
      name: 'unknown human-provided attribution',
      source: 'TEXT_UNITS_BATCH_API',
      actorIdentity: 'UNKNOWN',
      translatorIdentity: 'UNKNOWN',
      reviewerIdentity: 'UNKNOWN',
      expectedActor: 'Not provided',
      expectedTranslator: 'Not provided',
      expectedReviewer: 'Not recorded',
    },
    {
      name: 'missing human-provided attribution',
      source: 'TEXT_UNITS_BATCH_API',
      actorIdentity: null,
      translatorIdentity: '',
      reviewerIdentity: '',
      expectedActor: 'Not provided',
      expectedTranslator: 'Not provided',
      expectedReviewer: 'Not provided',
    },
  ])(
    'renders $name truthfully',
    async ({
      source,
      actorIdentity,
      translatorIdentity,
      reviewerIdentity,
      expectedActor,
      expectedTranslator,
      expectedReviewer,
    }) => {
      const attributedRun = {
        ...run,
        runId: `run-${source}`,
        source,
        actorType: 'UNKNOWN',
        actorIdentity,
      };
      mocks.fetchBulkImportRuns.mockResolvedValue([attributedRun]);
      mocks.fetchBulkImportInput.mockResolvedValue({
        runId: attributedRun.runId,
        repository: attributedRun.repositoryName,
        locale: attributedRun.locale,
        assetPath: attributedRun.assetPath,
        source,
        importMode: attributedRun.importMode,
        integrityChecksType: attributedRun.integrityChecksType,
        textUnits: [
          {
            tmTextUnitId: 12,
            name: 'checkout.button',
            source: 'Checkout',
            target: 'Paiement',
            includedInLocalizedFile: true,
            translatorIdentity,
            reviewerIdentity,
          },
        ],
      });
      mocks.fetchBulkImportOutput.mockResolvedValue({
        runId: attributedRun.runId,
        status: 'COMPLETED',
        requestedCount: 1,
        importedCount: 1,
        skippedCount: 0,
        textUnits: [
          {
            tmTextUnitId: 12,
            name: 'checkout.button',
            status: 'IMPORTED',
            translatorIdentity,
            reviewerIdentity,
          },
        ],
      });
      renderPage();

      const runList = await screen.findByRole('complementary', { name: 'Bulk import runs' });
      expect(within(runList).getByText(expectedActor)).toBeInTheDocument();
      const report = await screen.findByRole('table', { name: 'Bulk import text units' });
      expect(within(report).getByText(expectedTranslator)).toBeInTheDocument();
      expect(within(report).getByText(`Reviewer: ${expectedReviewer}`)).toBeInTheDocument();
    },
  );

  it('redirects non-admin users', async () => {
    mocks.role = 'ROLE_PM';
    renderPage();

    expect(await screen.findByText('Repositories')).toBeInTheDocument();
    expect(mocks.fetchBulkImportRuns).not.toHaveBeenCalled();
  });
});
