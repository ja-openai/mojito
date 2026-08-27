import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { JsonPayloadModal } from './JsonPayloadModal';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('JsonPayloadModal', () => {
  it('shows request instructions as safe rich text while preserving JSON and raw views', async () => {
    const instructions = [
      'You are a professional translator.',
      '',
      '**Handling tags:**',
      '• Keep <tag> placeholders unchanged.',
    ].join('\n');
    const payload = JSON.stringify({ instructions, input: [] });
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(payload, {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    const user = userEvent.setup();

    render(
      <QueryClientProvider client={queryClient}>
        <JsonPayloadModal
          open
          items={[
            {
              key: 'request',
              label: 'Request',
              title: 'AI Translate request JSON',
              url: '/api/lineage/request',
            },
          ]}
          activeItemKey="request"
          onActiveItemKeyChange={vi.fn()}
          onClose={vi.fn()}
        />
      </QueryClientProvider>,
    );

    const dialog = screen.getByRole('dialog', { name: 'AI Translate request JSON' });
    const instructionsTab = await within(dialog).findByRole('tab', { name: 'Instructions' });
    expect(within(dialog).getByRole('tab', { name: 'JSON' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(within(dialog).getByText(/"instructions":/)).toBeInTheDocument();
    expect(within(dialog).getByRole('link', { name: 'Open raw' })).toHaveAttribute(
      'href',
      '/api/lineage/request',
    );

    await user.click(instructionsTab);

    expect(within(dialog).getByText('Handling tags:').tagName).toBe('STRONG');
    expect(within(dialog).getByText('• Keep <tag> placeholders unchanged.')).toBeInTheDocument();
    expect(dialog.querySelector('tag')).toBeNull();
    expect(within(dialog).queryByText(/"instructions":/)).not.toBeInTheDocument();

    await user.click(within(dialog).getByRole('tab', { name: 'JSON' }));

    expect(within(dialog).getByText(/"instructions":/)).toBeInTheDocument();
  });
});
