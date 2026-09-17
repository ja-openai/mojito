import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { MAX_MF2_MESSAGE_CODE_UNITS } from '../../components/mf2/model';
import { MdxMessagePreview } from './MdxMessagePreview';

const plural =
  '.input {$count :integer}\n.match $count\none {{Une séance}}\n* {{Plusieurs séances}}';

describe('MdxMessagePreview', () => {
  it('uses the selected language plural rules and updates sample values', () => {
    const view = render(<MdxMessagePreview value={plural} args={{ count: 0 }} locale="fr" />);
    expect(screen.getByText('Une séance')).toBeInTheDocument();
    expect(screen.getByText('Sample values')).toHaveAttribute('title', 'count: 0');
    view.rerender(<MdxMessagePreview value={plural} args={{ count: 2 }} locale="fr" />);
    expect(screen.getByText('Plusieurs séances')).toBeInTheDocument();
    view.rerender(<MdxMessagePreview value={plural} args={{ count: 0 }} locale="en" />);
    expect(screen.getByText('Plusieurs séances')).toBeInTheDocument();
  });

  it('formats the date with the Intl adapter and explicit time zone', () => {
    render(
      <MdxMessagePreview
        value="Le {$date :date dateStyle=long timeZone=UTC}."
        args={{ date: '2026-09-16T12:00:00Z' }}
        locale="fr"
      />,
    );
    expect(screen.getByText('Le 16 septembre 2026.')).toBeInTheDocument();
  });

  it.each([
    { value: '{$missing}', args: {}, locale: 'fr' },
    { value: '{$broken', args: {}, locale: 'fr' },
    { value: 'hello', args: undefined, locale: 'fr' },
    { value: 'hello', args: {}, locale: undefined },
    { value: 'x'.repeat(MAX_MF2_MESSAGE_CODE_UNITS + 1), args: {}, locale: 'fr' },
  ])('keeps failed formatting visible as literal context', (props) => {
    const view = render(<MdxMessagePreview {...props} />);
    expect(screen.getByText(/Message preview unavailable/)).toBeInTheDocument();
    expect(view.container.querySelector('p')?.textContent).toBe(props.value);
  });

  it('renders interpolated markup as inert text', () => {
    const value = 'Hello {$name}';
    const name = '<img src=x onerror=alert(1)>';
    const view = render(<MdxMessagePreview value={value} args={{ name }} locale="en" />);
    expect(view.container.querySelector('p')?.textContent).toBe(`Hello ${name}`);
    expect(view.container.querySelector('img')).toBeNull();
  });
});
