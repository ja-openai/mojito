import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { Mf2TranslationEditor, type Mf2TranslationEditorSnapshot } from './Mf2TranslationEditor';

const COUNT_SOURCE = `.input {$count :number}
{{You have {$count} files.}}`;

function ControlledEmptyTarget({ onTargetChange }: { onTargetChange: (target: string) => void }) {
  const [target, setTarget] = useState('');
  return (
    <Mf2TranslationEditor
      locale="fr"
      onTargetChange={(nextTarget) => {
        onTargetChange(nextTarget);
        setTarget(nextTarget);
      }}
      showArgumentInputs={false}
      showLocaleSelector={false}
      showPreview={false}
      showSource={false}
      source={COUNT_SOURCE}
      target={target}
    />
  );
}

describe('Mf2TranslationEditor', () => {
  it('promotes a controlled empty target into flat locale plural forms', async () => {
    const user = userEvent.setup();
    const onTargetChange = vi.fn();
    render(<ControlledEmptyTarget onTargetChange={onTargetChange} />);

    await user.click(screen.getByRole('button', { name: 'Add fr plural forms for $count' }));

    const expectedTarget = `.input {$count :number}
.match $count
one {{}}
many {{}}
other {{}}
* {{}}`;
    expect(onTargetChange).toHaveBeenCalledOnce();
    expect(onTargetChange).toHaveBeenCalledWith(expectedTarget);
    expect(await screen.findByRole('textbox', { name: 'Target count: one' })).toBeInTheDocument();
    expect(screen.getByText('count: many')).toBeInTheDocument();
    expect(screen.getByText('count: other')).toBeInTheDocument();
    expect(screen.getByText('count: fallback')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Add fr plural forms for $count' }),
    ).not.toBeInTheDocument();
  });

  it('tracks controlled target prop updates without emitting a local change', async () => {
    const onTargetChange = vi.fn();
    const { rerender } = render(
      <Mf2TranslationEditor
        onTargetChange={onTargetChange}
        showArgumentInputs={false}
        showPreview={false}
        showSource={false}
        source="Hello"
        target="Bonjour"
      />,
    );

    expect(screen.getByRole('textbox', { name: 'Target Message' })).toHaveTextContent('Bonjour');

    rerender(
      <Mf2TranslationEditor
        onTargetChange={onTargetChange}
        showArgumentInputs={false}
        showPreview={false}
        showSource={false}
        source="Hello"
        target="Salut"
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole('textbox', { name: 'Target Message' })).toHaveTextContent('Salut');
    });
    expect(onTargetChange).not.toHaveBeenCalled();
  });

  it('can hide the locale selector while retaining the controlled locale', () => {
    render(
      <Mf2TranslationEditor
        locale="ru"
        showArgumentInputs={false}
        showLocaleSelector={false}
        showPreview={false}
        showSource={false}
        source={COUNT_SOURCE}
        target=""
      />,
    );

    expect(screen.queryByLabelText('Target language')).not.toBeInTheDocument();
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Add ru plural forms for $count' }),
    ).toBeInTheDocument();
  });

  it('surfaces malformed source diagnostics without target ranges', async () => {
    let lastSnapshot: Mf2TranslationEditorSnapshot | undefined;
    render(
      <Mf2TranslationEditor
        locale="fr"
        onChange={(snapshot) => {
          lastSnapshot = snapshot;
        }}
        showArgumentInputs={false}
        showLocaleSelector={false}
        showPreview={false}
        showSource={false}
        source=".input {$count :number"
        target="Vous avez des fichiers."
      />,
    );

    expect(
      (await screen.findAllByText(/Source: Placeholder is missing a closing brace/)).length,
    ).toBeGreaterThan(0);
    await waitFor(() => {
      const sourceDiagnostic = lastSnapshot?.diagnostics.find(
        (diagnostic) => diagnostic.code === 'source-unclosed-placeholder',
      );
      expect(sourceDiagnostic).toMatchObject({
        message: 'Source: Placeholder is missing a closing brace.',
        severity: 'error',
      });
      expect(sourceDiagnostic).not.toHaveProperty('start');
      expect(sourceDiagnostic).not.toHaveProperty('end');
    });
  });

  it.each([
    ['Cmd+Enter', { metaKey: true }],
    ['Ctrl+Enter', { ctrlKey: true }],
  ])('submits from the rich editor with %s', (_shortcut, modifiers) => {
    const onSubmit = vi.fn();
    render(
      <Mf2TranslationEditor
        onSubmit={onSubmit}
        showArgumentInputs={false}
        showPreview={false}
        showSource={false}
        source="Hello"
        target="Bonjour"
      />,
    );

    fireEvent.keyDown(screen.getByRole('textbox', { name: 'Target Message' }), {
      key: 'Enter',
      ...modifiers,
    });

    expect(onSubmit).toHaveBeenCalledOnce();
  });
});
