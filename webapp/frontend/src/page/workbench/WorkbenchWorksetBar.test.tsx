import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { WorkbenchCollection } from './workbench-types';
import { WorkbenchWorksetBar } from './WorkbenchWorksetBar';

const noop = vi.fn();

function renderWorksetBar({ canManageTranslations }: { canManageTranslations: boolean }) {
  const collections: WorkbenchCollection[] = [];

  return render(
    <WorkbenchWorksetBar
      disabled={false}
      isAdmin={canManageTranslations}
      canManageTranslations={canManageTranslations}
      isSearchLoading={false}
      hasSearched
      rowCount={3}
      hasMoreResults={false}
      worksetSize={100}
      onChangeWorksetSize={noop}
      resultSortField="source"
      onChangeResultSortField={noop}
      resultSortDirection="default"
      onChangeResultSortDirection={noop}
      editedCount={0}
      onRefreshWorkset={noop}
      onResetWorkbench={noop}
      bulkActionRowCount={2}
      isApplyingBulkAction={false}
      onRequestDeleteAll={noop}
      onRequestBulkStatusChange={noop}
      onOpenExportModal={noop}
      onOpenImportModal={noop}
      onOpenShareModal={noop}
      collections={collections}
      activeCollectionId={null}
      activeCollectionName={null}
      activeCollectionCount={0}
      onCreateCollection={() => null}
      onSelectCollection={noop}
      onRenameCollection={noop}
      onDeleteCollection={noop}
      onClearCollection={noop}
      onDeleteAllCollections={noop}
      onAddAllToCollection={noop}
      onOpenCollectionSearch={noop}
      onShareCollection={() => true}
      onCreateReviewProject={noop}
      onOpenAiTranslate={noop}
    />,
  );
}

describe('WorkbenchWorksetBar permissions', () => {
  it('hides export, share, and delete actions from translators', async () => {
    const user = userEvent.setup();
    renderWorksetBar({ canManageTranslations: false });

    expect(screen.queryByRole('button', { name: 'Export' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Share' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Bulk: 2/ }));

    expect(screen.getByRole('button', { name: 'Change status' })).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Delete loaded translations' }),
    ).not.toBeInTheDocument();
  });

  it('shows export, share, and delete actions to PMs and admins', async () => {
    const user = userEvent.setup();
    renderWorksetBar({ canManageTranslations: true });

    expect(screen.getByRole('button', { name: 'Export' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Share' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Bulk: 2/ }));

    expect(screen.getByRole('button', { name: 'Delete loaded translations' })).toBeInTheDocument();
  });
});
