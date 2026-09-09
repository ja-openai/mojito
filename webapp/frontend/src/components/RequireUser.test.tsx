import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { ApiUserPreferences } from '../api/userPreferences';
import { userPreferencesQueryKey, useUserPreferences } from '../hooks/useUserPreferences';
import { RequireUser } from './RequireUser';

const currentUser = vi.hoisted(() => ({
  username: 'alice',
  role: 'ROLE_TRANSLATOR',
  canTranslateAllLocales: true,
  userLocales: [],
}));
vi.mock('../hooks/useCurrentUser', () => ({
  useCurrentUser: () => ({ data: currentUser, isLoading: false, isError: false }),
}));

const defaults: ApiUserPreferences = {
  initialized: false,
  worksetSize: null,
  preferredLocales: [],
  shortcutHelp: null,
  visibleTextEditorEnabled: false,
  reviewProjectSearchEnabled: false,
  defaultReviewTeamIds: [],
  aiReviewProfile: 'version_b',
  aiReviewAutomaticDisabled: false,
};

function Workset() {
  const { data } = useUserPreferences();
  const [initialWorksetSize] = useState(data?.worksetSize ?? 100);
  return <output>Workset {initialWorksetSize}</output>;
}

function App({ client }: { client: QueryClient }) {
  return (
    <QueryClientProvider client={client}>
      <RequireUser>
        <Workset />
      </RequireUser>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  currentUser.username = 'alice';
});
afterEach(() => vi.unstubAllGlobals());

describe('RequireUser preferences gate', () => {
  it('waits for saved settings before mounting pages that initialize worksets', async () => {
    let resolvePreferences: (value: Response) => void = () => {};
    vi.stubGlobal(
      'fetch',
      vi.fn(
        () =>
          new Promise<Response>((resolve) => {
            resolvePreferences = resolve;
          }),
      ),
    );
    render(<App client={new QueryClient()} />);
    expect(screen.getByText('Loading your settings…')).toBeInTheDocument();
    expect(screen.queryByText(/Workset/)).not.toBeInTheDocument();

    act(() => resolvePreferences(Response.json({ ...defaults, worksetSize: 5000 })));
    expect(await screen.findByText('Workset 5000')).toBeInTheDocument();
  });

  it('shows a retryable load error without initializing pages with defaults', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(new Response('', { status: 500 }))
        .mockResolvedValueOnce(Response.json({ ...defaults, worksetSize: 1000 })),
    );
    render(<App client={new QueryClient()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load your settings.');
    expect(screen.queryByText(/Workset/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Workset 1000')).toBeInTheDocument();
  });

  it('remounts page state when switching between signed-in accounts', () => {
    const client = new QueryClient();
    client.setQueryData(userPreferencesQueryKey('alice'), { ...defaults, worksetSize: 1000 });
    client.setQueryData(userPreferencesQueryKey('bob'), { ...defaults, worksetSize: 5000 });
    const { rerender } = render(<App client={client} />);
    expect(screen.getByText('Workset 1000')).toBeInTheDocument();
    currentUser.username = 'bob';
    rerender(<App client={client} />);
    expect(screen.getByText('Workset 5000')).toBeInTheDocument();
  });
});
