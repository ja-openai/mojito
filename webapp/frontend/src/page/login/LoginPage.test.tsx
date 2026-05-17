import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { FrontendConfig } from '../../api/frontend-config';
import { resetFrontendConfigForTests } from '../../api/frontend-config';
import { LoginPage } from './LoginPage';

const baseConfig: FrontendConfig = {
  user: {
    username: '',
    role: 'ROLE_USER',
    canTranslateAllLocales: false,
    userLocales: [],
  },
  locale: 'en',
  ict: false,
  csrfToken: 'csrf-token',
  contextPath: '',
  security: {
    oAuth2: {
      github: { uiLabelText: 'GitHub' },
    },
  },
  stateless: {
    enabled: false,
  },
};

afterEach(() => {
  vi.unstubAllGlobals();
  resetFrontendConfigForTests();
});

function mockFrontendConfig(config: FrontendConfig) {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      new Response(JSON.stringify(config), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  );
}

function renderLoginPage(path = '/login') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <LoginPage />
    </MemoryRouter>,
  );
}

describe('LoginPage', () => {
  it('renders the form from frontend config', async () => {
    mockFrontendConfig(baseConfig);

    renderLoginPage('/login?showPage=workbench&logout');

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByText('You have been logged out.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'GitHub' })).toBeInTheDocument();

    const submitButton = screen.getByRole('button', { name: 'Sign in' });
    await waitFor(() => expect(submitButton).toBeEnabled());
    expect(submitButton.closest('form')).toHaveAttribute('action', '/login?showPage=workbench');
    expect(document.querySelector('input[name="_csrf"]')).toHaveAttribute('value', 'csrf-token');
  });

  it('shows stateless MSAL login when configured', async () => {
    mockFrontendConfig({
      ...baseConfig,
      security: { oAuth2: {} },
      stateless: {
        enabled: true,
        type: 'MSAL',
        msal: {
          authority: 'https://login.microsoftonline.com/example',
          clientId: 'client-id',
          scope: 'api://example/read',
        },
      },
    });

    renderLoginPage('/login');

    expect(
      await screen.findByRole('button', { name: 'Continue with Microsoft' }),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('Username')).not.toBeInTheDocument();
  });
});
