import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { Mf2TranslationEditor } from './Mf2TranslationEditor';

const source = `.input {$count :integer}
.match $count
one {{You have {$count} item.}}
* {{You have {$count} items.}}`;

const target = `.input {$count :integer}
.match $count
one {{لديك عنصر واحد.}}
* {{لديك عناصر.}}`;

describe('MF2 plural warning presentation', () => {
  it('accepts an implied Arabic singular count and offers repair only for the variable fallback count', () => {
    render(
      <Mf2TranslationEditor
        locale="ar"
        showArgumentInputs={false}
        showLocaleSelector={false}
        showPreview={false}
        showSource={false}
        source={source}
        target={target}
      />,
    );

    const singularRow = screen
      .getByRole('textbox', { name: 'Target count: one' })
      .closest('.mf2-form-row');
    expect(singularRow).toHaveAttribute('aria-current', 'true');
    expect(singularRow?.querySelector('.mf2-form-issue-badge')).toBeNull();
    expect(singularRow?.querySelector('.mf2-inline-diagnostics')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Restore {$count}' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /count: fallback/u }));

    const fallbackRow = screen
      .getByRole('textbox', { name: 'Target count: fallback' })
      .closest('.mf2-form-row');
    expect(fallbackRow).toHaveAttribute('aria-current', 'true');
    expect(fallbackRow?.querySelector('.mf2-form-issue-badge')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Restore {$count}' })).toBeEnabled();

    const inline = fallbackRow?.querySelector<HTMLElement>('.mf2-inline-diagnostics');
    expect(inline).toBeVisible();
    expect(
      within(inline!).getByText(
        'This form is missing {$count} from the source. Restore it to preserve the information shown to the user.',
      ),
    ).toBeVisible();
    expect(inline).not.toHaveTextContent('variant-missing-placeholder');
    expect(inline).not.toHaveTextContent('mf2-placeholders-changed');

    fireEvent.click(screen.getByRole('button', { name: /count: one/u }));
    expect(screen.getByRole('textbox', { name: 'Target count: one' })).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Restore {$count}' })).not.toBeInTheDocument();
  });
});
