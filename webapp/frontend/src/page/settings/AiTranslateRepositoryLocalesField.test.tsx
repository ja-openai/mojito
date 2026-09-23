import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import type { ApiRepository } from '../../api/repositories';
import { AiTranslateRepositoryLocalesField } from './AiTranslateRepositoryLocalesField';

vi.mock('../../hooks/useLocales', () => ({
  useLocales: () => ({
    data: ['en', 'fr', 'fr-CA', 'de', 'ja'].map((bcp47Tag) => ({ bcp47Tag })),
  }),
}));

const repositories: ApiRepository[] = [
  {
    id: 1,
    name: 'First repository',
    repositoryLocales: ['en', 'fr', 'fr-CA'].map((tag) => ({
      locale: { bcp47Tag: tag },
      parentLocale: tag === 'en' ? null : { bcp47Tag: 'en' },
      toBeFullyTranslated: true,
    })),
  },
  {
    id: 2,
    name: 'Second repository',
    repositoryLocales: [
      {
        locale: { bcp47Tag: 'de' },
        parentLocale: { bcp47Tag: 'en' },
        toBeFullyTranslated: true,
      },
    ],
  },
];

function Harness({ onChange }: { onChange: (next: Record<string, string[]>) => void }) {
  const [value, setValue] = useState<Record<string, string[]>>({ '1': ['fr', 'ja'], '99': ['es'] });
  return (
    <AiTranslateRepositoryLocalesField
      repositories={repositories}
      excludedLocaleTagsByRepositoryId={value}
      onChange={(next) => {
        setValue(next);
        onChange(next);
      }}
    />
  );
}

function selectRepository(name: string) {
  fireEvent.click(
    screen.getByRole('button', { name: 'Choose repository for automatic AI locale exclusions' }),
  );
  fireEvent.click(screen.getByRole('button', { name }));
}

function toggleLocales() {
  fireEvent.click(
    screen.getByRole('button', { name: 'Select automatic AI translation excluded locales' }),
  );
}

describe('AiTranslateRepositoryLocalesField', () => {
  it('shows target locales and existing exclusions, with access to all locales', () => {
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);
    selectRepository('First repository');
    toggleLocales();

    expect(screen.getByRole('checkbox', { name: /^French\s*fr\b/ })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: /^Japanese\s*ja\b/ })).toBeChecked();
    expect(
      screen.getByRole('checkbox', { name: /^French \(Canada\)\s*fr-CA\b/ }),
    ).not.toBeChecked();
    expect(screen.queryByRole('checkbox', { name: /^English\s*en\b/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: /^German\s*de\b/ })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Show all locales' }));
    fireEvent.click(screen.getByRole('checkbox', { name: /^German\s*de\b/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Show repository locales' }));
    expect(screen.getByRole('checkbox', { name: /^German\s*de\b/ })).toBeChecked();
    expect(onChange).toHaveBeenLastCalledWith({ '1': ['fr', 'ja', 'de'], '99': ['es'] });
  });

  it('keeps each repository draft separate and clears only the selected repository', () => {
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);
    selectRepository('First repository');
    toggleLocales();
    fireEvent.click(screen.getByRole('checkbox', { name: /^French \(Canada\)\s*fr-CA\b/ }));
    toggleLocales();
    selectRepository('Second repository');
    toggleLocales();
    expect(screen.getByRole('checkbox', { name: /^German\s*de\b/ })).not.toBeChecked();
    expect(screen.queryByRole('checkbox', { name: /^French\s*fr\b/ })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('checkbox', { name: /^German\s*de\b/ }));
    toggleLocales();
    selectRepository('First repository');
    toggleLocales();
    expect(screen.getByRole('checkbox', { name: /^French \(Canada\)\s*fr-CA\b/ })).toBeChecked();
    fireEvent.click(screen.getByRole('button', { name: 'Clear locale selection' }));
    expect(onChange).toHaveBeenLastCalledWith({ '2': ['de'], '99': ['es'] });
  });

  it('hides an out-of-scope repository without removing its rules', () => {
    const onChange = vi.fn();
    const value = { '1': ['fr'] };
    const view = render(
      <AiTranslateRepositoryLocalesField
        repositories={repositories}
        excludedLocaleTagsByRepositoryId={value}
        onChange={onChange}
      />,
    );
    selectRepository('First repository');
    view.rerender(
      <AiTranslateRepositoryLocalesField
        repositories={repositories.slice(1)}
        excludedLocaleTagsByRepositoryId={value}
        onChange={onChange}
      />,
    );
    expect(
      screen.queryByRole('button', { name: 'Select automatic AI translation excluded locales' }),
    ).not.toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
    selectRepository('Second repository');
    toggleLocales();
    fireEvent.click(screen.getByRole('checkbox', { name: /^German\s*de\b/ }));
    expect(onChange).toHaveBeenCalledWith({ '1': ['fr'], '2': ['de'] });
  });
});
