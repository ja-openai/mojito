import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { LocaleMultiSelect } from './LocaleMultiSelect';

describe('LocaleMultiSelect', () => {
  it('shows locale presets instead of legacy bulk action links', () => {
    const handleChange = vi.fn();

    render(
      <LocaleMultiSelect
        options={[
          { tag: 'sq', label: 'Albanian' },
          { tag: 'sq-AL', label: 'Albanian (Albania)' },
          { tag: 'am', label: 'Amharic' },
        ]}
        selectedTags={[]}
        onChange={handleChange}
        myLocaleTags={['sq', 'am']}
        showSelectionPresets
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Locales' }));

    const menu = screen.getByRole('menu');
    expect(within(menu).getByRole('button', { name: 'Select all locales' })).toHaveTextContent(
      'All',
    );
    expect(within(menu).getByRole('button', { name: 'Select your locales' })).toHaveTextContent(
      'My locales',
    );
    expect(within(menu).getByRole('button', { name: 'Clear locale selection' })).toHaveTextContent(
      'None',
    );
    expect(within(menu).queryByRole('button', { name: 'Select all' })).not.toBeInTheDocument();
    expect(within(menu).queryByRole('button', { name: 'Clear' })).not.toBeInTheDocument();

    fireEvent.click(within(menu).getByRole('button', { name: 'Select your locales' }));

    expect(handleChange).toHaveBeenCalledWith(['sq', 'am']);
  });
});
