import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { Mf2DocumentPreview } from './Mf2DocumentPreview';

function preview(value: string) {
  return render(<Mf2DocumentPreview marksMode="off" value={value} />).container;
}

describe('MF2 document presentation', () => {
  it('encloses status structure and exposes every translatable body without variable chips', () => {
    const value = `.input {$status :string}\n.match $status\nactive {{Active}}\npaused {{Paused}}\n* {{Unknown}}`;
    const container = preview(value);
    expect(container.querySelector('.mf2-document-preview--structured')).toBeInTheDocument();
    expect(container.textContent).toBe(value.replace('*', 'fallback'));
    expect(
      [...container.querySelectorAll('.mf2-document-preview__pattern')].map((el) => el.textContent),
    ).toEqual(['Active', 'Paused', 'Unknown']);
    expect(container.querySelector('.mf2-document-preview__structure')).toHaveTextContent(
      '.input {$status :string} .match $status active {{',
    );
    expect(
      screen.getByLabelText('MF2 fallback selector').closest('.mf2-document-preview__structure'),
    ).toBeInTheDocument();
    expect(
      container.querySelector(
        '.mf2-document-preview__pattern .visible-text-editor__protected-token',
      ),
    ).toBeNull();
  });

  it('shows all plural forms and makes only their inline count expressions blue atoms', () => {
    const value = `.input {$count :number}\n.match $count\n${['zero', 'one', 'two', 'few', 'many', 'other', '*'].map((key) => `${key} {{${key}: {$count} files}}`).join('\n')}`;
    const container = preview(value);
    expect(
      [...container.querySelectorAll('.mf2-document-preview__pattern')].map((el) => el.textContent),
    ).toEqual(
      ['zero', 'one', 'two', 'few', 'many', 'other', '*'].map((key) => `${key}: {$count} files`),
    );
    expect(screen.getAllByLabelText('MF2 variable count')).toHaveLength(7);
    for (const atom of screen.getAllByLabelText('MF2 variable count')) {
      expect(atom).toHaveTextContent('{$count}');
      expect(atom.closest('.mf2-document-preview__pattern')).toBeInTheDocument();
    }
  });

  it('keeps markup and expressions protected inside normal pattern text', () => {
    const value =
      '.input {$name :string}\n{{Tap {#link href=$url}here{/link} {#br/} {|literal|} {:function} {$name :string}.}}';
    const container = preview(value);
    expect(container.textContent).toBe(value);
    const atoms = container.querySelectorAll(
      '.mf2-document-preview__pattern .visible-text-editor__protected-token',
    );
    expect([...atoms].map((el) => el.textContent)).toEqual([
      '{#link href=$url}',
      '{/link}',
      '{#br/}',
      '{|literal|}',
      '{:function}',
      '{$name :string}',
    ]);
    expect(container.querySelector('.mf2-document-preview__pattern')).toHaveTextContent('here');
  });

  it('leaves escaped brace lookalikes and multiline fallback-like text inside the pattern body', () => {
    const value = String.raw`.input {$status :string}
.match $status
active {{Literal \{$name\} and \{#link\}.
* \{\{lookalike\}\}}}
* {{Unknown}}`;
    const container = preview(value);
    expect(container.querySelector('.mf2-document-preview__pattern')).toHaveTextContent(
      String.raw`Literal \{$name\} and \{#link\}. * \{\{lookalike\}\}`,
    );
    expect(
      container.querySelector(
        '.mf2-document-preview__pattern .visible-text-editor__protected-token',
      ),
    ).toBeNull();
    expect(screen.getAllByLabelText('MF2 fallback selector')).toHaveLength(1);
  });

  it('preserves multiple selectors, quoted keys, and every fallback key as structure', () => {
    const value = `.input {$kind :string}\n.input {$count :number}\n.match $kind $count\n|*| one {{Star}}\nfoo|bar * {{Files}}\n*\n* {{Anything}}`;
    const container = preview(value);
    expect(container.textContent).toBe(
      value.replace('foo|bar *', 'foo|bar fallback').replace('*\n*', 'fallback\nfallback'),
    );
    expect(
      [...container.querySelectorAll('.mf2-document-preview__pattern')].map((el) => el.textContent),
    ).toEqual(['Star', 'Files', 'Anything']);
    expect(screen.getAllByLabelText('MF2 fallback selector')).toHaveLength(3);
  });

  it('renders an empty target without a structural envelope or invented text', () => {
    const container = preview('');
    expect(container.textContent).toBe('');
    expect(container.querySelector('.mf2-document-preview--structured')).toBeNull();
  });
});
