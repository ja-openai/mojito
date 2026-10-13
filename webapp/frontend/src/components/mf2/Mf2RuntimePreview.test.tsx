import { type MF2Message, parseToModel } from '@mojito-mf2/core';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { Mf2RuntimePreview } from './Mf2RuntimePreview';

function model(source: string): MF2Message {
  const parsed = parseToModel(source);
  if (!parsed.model || parsed.hasDiagnostics) throw new Error('Invalid test fixture');
  return parsed.model;
}

describe('MF2 runtime samples', () => {
  it.each(['1.5', '-0.5', '001.50'])(
    'preserves typed numeric text %s while formatting its value',
    async (sample) => {
      const source = model('{$amount :number maximumFractionDigits=2}');
      render(<Mf2RuntimePreview source={source} target={source} locale="en" />);
      await userEvent.click(screen.getByText('Preview with sample values'));
      const input = await screen.findByLabelText('amount');
      await userEvent.clear(input);
      await userEvent.type(input, sample);
      expect(input).toHaveValue(sample);
      expect(screen.getByLabelText('Formatted message')).toHaveTextContent(
        new Intl.NumberFormat('en', { maximumFractionDigits: 2 }).format(Number(sample)),
      );
    },
  );

  it('preserves leading zeroes in unannotated text arguments', async () => {
    const source = model('Reference {$reference}');
    render(<Mf2RuntimePreview source={source} target={source} locale="en" />);
    await userEvent.click(screen.getByText('Preview with sample values'));
    const input = await screen.findByLabelText('reference');
    await userEvent.clear(input);
    await userEvent.type(input, '00123');
    expect(input).toHaveValue('00123');
    expect(screen.getByLabelText('Formatted message')).toHaveTextContent('00123');
  });

  it('formats edited date samples in UTC and reports invalid input', async () => {
    const source = model('{$date :date dateStyle=long}');
    render(<Mf2RuntimePreview source={source} target={source} locale="en" />);
    await userEvent.click(screen.getByText('Preview with sample values'));
    const input = await screen.findByLabelText('date');
    await userEvent.clear(input);
    await userEvent.type(input, '2024-01-16T12:00:00Z');
    expect(input).toHaveValue('2024-01-16T12:00:00Z');
    expect(screen.getByLabelText('Formatted message')).toHaveTextContent('January 16, 2024');
    await userEvent.clear(input);
    await userEvent.type(input, 'invalid');
    expect(screen.getByLabelText('Formatted message')).toHaveTextContent(
      'Date function requires a date operand.',
    );
  });

  it('defers formatting until opened and infers external arguments, excluding locals', async () => {
    const source = model(
      '.input {$count :number}\n.local $alias = {$count}\n.match $count\none {{One item}}\n* {{{$alias} items}}',
    );
    render(<Mf2RuntimePreview source={source} target={source} locale="en" />);
    expect(screen.queryByLabelText('Formatted message')).not.toBeInTheDocument();
    await userEvent.click(screen.getByText('Preview with sample values'));
    expect(await screen.findByLabelText('count')).toHaveValue('1');
    expect(screen.queryByLabelText('alias')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Formatted message')).toHaveTextContent('One item');
    await userEvent.clear(screen.getByLabelText('count'));
    await userEvent.type(screen.getByLabelText('count'), '4');
    expect(screen.getByLabelText('Formatted message')).toHaveTextContent('\u20684\u2069 items');
  });

  it('uses source markup bindings and inert placeholders for custom components', async () => {
    const source = model('Read {#helpLink}{#strong}help{/strong}{/helpLink} {#badge /}, {$name}.');
    const view = render(<Mf2RuntimePreview source={source} target={source} locale="en" />);
    await userEvent.click(screen.getByText('Preview with sample values'));
    expect(await screen.findByTitle('Component helpLink (preview only)')).toHaveTextContent('help');
    expect(
      screen.getByTitle('Component helpLink (preview only)').querySelector('strong'),
    ).not.toBeNull();
    expect(screen.getByTitle('Component badge (preview only)')).toHaveTextContent('[badge]');
    expect(screen.getByLabelText('name')).toHaveValue('Sample');
    expect(view.container.querySelector('a')).toBeNull();
    await userEvent.clear(screen.getByLabelText('name'));
    await userEvent.type(screen.getByLabelText('name'), '<img src=x onerror=alert(1)>');
    expect(view.container.querySelector('img')).toBeNull();
    expect(screen.getByLabelText('Formatted message')).toHaveTextContent(
      '<img src=x onerror=alert(1)>',
    );
  });

  it('reports unknown target bindings without running component code', async () => {
    render(
      <Mf2RuntimePreview
        source={model('Hello')}
        target={model('{#unexpected}Hello{/unexpected}')}
        locale="en"
      />,
    );
    await userEvent.click(screen.getByText('Preview with sample values'));
    expect(await screen.findByText('Unknown MF2 markup: unexpected')).toBeInTheDocument();
  });

  it('updates locale while preserving entered samples', async () => {
    const source = model('.input {$count :number}\n.match $count\none {{singular}}\n* {{plural}}');
    const view = render(<Mf2RuntimePreview source={source} target={source} locale="en" />);
    await userEvent.click(screen.getByText('Preview with sample values'));
    await userEvent.clear(await screen.findByLabelText('count'));
    await userEvent.type(screen.getByLabelText('count'), '0');
    expect(screen.getByLabelText('Formatted message')).toHaveTextContent('plural');
    view.rerender(<Mf2RuntimePreview source={source} target={source} locale="fr" />);
    expect(screen.getByLabelText('count')).toHaveValue('0');
    expect(screen.getByLabelText('Formatted message')).toHaveTextContent('singular');
  });
});
