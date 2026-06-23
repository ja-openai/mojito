import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { vi } from 'vitest';

import type { ApiUserProfile } from './api/users';
import { App } from './App';

const copyAuthoringTechnicalInternals =
  /Admin tools|CMS|Mojito|schema|mapping|variant|publish|repository|package|snapshot|JSON/i;

const mocks = vi.hoisted(() => ({
  useCurrentUser: vi.fn(),
  adminContentCmsModuleGate: Promise.resolve(),
  resolveAdminContentCmsModule: () => {},
}));

vi.mock('./hooks/useCurrentUser', () => ({
  useCurrentUser: mocks.useCurrentUser,
}));

vi.mock('./page/settings/AdminContentCmsPage', async () => {
  await mocks.adminContentCmsModuleGate;
  return {
    AdminContentCmsPage: ({ mode = 'author' }: { mode?: 'author' | 'admin' }) => (
      <div>{mode === 'admin' ? 'Admin tools route' : 'Product copy route'}</div>
    ),
  };
});

vi.mock('./page/settings/AdminStringAuthoringPage', () => ({
  AdminStringAuthoringPage: () => <div>String authoring page</div>,
}));

function setUserRole(role: ApiUserProfile['role']) {
  mocks.useCurrentUser.mockReturnValue({
    data: {
      username: role.toLowerCase(),
      role,
      canTranslateAllLocales: true,
      userLocales: [],
    },
    isLoading: false,
    isError: false,
  });
}

function getCopyAuthoringRegion() {
  const copyAuthoringRegion = screen
    .getByRole('heading', { name: 'Copy authoring' })
    .closest('section');
  if (!(copyAuthoringRegion instanceof HTMLElement)) {
    throw new Error('Expected Copy authoring settings region');
  }
  return copyAuthoringRegion;
}

function getSettingsSections() {
  return Array.from(document.querySelectorAll('.settings-page > .settings-card')).filter(
    (section): section is HTMLElement => section instanceof HTMLElement,
  );
}

function getAccessibleCopy(container: HTMLElement) {
  return Array.from(container.querySelectorAll('[aria-label], [title], [aria-describedby]'))
    .flatMap((element) => [
      element.getAttribute('aria-label'),
      element.getAttribute('title'),
      ...(element.getAttribute('aria-describedby') ?? '')
        .split(/\s+/)
        .filter(Boolean)
        .map((describedById) => document.getElementById(describedById)?.textContent),
    ])
    .filter((value): value is string => value != null);
}

describe('App', () => {
  beforeEach(() => {
    window.history.pushState({}, '', '/');
    mocks.adminContentCmsModuleGate = Promise.resolve();
    mocks.resolveAdminContentCmsModule = () => {};
    setUserRole('ROLE_ADMIN');
  });

  it('renders the shell', () => {
    render(<App />);
  });

  it('keeps the Product copy loading state author-facing', async () => {
    mocks.adminContentCmsModuleGate = new Promise<void>((resolve) => {
      mocks.resolveAdminContentCmsModule = () => resolve();
    });
    window.history.pushState({}, '', '/settings/system/content-cms');

    render(<App />);

    const loadingState = await screen.findByRole('status');
    expect(loadingState).toHaveTextContent('Loading Product copy...');
    const editor = screen.getByRole('region', { name: 'Product copy editor' });
    expect(editor).toContainElement(loadingState);
    expect(getComputedStyle(editor).display).toBe('flex');
    expect(screen.getByRole('heading', { name: 'Product copy' })).toBeVisible();
    expect(screen.getByRole('link', { name: 'Back to settings' })).toHaveAttribute(
      'href',
      '/settings/system',
    );
    expect(document.body).not.toHaveTextContent(copyAuthoringTechnicalInternals);
    expect(getAccessibleCopy(document.body)).not.toEqual(
      expect.arrayContaining([expect.stringMatching(copyAuthoringTechnicalInternals)]),
    );

    mocks.resolveAdminContentCmsModule();

    expect(await screen.findByText('Product copy route')).toBeInTheDocument();
  });

  it('loads the Product copy route', async () => {
    window.history.pushState({}, '', '/settings/system/content-cms');

    render(<App />);

    expect(await screen.findByText('Product copy route')).toBeInTheDocument();
  });

  it('shows a string authoring tab for admins and keeps settings inactive on that route', () => {
    window.history.pushState({}, '', '/string-authoring');

    render(<App />);

    const nav = screen.getByRole('navigation');
    const stringAuthoringLink = within(nav).getByRole('link', { name: 'String Authoring' });
    const settingsLink = within(nav).getByRole('link', { name: 'Settings' });
    expect(stringAuthoringLink).toHaveAttribute('href', '/string-authoring');
    expect(stringAuthoringLink).toHaveClass('is-active');
    expect(settingsLink).not.toHaveClass('is-active');
    expect(screen.getByText('String authoring page')).toBeInTheDocument();
  });

  it('hides the string authoring tab from project managers', () => {
    setUserRole('ROLE_PM');

    render(<App />);

    expect(screen.queryByRole('link', { name: 'String Authoring' })).not.toBeInTheDocument();
  });

  it('hides the string authoring tab from users without source authoring access', () => {
    setUserRole('ROLE_TRANSLATOR');

    render(<App />);

    expect(screen.queryByRole('link', { name: 'String Authoring' })).not.toBeInTheDocument();
  });

  it('loads the Product copy admin tools route', async () => {
    window.history.pushState({}, '', '/settings/system/content-cms/admin');

    render(<App />);

    expect(await screen.findByText('Admin tools route')).toBeInTheDocument();
  });

  it('keeps the Product copy settings entry author-facing', async () => {
    window.history.pushState({}, '', '/settings/system');

    render(<App />);

    expect(await screen.findByRole('heading', { name: 'Copy authoring' })).toBeVisible();
    const copyAuthoringRegion = getCopyAuthoringRegion();
    expect(getSettingsSections()[0]).toBe(copyAuthoringRegion);
    const productCopyEntry = screen.getByRole('button', { name: /Product copy/ });
    expect(productCopyEntry).toBeVisible();
    expect(productCopyEntry).toHaveTextContent(
      'Write product copy, translate it, and release approved copy.',
    );
    expect(productCopyEntry).not.toHaveTextContent('hand source text to Mojito');
    expect(copyAuthoringRegion).not.toHaveTextContent(copyAuthoringTechnicalInternals);
    expect(getAccessibleCopy(copyAuthoringRegion)).not.toEqual(
      expect.arrayContaining([expect.stringMatching(copyAuthoringTechnicalInternals)]),
    );
    expect(screen.queryByRole('button', { name: /Admin tools/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Content CMS' })).not.toBeInTheDocument();
  });

  it('opens Product copy authoring from the settings directory', async () => {
    const user = userEvent.setup();
    window.history.pushState({}, '', '/settings/system');

    render(<App />);

    await user.click(await screen.findByRole('button', { name: /Product copy/ }));

    expect(await screen.findByText('Product copy route')).toBeInTheDocument();
    expect(screen.queryByText('Admin tools route')).not.toBeInTheDocument();
  });
});
