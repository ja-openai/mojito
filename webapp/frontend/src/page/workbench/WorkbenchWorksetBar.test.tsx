import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { WorkbenchCollection } from './workbench-types';
import { WorkbenchWorksetBar } from './WorkbenchWorksetBar';

const noop = vi.fn();

function renderWorksetBar({
  canManageTranslations,
  onChangeMarksMode = noop,
  onChangeShowProtectedTokens = noop,
  onChangeShowDateMetadata = noop,
  showDateMetadata = false,
}: {
  canManageTranslations: boolean;
  onChangeMarksMode?: (mode: 'auto' | 'all' | 'off') => void;
  onChangeShowProtectedTokens?: (show: boolean) => void;
  onChangeShowDateMetadata?: (show: boolean) => void;
  showDateMetadata?: boolean;
}) {
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
      showDisplayOptions
      marksMode="auto"
      onChangeMarksMode={onChangeMarksMode}
      showProtectedTokens
      onChangeShowProtectedTokens={onChangeShowProtectedTokens}
      showDateMetadata={showDateMetadata}
      onChangeShowDateMetadata={onChangeShowDateMetadata}
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

  it('changes workbench display marks from the subbar', async () => {
    const user = userEvent.setup();
    const handleChangeMarksMode = vi.fn();
    renderWorksetBar({
      canManageTranslations: true,
      onChangeMarksMode: handleChangeMarksMode,
    });

    await user.click(screen.getByRole('button', { name: 'Display options' }));
    expect(screen.getByText('Hidden characters')).toBeInTheDocument();
    await user.click(within(screen.getByRole('menu')).getByRole('button', { name: /All/ }));

    expect(handleChangeMarksMode).toHaveBeenCalledWith('all');
  });

  it('offers created and translated date sorting for loaded results', async () => {
    const user = userEvent.setup();
    renderWorksetBar({ canManageTranslations: true });

    await user.click(screen.getByRole('button', { name: 'Sort loaded results' }));

    expect(screen.getByRole('button', { name: /Created date/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Translated date/ })).toBeInTheDocument();
  });

  it('changes workbench display placeholder highlighting from the subbar', async () => {
    const user = userEvent.setup();
    const handleChangeShowProtectedTokens = vi.fn();
    renderWorksetBar({
      canManageTranslations: true,
      onChangeShowProtectedTokens: handleChangeShowProtectedTokens,
    });

    expect(screen.getByText('Display')).toBeInTheDocument();
    expect(screen.queryByText(/Tokens on/)).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Display options' }));
    expect(screen.getByText('Placeholder highlights')).toBeInTheDocument();
    await user.click(
      within(screen.getByRole('menu')).getByRole('button', {
        name: /Render placeholder text without highlights/,
      }),
    );

    expect(handleChangeShowProtectedTokens).toHaveBeenCalledWith(false);
  });

  it('changes workbench display date metadata from the subbar', async () => {
    const user = userEvent.setup();
    const handleChangeShowDateMetadata = vi.fn();
    renderWorksetBar({
      canManageTranslations: true,
      onChangeShowDateMetadata: handleChangeShowDateMetadata,
    });

    await user.click(screen.getByRole('button', { name: 'Display options' }));
    expect(screen.getByText('Dates')).toBeInTheDocument();
    await user.click(
      within(screen.getByRole('menu')).getByRole('button', {
        name: /Show created and translated dates/,
      }),
    );

    expect(handleChangeShowDateMetadata).toHaveBeenCalledWith(true);
  });
});
