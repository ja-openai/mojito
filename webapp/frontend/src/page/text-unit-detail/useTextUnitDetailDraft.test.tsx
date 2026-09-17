import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';

import { useTextUnitDetailDraft } from './useTextUnitDetailDraft';

it('notifies shared draft readers only for their draft, not queries created during rendering', () => {
  const client = new QueryClient();
  const errors = vi.spyOn(console, 'error');
  function Editor({ name }: { name: string }) {
    const { draft, setField } = useTextUnitDetailDraft('reviewer:1:fr');
    useQuery({
      queryKey: ['editor-context', name, draft.draftTarget],
      queryFn: () => Promise.resolve(null),
      enabled: false,
    });
    return (
      <>
        <output aria-label={name}>{draft.draftTarget}</output>
        <button onClick={() => setField('draftTarget', 'Updated')}>Edit {name}</button>
      </>
    );
  }
  const view = render(
    <QueryClientProvider client={client}>
      <Editor name="first" />
      <Editor name="second" />
    </QueryClientProvider>,
  );
  try {
    fireEvent.click(screen.getByRole('button', { name: 'Edit first' }));
    expect(screen.getByLabelText('first')).toHaveTextContent('Updated');
    expect(screen.getByLabelText('second')).toHaveTextContent('Updated');
    expect(errors.mock.calls.map((call) => String(call[0])).join('\n')).not.toMatch(
      /Cannot update a component.*while rendering a different component/,
    );
  } finally {
    view.unmount();
    client.clear();
    errors.mockRestore();
  }
});
