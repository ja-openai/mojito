import { render, screen, waitFor, within } from '@testing-library/react';
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

  it('combines the input and output payloads into one readable report', async () => {
    renderPage();

    expect(await screen.findByText('checkout.json')).toBeInTheDocument();
    await waitFor(() => expect(mocks.fetchBulkImportRuns).toHaveBeenCalledWith(50));
    await waitFor(() => expect(mocks.fetchBulkImportInput).toHaveBeenCalledWith(run.runId));
    await waitFor(() => expect(mocks.fetchBulkImportOutput).toHaveBeenCalledWith(run.runId));

    const report = await screen.findByRole('table', { name: 'Bulk import text units' });
    expect(within(report).getByText('checkout.button')).toBeInTheDocument();
    expect(within(report).getByText('Checkout')).toBeInTheDocument();
    expect(within(report).getByText('Paiement')).toBeInTheDocument();
    expect(within(report).getByText('IMPORTED')).toBeInTheDocument();
    expect(within(report).getByText('31 → 32')).toBeInTheDocument();

    expect(screen.getByRole('link', { name: 'Open input JSON' })).toHaveAttribute(
      'href',
      `/api/monitoring/import-lineage/${run.runId}/input`,
    );
    expect(screen.getByRole('link', { name: 'Open output JSON' })).toHaveAttribute(
      'href',
      `/api/monitoring/import-lineage/${run.runId}/output`,
    );
  });

  it('redirects non-admin users', async () => {
    mocks.role = 'ROLE_PM';
    renderPage();

    expect(await screen.findByText('Repositories')).toBeInTheDocument();
    expect(mocks.fetchBulkImportRuns).not.toHaveBeenCalled();
  });
});
