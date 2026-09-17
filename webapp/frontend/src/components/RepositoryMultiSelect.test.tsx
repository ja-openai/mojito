import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { RepositoryMultiSelect } from './RepositoryMultiSelect';

describe('RepositoryMultiSelect', () => {
  it('keeps the repository name for a single selection without repository presets', () => {
    render(
      <RepositoryMultiSelect
        options={[{ id: 1, name: 'Website', secondaryLabel: 'Product content' }]}
        selectedIds={[1]}
        onChange={vi.fn()}
        selectionMode="single"
        showSelectionPresets
      />,
    );

    const trigger = screen.getByRole('button', { name: 'Repositories' });
    expect(trigger).toHaveTextContent('Website');
    fireEvent.click(trigger);
    const menu = screen.getByRole('menu');
    expect(within(menu).getByRole('radio', { name: 'Website Product content' })).toBeChecked();
    expect(within(menu).queryByRole('button')).not.toBeInTheDocument();
  });
});
