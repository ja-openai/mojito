import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, NavLink, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AdminContentCmsPage } from './AdminContentCmsPage';

const mocks = vi.hoisted(() => ({
  createCmsContentType: vi.fn(),
  createCmsContentTypeField: vi.fn(),
  createCmsEntry: vi.fn(),
  createCmsPublishRequestKey: vi.fn(),
  createCmsProject: vi.fn(),
  createCmsVariant: vi.fn(),
  fetchCmsProject: vi.fn(),
  fetchCmsProjectCompleteness: vi.fn(),
  fetchCmsPublishSnapshots: vi.fn(),
  fetchCmsProjects: vi.fn(),
  isCmsConflictError: vi.fn(),
  publishCmsProject: vi.fn(),
  updateCmsContentType: vi.fn(),
  updateCmsContentTypeField: vi.fn(),
  updateCmsEntry: vi.fn(),
  updateCmsProject: vi.fn(),
  updateCmsVariant: vi.fn(),
  unmapCmsFieldMapping: vi.fn(),
  upsertCmsFieldMapping: vi.fn(),
  useRepositories: vi.fn(),
  useUser: vi.fn(),
}));

vi.mock('../../api/content-cms', () => ({
  createCmsContentType: mocks.createCmsContentType,
  createCmsContentTypeField: mocks.createCmsContentTypeField,
  createCmsEntry: mocks.createCmsEntry,
  createCmsPublishRequestKey: mocks.createCmsPublishRequestKey,
  createCmsProject: mocks.createCmsProject,
  createCmsVariant: mocks.createCmsVariant,
  fetchCmsProject: mocks.fetchCmsProject,
  fetchCmsProjectCompleteness: mocks.fetchCmsProjectCompleteness,
  fetchCmsPublishSnapshots: mocks.fetchCmsPublishSnapshots,
  fetchCmsProjects: mocks.fetchCmsProjects,
  isCmsConflictError: mocks.isCmsConflictError,
  publishCmsProject: mocks.publishCmsProject,
  updateCmsContentType: mocks.updateCmsContentType,
  updateCmsContentTypeField: mocks.updateCmsContentTypeField,
  updateCmsEntry: mocks.updateCmsEntry,
  updateCmsProject: mocks.updateCmsProject,
  updateCmsVariant: mocks.updateCmsVariant,
  unmapCmsFieldMapping: mocks.unmapCmsFieldMapping,
  upsertCmsFieldMapping: mocks.upsertCmsFieldMapping,
}));

vi.mock('../../hooks/useRepositories', () => ({
  useRepositories: mocks.useRepositories,
}));

vi.mock('../../hooks/useUser', () => ({
  useUser: mocks.useUser,
}));

const cmsAudit = {
  createdDate: null,
  lastModifiedDate: null,
  createdByUsername: 'admin',
  lastModifiedByUsername: 'admin',
};
const repository = {
  id: 701,
  name: 'Product copy',
  sourceLocale: null,
  manualScreenshotRun: null,
  repositoryLocales: [],
  repositoryStatistic: null,
  isGlossary: false,
};
const refetchRepositories = vi.fn();

const projectA = projectDetail({
  projectId: 1,
  projectKey: 'project-a',
  projectName: 'Project A',
  contentTypeId: 101,
  fieldId: 201,
  entryId: 301,
  variantId: 401,
});
const projectB = projectDetail({
  projectId: 2,
  projectKey: 'project-b',
  projectName: 'Project B',
  contentTypeId: 102,
  fieldId: 202,
  entryId: 302,
  variantId: 402,
});
const mappedProjectA = projectDetail({
  projectId: 1,
  projectKey: 'project-a',
  projectName: 'Project A',
  contentTypeId: 101,
  fieldId: 201,
  entryId: 301,
  variantId: 401,
  mappingId: 501,
});
const externallyMappedProjectA = {
  ...mappedProjectA,
  entries: mappedProjectA.entries.map((entry) => ({
    ...entry,
    variants: entry.variants.map((variant) => ({
      ...variant,
      fieldMappings: variant.fieldMappings.map((mapping) => ({
        ...mapping,
        stringId: 'product-copy.welcome.header',
      })),
    })),
  })),
};
const publishedProjectA = {
  ...projectA,
  publishSnapshots: [
    {
      id: 601,
      projectId: projectA.project.id,
      snapshotVersion: 4,
      status: 'PUBLISHED' as const,
      localeTags: ['en'],
      artifactSha256: 'a'.repeat(64),
      artifactByteSize: 512,
      snapshotSigningKeyId: 'test-v1',
      snapshotSignature: 'f'.repeat(64),
      artifactSignature: 'e'.repeat(64),
      artifactFilename: 'project-a.v4.json',
      artifactExportPath: '/delivery/project-a.v4.json',
      createdByUsername: 'admin',
      publishedAt: null,
    },
  ],
};
const candidateProjectA = {
  ...projectA,
  entries: projectA.entries.map((entry) => ({
    ...entry,
    variants: [
      ...entry.variants,
      {
        id: 411,
        entityVersion: 2,
        audit: cmsAudit,
        entryId: entry.id,
        variantKey: 'winner',
        name: 'Winner',
        candidateGroupKey: 'welcome-subject',
        status: 'CANDIDATE' as const,
        metadataJson: null,
        sortOrder: 1,
        fieldMappings: [],
      },
    ],
  })),
};
const readyProjectA = {
  ...projectA,
  entries: projectA.entries.map((entry) => ({
    ...entry,
    status: 'READY' as const,
  })),
};
const projectACompleteness = {
  projectId: projectA.project.id,
  projectKey: projectA.project.projectKey,
  authoringSha256: projectA.authoringSha256,
  publishPackageSha256: 'b'.repeat(64),
  publishPackageByteSize: 512,
  localeTags: ['en', 'fr-FR'],
  locales: [
    {
      localeTag: 'en',
      totalFields: 1,
      approvedFields: 1,
      missingFields: 0,
      reviewNeededFields: 0,
      translationNeededFields: 0,
      complete: true,
    },
    {
      localeTag: 'fr-FR',
      totalFields: 1,
      approvedFields: 1,
      missingFields: 0,
      reviewNeededFields: 0,
      translationNeededFields: 0,
      complete: true,
    },
  ],
  entries: [
    {
      entryId: projectA.entries[0].id,
      entryKey: projectA.entries[0].entryKey,
      locales: [
        {
          localeTag: 'en',
          totalFields: 1,
          approvedFields: 1,
          missingFields: 0,
          reviewNeededFields: 0,
          translationNeededFields: 0,
          complete: true,
        },
        {
          localeTag: 'fr-FR',
          totalFields: 1,
          approvedFields: 1,
          missingFields: 0,
          reviewNeededFields: 0,
          translationNeededFields: 0,
          complete: true,
        },
      ],
    },
  ],
  complete: true,
};

function projectDetail({
  projectId,
  projectKey,
  projectName,
  contentTypeId,
  fieldId,
  entryId,
  variantId,
  mappingId,
}: {
  projectId: number;
  projectKey: string;
  projectName: string;
  contentTypeId: number;
  fieldId: number;
  entryId: number;
  variantId: number;
  mappingId?: number;
}) {
  const authoringSha256 = (projectKey === 'project-b' ? 'b' : 'a').repeat(64);
  return {
    project: {
      id: projectId,
      entityVersion: 0,
      audit: cmsAudit,
      projectKey,
      name: projectName,
      description: null,
      enabled: true,
      repository: { id: projectId + 10, name: `${projectName} repository` },
      asset: { id: projectId + 20, path: `cms/${projectKey}` },
      sourceLocale: 'en',
      deliveryHint: 'BLOB_CDN',
    },
    authoringSha256,
    contentTypes: [
      {
        id: contentTypeId,
        entityVersion: 0,
        audit: cmsAudit,
        projectId,
        typeKey: 'email',
        name: `${projectName} email`,
        description: null,
        schemaVersion: 1,
        metadataSchemaJson: null,
        fields: [
          {
            id: fieldId,
            entityVersion: 0,
            audit: cmsAudit,
            contentTypeId,
            fieldKey: 'header',
            name: `${projectName} header`,
            description: null,
            fieldType: 'TEXT' as const,
            localizable: true,
            required: true,
            sortOrder: 0,
          },
        ],
      },
    ],
    entries: [
      {
        id: entryId,
        entityVersion: 0,
        audit: cmsAudit,
        projectId,
        contentTypeId,
        entryKey: 'welcome',
        name: `${projectName} welcome`,
        description: null,
        status: 'DRAFT' as const,
        metadataJson: null,
        variants: [
          {
            id: variantId,
            entityVersion: 0,
            audit: cmsAudit,
            entryId,
            variantKey: 'default',
            name: `${projectName} default`,
            candidateGroupKey: null,
            status: 'CONTROL' as const,
            metadataJson: null,
            sortOrder: 0,
            fieldMappings:
              mappingId == null
                ? []
                : [
                    {
                      id: mappingId,
                      entityVersion: 3,
                      audit: cmsAudit,
                      variantId,
                      fieldId,
                      fieldKey: 'header',
                      tmTextUnitId: mappingId + 100,
                      stringId: `cms.${projectKey}.welcome.default.header`,
                      sourceContent: 'Hello',
                      sourceComment: 'Shown in welcome email header',
                    },
                  ],
          },
        ],
      },
    ],
    publishSnapshots: [],
    hasMorePublishSnapshots: false,
    nextBeforePublishSnapshotVersion: null,
  };
}

function renderPage({
  initialEntries = ['/settings/system/content-cms'],
  initialIndex,
}: {
  initialEntries?: string[];
  initialIndex?: number;
} = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      mutations: { retry: false },
      queries: { retry: false },
    },
  });
  const router = createMemoryRouter(
    [
      {
        path: '/settings/system/content-cms',
        element: (
          <>
            <NavLink to="/repositories">App repositories</NavLink>
            <AdminContentCmsPage />
          </>
        ),
      },
      { path: '/repositories', element: <div>Repositories</div> },
      { path: '/settings/system', element: <div>Settings system</div> },
    ],
    { initialEntries, initialIndex },
  );

  return {
    ...render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    ),
    queryClient,
    router,
  };
}

function mappingForm() {
  const form = screen
    .getByRole('heading', { name: 'Map field to Mojito text unit' })
    .closest('form');
  if (form == null) {
    throw new Error('Mapping form not found');
  }
  return form;
}

function projectCreateForm() {
  const form = screen.getByRole('heading', { name: 'Create project' }).closest('form');
  if (form == null) {
    throw new Error('Project create form not found');
  }
  return form;
}

function variantForm() {
  const form = screen.getByRole('heading', { name: 'Add variant' }).closest('form');
  if (form == null) {
    throw new Error('Variant form not found');
  }
  return form;
}

function projectOverview() {
  const overview = screen.getByRole('heading', { name: 'Project overview' }).closest('section');
  if (overview == null) {
    throw new Error('Project overview not found');
  }
  return overview;
}

function variantEditForm() {
  const form = screen.getByRole('heading', { name: 'Edit variant' }).closest('form');
  if (form == null) {
    throw new Error('Variant edit form not found');
  }
  return form;
}

function fieldEditForm() {
  const form = screen.getByRole('heading', { name: 'Edit field' }).closest('form');
  if (form == null) {
    throw new Error('Field edit form not found');
  }
  return form;
}

function projectEditForm() {
  const form = screen.getByRole('heading', { name: 'Edit project' }).closest('form');
  if (form == null) {
    throw new Error('Project edit form not found');
  }
  return form;
}

function contentTypeEditForm() {
  const form = screen.getByRole('heading', { name: 'Edit content type' }).closest('form');
  if (form == null) {
    throw new Error('Content type edit form not found');
  }
  return form;
}

function fieldForm() {
  const form = screen.getByRole('heading', { name: 'Define localizable field' }).closest('form');
  if (form == null) {
    throw new Error('Field form not found');
  }
  return form;
}

function entryForm() {
  const form = screen.getByRole('heading', { name: 'Create content entry' }).closest('form');
  if (form == null) {
    throw new Error('Entry form not found');
  }
  return form;
}

function entryEditForm() {
  const form = screen.getByRole('heading', { name: 'Edit entry' }).closest('form');
  if (form == null) {
    throw new Error('Entry edit form not found');
  }
  return form;
}

function contentTypeForm() {
  const form = screen.getByRole('heading', { name: 'Create content type' }).closest('form');
  if (form == null) {
    throw new Error('Content type form not found');
  }
  return form;
}

async function validatePackage(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Validate package' }));
  expect(await screen.findByText('Package complete: Yes for en, fr-FR.')).toBeVisible();
  expect(screen.getByText('Package size: 512 B.')).toBeVisible();
}

describe('AdminContentCmsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.sessionStorage.clear();
    mocks.createCmsPublishRequestKey.mockReturnValue('publish-request');
    mocks.isCmsConflictError.mockReturnValue(false);
    mocks.useUser.mockReturnValue({
      username: 'admin',
      role: 'ROLE_ADMIN',
      canTranslateAllLocales: true,
      userLocales: [],
    });
    mocks.useRepositories.mockReturnValue({
      data: [repository],
      isLoading: false,
      isError: false,
      refetch: refetchRepositories,
    });
    mocks.fetchCmsProjects.mockResolvedValue({
      projects: [projectA.project, projectB.project],
      totalCount: 2,
    });
    mocks.fetchCmsProject.mockImplementation((projectId: number) =>
      Promise.resolve(projectId === projectB.project.id ? projectB : projectA),
    );
    mocks.fetchCmsProjectCompleteness.mockResolvedValue(projectACompleteness);
    mocks.fetchCmsPublishSnapshots.mockResolvedValue({
      snapshots: [],
      hasMore: false,
      nextBeforeSnapshotVersion: null,
    });
  });

  it('redirects non-admin direct CMS routes without loading authoring data', async () => {
    mocks.useUser.mockReturnValue({
      username: 'user',
      role: 'ROLE_USER',
      canTranslateAllLocales: false,
      userLocales: [],
    });

    renderPage();

    expect(await screen.findByText('Repositories')).toBeVisible();
    expect(mocks.useRepositories).toHaveBeenCalledWith(false);
    expect(mocks.fetchCmsProjects).not.toHaveBeenCalled();
    expect(mocks.fetchCmsProject).not.toHaveBeenCalled();
  });

  it('surfaces repository load failure before project creation', async () => {
    const user = userEvent.setup();
    mocks.useRepositories.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      refetch: refetchRepositories,
    });

    renderPage();

    expect(await screen.findByText('Failed to load repositories.')).toBeVisible();
    expect(screen.getByLabelText('Repository')).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Create project' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(refetchRepositories).toHaveBeenCalledOnce();
  });

  it('retries project rail failure in place', async () => {
    const user = userEvent.setup();
    mocks.fetchCmsProjects.mockRejectedValueOnce(new Error('failed'));

    renderPage();

    expect(await screen.findByText('Failed to load projects.')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(mocks.fetchCmsProjects).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('Project A')).toBeVisible();
  });

  it('retries selected project failure in place', async () => {
    const user = userEvent.setup();
    mocks.fetchCmsProject.mockRejectedValueOnce(new Error('failed'));

    renderPage();

    expect(await screen.findByText('Failed to load selected project.')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(mocks.fetchCmsProject).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('Project overview')).toBeVisible();
  });

  it('renders delivery hints with operator-facing labels', async () => {
    renderPage();

    await screen.findByText('Project A header');
    const overview = projectOverview();

    expect(within(overview).getByText('Blob/CDN')).toBeVisible();
    expect(within(overview).queryByText('BLOB_CDN')).not.toBeInTheDocument();
  });

  it('renders author-authored CMS text as inert text', async () => {
    const projectDescription = '<img src=x onerror=alert(1)>';
    mocks.fetchCmsProject.mockResolvedValue({
      ...projectA,
      project: { ...projectA.project, description: projectDescription },
    });

    renderPage();

    expect(await screen.findByText(projectDescription)).toBeVisible();
    expect(document.querySelector('img[src="x"]')).toBeNull();
  });

  it('clears project-scoped mapping selections when switching projects', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByText('Project A header');
    const projectAMappingForm = mappingForm();
    await user.selectOptions(within(projectAMappingForm).getByLabelText('Entry'), '301');
    await user.selectOptions(within(projectAMappingForm).getByLabelText('Variant'), '401');
    await user.selectOptions(
      within(projectAMappingForm).getByLabelText('Localizable field'),
      '201',
    );

    await user.click(screen.getByRole('button', { name: /Project B/ }));

    await waitFor(() => expect(mocks.fetchCmsProject).toHaveBeenCalledWith(projectB.project.id));
    const projectBMappingForm = mappingForm();
    await waitFor(() => {
      expect(within(projectBMappingForm).getByLabelText('Entry')).toHaveValue('');
      expect(within(projectBMappingForm).getByLabelText('Variant')).toHaveValue('');
      expect(within(projectBMappingForm).getByLabelText('Localizable field')).toHaveValue('');
    });

    await user.type(
      within(projectBMappingForm).getByLabelText('Generated source content'),
      'Hello',
    );
    await user.click(within(projectBMappingForm).getByRole('button', { name: 'Map field' }));

    expect(mocks.upsertCmsFieldMapping).not.toHaveBeenCalled();
    expect(within(projectBMappingForm).getByLabelText('Entry')).toBeInvalid();
    expect(within(projectBMappingForm).getByLabelText('Variant')).toBeDisabled();
  });

  it('keeps the selected workspace when project search filters its rail row', async () => {
    const user = userEvent.setup();
    mocks.fetchCmsProjects.mockImplementation(({ search }: { search?: string }) =>
      Promise.resolve(
        search === 'project-b'
          ? { projects: [projectB.project], totalCount: 1 }
          : { projects: [projectA.project, projectB.project], totalCount: 2 },
      ),
    );
    renderPage();

    await screen.findByText('Project A header');
    await user.type(screen.getByLabelText('Search projects'), 'project-b');

    await waitFor(() =>
      expect(mocks.fetchCmsProjects).toHaveBeenLastCalledWith({
        search: 'project-b',
        enabled: null,
        limit: 100,
      }),
    );
    expect(screen.queryByRole('button', { name: /Project A/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Project B/ })).not.toHaveClass('is-active');
    expect(screen.getAllByText('project-a')).not.toHaveLength(0);
    expect(mocks.fetchCmsProject).not.toHaveBeenCalledWith(projectB.project.id);
  });

  it('confirms before discarding project-scoped drafts during project navigation', async () => {
    const user = userEvent.setup();
    const confirm = vi
      .spyOn(window, 'confirm')
      .mockReturnValueOnce(false)
      .mockReturnValueOnce(true);
    renderPage();

    await screen.findByText('Project A header');
    const form = projectEditForm();
    const nameInput = within(form).getByLabelText('Name');
    await user.clear(nameInput);
    await user.type(nameInput, 'Unsaved project name');

    await user.click(screen.getByRole('button', { name: /Project B/ }));

    expect(confirm).toHaveBeenCalledWith(
      'Switch to Project B? Unsaved CMS drafts for project-a will be discarded.',
    );
    expect(mocks.fetchCmsProject).not.toHaveBeenCalledWith(projectB.project.id);
    expect(screen.getByRole('button', { name: /Project A/ })).toHaveAttribute(
      'aria-current',
      'true',
    );
    expect(within(projectEditForm()).getByLabelText('Name')).toHaveValue('Unsaved project name');

    await user.click(screen.getByRole('button', { name: /Project B/ }));

    await waitFor(() => expect(mocks.fetchCmsProject).toHaveBeenCalledWith(projectB.project.id));
    expect(screen.getByRole('button', { name: /Project B/ })).toHaveAttribute(
      'aria-current',
      'true',
    );
    confirm.mockRestore();
  });

  it('confirms before creating a project that would replace dirty selected-project drafts', async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    renderPage();

    await screen.findByText('Project A header');
    const projectEditName = within(projectEditForm()).getByLabelText('Name');
    await user.clear(projectEditName);
    await user.type(projectEditName, 'Unsaved project name');

    const form = projectCreateForm();
    await user.type(within(form).getByLabelText('Project key'), 'growth-email');
    await user.type(within(form).getByLabelText('Name'), 'Growth email');
    await user.selectOptions(within(form).getByLabelText('Repository'), String(repository.id));
    await user.click(within(form).getByRole('button', { name: 'Create project' }));

    expect(confirm).toHaveBeenCalledWith(
      'Create Growth email and open it? Unsaved CMS drafts for project-a will be discarded.',
    );
    expect(mocks.createCmsProject).not.toHaveBeenCalled();
    expect(within(projectEditForm()).getByLabelText('Name')).toHaveValue('Unsaved project name');
    confirm.mockRestore();
  });

  it('confirms before leaving CMS through the settings back control', async () => {
    const user = userEvent.setup();
    const confirm = vi
      .spyOn(window, 'confirm')
      .mockReturnValueOnce(false)
      .mockReturnValueOnce(true);
    renderPage();

    await screen.findByText('Project A header');
    const projectEditName = within(projectEditForm()).getByLabelText('Name');
    await user.clear(projectEditName);
    await user.type(projectEditName, 'Unsaved project name');

    const backButton = screen.getByRole('button', { name: 'Back to settings' });
    await user.click(backButton);

    expect(confirm).toHaveBeenCalledWith(
      'Leave Content CMS? Unsaved CMS drafts will be discarded.',
    );
    expect(screen.getByRole('button', { name: /Project A/ })).toHaveAttribute(
      'aria-current',
      'true',
    );

    await user.click(backButton);

    expect(await screen.findByText('Settings system')).toBeVisible();
    confirm.mockRestore();
  });

  it('blocks app link exits and native unload while CMS drafts are dirty', async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    renderPage();

    await screen.findByText('Project A header');
    const projectEditName = within(projectEditForm()).getByLabelText('Name');
    await user.clear(projectEditName);
    await user.type(projectEditName, 'Unsaved project name');

    const navEvent = new MouseEvent('click', { bubbles: true, button: 0, cancelable: true });
    act(() => {
      screen.getByRole('link', { name: 'App repositories' }).dispatchEvent(navEvent);
    });
    expect(confirm).toHaveBeenCalledWith(
      'Leave Content CMS? Unsaved CMS drafts will be discarded.',
    );
    expect(navEvent.defaultPrevented).toBe(true);
    expect(screen.getByRole('button', { name: /Project A/ })).toHaveAttribute(
      'aria-current',
      'true',
    );

    const beforeUnloadEvent = new Event('beforeunload', { cancelable: true });
    act(() => {
      window.dispatchEvent(beforeUnloadEvent);
    });
    expect(beforeUnloadEvent.defaultPrevented).toBe(true);
    confirm.mockRestore();
  });

  it('confirms before leaving dirty CMS drafts through browser history', async () => {
    const user = userEvent.setup();
    const confirm = vi
      .spyOn(window, 'confirm')
      .mockReturnValueOnce(false)
      .mockReturnValueOnce(true);
    const { router } = renderPage({
      initialEntries: ['/settings/system', '/settings/system/content-cms'],
      initialIndex: 1,
    });

    await screen.findByText('Project A header');
    const projectEditName = within(projectEditForm()).getByLabelText('Name');
    await user.clear(projectEditName);
    await user.type(projectEditName, 'Unsaved project name');

    await act(async () => {
      await router.navigate(-1);
    });

    expect(confirm).toHaveBeenCalledWith(
      'Leave Content CMS? Unsaved CMS drafts will be discarded.',
    );
    expect(screen.getByRole('button', { name: /Project A/ })).toHaveAttribute(
      'aria-current',
      'true',
    );

    await act(async () => {
      await router.navigate(-1);
    });

    expect(await screen.findByText('Settings system')).toBeVisible();
    confirm.mockRestore();
  });

  it('confirms before switching dirty variant edit drafts', async () => {
    const user = userEvent.setup();
    const confirm = vi
      .spyOn(window, 'confirm')
      .mockReturnValueOnce(false)
      .mockReturnValueOnce(true);
    mocks.fetchCmsProject.mockResolvedValue(candidateProjectA);
    renderPage();

    await screen.findByText('Project A header');
    const form = variantEditForm();
    const nameInput = within(form).getByLabelText('Name');
    await user.clear(nameInput);
    await user.type(nameInput, 'Unsaved control variant');

    await user.selectOptions(within(form).getByLabelText('Variant'), '411');

    expect(confirm).toHaveBeenCalledWith(
      'Switch edit variants? Unsaved CMS drafts for project-a will be discarded.',
    );
    expect(within(form).getByLabelText('Variant')).toHaveValue('401');
    expect(within(form).getByLabelText('Name')).toHaveValue('Unsaved control variant');

    await user.selectOptions(within(form).getByLabelText('Variant'), '411');

    await waitFor(() => expect(within(form).getByLabelText('Variant')).toHaveValue('411'));
    expect(within(form).getByLabelText('Name')).toHaveValue('Winner');
    confirm.mockRestore();
  });

  it('confirms before switching dirty mapping targets', async () => {
    const user = userEvent.setup();
    const confirm = vi
      .spyOn(window, 'confirm')
      .mockReturnValueOnce(false)
      .mockReturnValueOnce(true);
    mocks.fetchCmsProject.mockResolvedValue(candidateProjectA);
    renderPage();

    await screen.findByText('Project A header');
    const form = mappingForm();
    await user.selectOptions(within(form).getByLabelText('Entry'), '301');
    await user.selectOptions(within(form).getByLabelText('Variant'), '401');
    await user.selectOptions(within(form).getByLabelText('Localizable field'), '201');
    await user.type(within(form).getByLabelText('Generated source content'), 'Draft hello');

    await user.selectOptions(within(form).getByLabelText('Variant'), '411');

    expect(confirm).toHaveBeenCalledWith(
      'Change mapping target? Unsaved CMS drafts for project-a will be discarded.',
    );
    expect(within(form).getByLabelText('Variant')).toHaveValue('401');
    expect(within(form).getByLabelText('Generated source content')).toHaveValue('Draft hello');

    await user.selectOptions(within(form).getByLabelText('Variant'), '411');

    await waitFor(() => expect(within(form).getByLabelText('Variant')).toHaveValue('411'));
    expect(within(form).getByLabelText('Generated source content')).toHaveValue('');
    confirm.mockRestore();
  });

  it('locks project navigation while package validation is pending', async () => {
    const user = userEvent.setup();
    let resolveCompleteness: (result: typeof projectACompleteness) => void = () => {
      throw new Error('Expected pending completeness resolution');
    };
    mocks.fetchCmsProjectCompleteness.mockImplementationOnce(
      () =>
        new Promise<typeof projectACompleteness>((resolve) => {
          resolveCompleteness = resolve;
        }),
    );
    renderPage();

    await screen.findByText('Project A header');
    await user.click(screen.getByRole('button', { name: 'Validate package' }));

    const projectSearch = screen.getByLabelText('Search projects');
    const projectAButton = screen.getByRole('button', { name: /Project A/ });
    const projectBButton = screen.getByRole('button', { name: /Project B/ });
    const main = screen.getByRole('main');
    await waitFor(() => {
      expect(projectSearch).toBeDisabled();
      expect(projectBButton).toBeDisabled();
      expect(main).toHaveAttribute('aria-busy', 'true');
    });

    await user.click(projectBButton);

    expect(mocks.fetchCmsProject).not.toHaveBeenCalledWith(projectB.project.id);
    expect(projectAButton).toHaveClass('is-active');
    expect(projectAButton).toHaveAttribute('aria-current', 'true');
    expect(projectBButton).not.toHaveAttribute('aria-current');

    resolveCompleteness(projectACompleteness);

    expect(await screen.findByText('Package complete: Yes for en, fr-FR.')).toBeVisible();
    await waitFor(() => {
      expect(projectSearch).toBeEnabled();
      expect(projectBButton).toBeEnabled();
      expect(main).toHaveAttribute('aria-busy', 'false');
    });
  });

  it('locks project navigation while an authoring save is pending', async () => {
    const user = userEvent.setup();
    let resolveUpdateProject: (result: typeof projectA) => void = () => {
      throw new Error('Expected pending project update resolution');
    };
    mocks.updateCmsProject.mockImplementationOnce(
      () =>
        new Promise<typeof projectA>((resolve) => {
          resolveUpdateProject = resolve;
        }),
    );
    renderPage();

    await screen.findByText('Project A header');
    await user.click(within(projectEditForm()).getByRole('button', { name: 'Update project' }));

    const projectSearch = screen.getByLabelText('Search projects');
    const projectAButton = screen.getByRole('button', { name: /Project A/ });
    const projectBButton = screen.getByRole('button', { name: /Project B/ });
    const main = screen.getByRole('main');
    await waitFor(() => {
      expect(projectSearch).toBeDisabled();
      expect(projectBButton).toBeDisabled();
      expect(main).toHaveAttribute('aria-busy', 'true');
    });

    await user.click(projectBButton);

    expect(mocks.fetchCmsProject).not.toHaveBeenCalledWith(projectB.project.id);
    expect(projectAButton).toHaveClass('is-active');
    expect(projectAButton).toHaveAttribute('aria-current', 'true');
    expect(projectBButton).not.toHaveAttribute('aria-current');

    resolveUpdateProject(projectA);

    expect(await screen.findByText('Updated content project Project A.')).toBeVisible();
    await waitFor(() => {
      expect(projectSearch).toBeEnabled();
      expect(projectBButton).toBeEnabled();
      expect(main).toHaveAttribute('aria-busy', 'false');
    });
  });

  it('shows when the project search result is truncated', async () => {
    mocks.fetchCmsProjects.mockResolvedValueOnce({
      projects: [projectA.project, projectB.project],
      totalCount: 101,
    });

    renderPage();

    expect(
      await screen.findByText('Showing 2 of 101 projects. Refine search to find more.'),
    ).toBeVisible();
  });

  it('uses the server-returned snapshot export path', async () => {
    mocks.fetchCmsProject.mockResolvedValue(publishedProjectA);
    renderPage();

    expect(await screen.findByRole('link', { name: 'JSON' })).toHaveAttribute(
      'href',
      '/delivery/project-a.v4.json',
    );
    expect(screen.getByRole('columnheader', { name: 'Artifact signature' })).toBeVisible();
    expect(screen.getByTitle('e'.repeat(64))).toHaveTextContent('eeeeeeeeeeee');
    expect(screen.getByRole('columnheader', { name: 'Published' })).toBeVisible();
  });

  it('shows the supported metadata schema shape when creating a content type', async () => {
    renderPage();

    await screen.findByText('Project A header');
    expect(within(contentTypeForm()).getByLabelText('Metadata schema JSON')).toHaveAttribute(
      'placeholder',
      '{"type":"object","properties":{"owner":{"type":"string"}}}',
    );
  });

  it('validates the project package with the same locale scope used by publish', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByText('Project A header');
    await user.type(screen.getByLabelText('Locale tags'), 'fr-FR');
    await user.click(screen.getByRole('button', { name: 'Validate package' }));

    await waitFor(() =>
      expect(mocks.fetchCmsProjectCompleteness).toHaveBeenCalledWith(projectA.project.id, 'fr-FR'),
    );
    expect(await screen.findByText('Package complete: Yes for en, fr-FR.')).toBeVisible();
    expect(screen.getByRole('columnheader', { name: 'Scope' })).toBeVisible();
  });

  it('refreshes stale project detail when completeness sees a newer authoring revision', async () => {
    const user = userEvent.setup();
    mocks.fetchCmsProjectCompleteness.mockResolvedValue({
      ...projectACompleteness,
      authoringSha256: 'c'.repeat(64),
    });
    renderPage();

    await screen.findByText('Project A header');
    await user.click(screen.getByRole('button', { name: 'Validate package' }));

    await screen.findByText(
      'Content changed since it was loaded. Refreshing current CMS data; review and validate again.',
    );
    await waitFor(() => expect(mocks.fetchCmsProject).toHaveBeenCalledTimes(2));
    expect(screen.queryByText('Package complete: Yes for en, fr-FR.')).not.toBeInTheDocument();
  });

  it('clears stale package completeness when refreshed detail has a newer authoring revision', async () => {
    const user = userEvent.setup();
    const page = renderPage();

    await screen.findByText('Project A header');
    await validatePackage(user);

    mocks.fetchCmsProject.mockResolvedValue({
      ...projectA,
      project: { ...projectA.project, entityVersion: 1 },
      authoringSha256: 'c'.repeat(64),
    });
    await act(async () => {
      await page.queryClient.invalidateQueries({
        queryKey: ['content-cms', 'project', projectA.project.id],
      });
    });

    await screen.findByText('Content changed since it was validated. Validate package again.');
    await waitFor(() => expect(mocks.fetchCmsProject).toHaveBeenCalledTimes(2));
    expect(screen.queryByText('Package complete: Yes for en, fr-FR.')).not.toBeInTheDocument();
  });

  it('preserves unsaved selected-project drafts when project detail refreshes', async () => {
    const user = userEvent.setup();
    mocks.fetchCmsProject.mockResolvedValue(mappedProjectA);
    mocks.updateCmsProject.mockResolvedValue(mappedProjectA);
    mocks.upsertCmsFieldMapping.mockResolvedValue(mappedProjectA);
    const page = renderPage();

    await screen.findByText('Project A header');
    const projectNameInput = within(projectEditForm()).getByLabelText('Name');
    await user.clear(projectNameInput);
    await user.type(projectNameInput, 'Unsaved project name');

    const form = mappingForm();
    await user.selectOptions(within(form).getByLabelText('Entry'), '301');
    await user.selectOptions(within(form).getByLabelText('Variant'), '401');
    await user.selectOptions(within(form).getByLabelText('Localizable field'), '201');
    const sourceContentInput = within(form).getByLabelText('Generated source content');
    await waitFor(() => expect(sourceContentInput).toHaveValue('Hello'));
    await user.clear(sourceContentInput);
    await user.type(sourceContentInput, 'Unsaved hello');

    const refreshedMappedProjectA = projectDetail({
      projectId: 1,
      projectKey: 'project-a',
      projectName: 'Project A',
      contentTypeId: 101,
      fieldId: 201,
      entryId: 301,
      variantId: 401,
      mappingId: 501,
    });
    mocks.fetchCmsProject.mockResolvedValue({
      ...refreshedMappedProjectA,
      project: { ...refreshedMappedProjectA.project, entityVersion: 7 },
      entries: refreshedMappedProjectA.entries.map((entry) => ({
        ...entry,
        variants: entry.variants.map((variant) => ({
          ...variant,
          fieldMappings: variant.fieldMappings.map((mapping) => ({
            ...mapping,
            entityVersion: 4,
          })),
        })),
      })),
    });
    await act(async () => {
      await page.queryClient.invalidateQueries({
        queryKey: ['content-cms', 'project', projectA.project.id],
      });
    });

    await waitFor(() => expect(mocks.fetchCmsProject).toHaveBeenCalledTimes(2));
    expect(within(projectEditForm()).getByLabelText('Name')).toHaveValue('Unsaved project name');
    expect(within(mappingForm()).getByLabelText('Generated source content')).toHaveValue(
      'Unsaved hello',
    );

    await user.click(within(projectEditForm()).getByRole('button', { name: 'Update project' }));
    await waitFor(() =>
      expect(mocks.updateCmsProject).toHaveBeenCalledWith(1, {
        deliveryHint: 'BLOB_CDN',
        description: null,
        enabled: true,
        expectedVersion: 0,
        name: 'Unsaved project name',
      }),
    );

    await user.click(within(mappingForm()).getByRole('button', { name: 'Save mapping' }));
    await waitFor(() =>
      expect(mocks.upsertCmsFieldMapping).toHaveBeenCalledWith(401, {
        expectedVersion: 3,
        fieldId: 201,
        sourceComment: 'Shown in welcome email header',
        sourceContent: 'Unsaved hello',
        stringId: null,
        tmTextUnitId: null,
      }),
    );
  });

  it('rehydrates only the conflicting selected-project draft after save conflict', async () => {
    const user = userEvent.setup();
    mocks.fetchCmsProject.mockResolvedValue(mappedProjectA);
    mocks.updateCmsProject.mockRejectedValueOnce(new Error('Content write conflicts'));
    mocks.isCmsConflictError.mockReturnValueOnce(true);
    renderPage();

    await screen.findByText('Project A header');
    const projectNameInput = within(projectEditForm()).getByLabelText('Name');
    await user.clear(projectNameInput);
    await user.type(projectNameInput, 'Unsaved project name');

    const form = mappingForm();
    await user.selectOptions(within(form).getByLabelText('Entry'), '301');
    await user.selectOptions(within(form).getByLabelText('Variant'), '401');
    await user.selectOptions(within(form).getByLabelText('Localizable field'), '201');
    const sourceContentInput = within(form).getByLabelText('Generated source content');
    await waitFor(() => expect(sourceContentInput).toHaveValue('Hello'));
    await user.clear(sourceContentInput);
    await user.type(sourceContentInput, 'Unsaved hello');

    mocks.fetchCmsProject.mockResolvedValue({
      ...mappedProjectA,
      project: { ...mappedProjectA.project, name: 'Server Project A', entityVersion: 1 },
    });
    await user.click(within(projectEditForm()).getByRole('button', { name: 'Update project' }));

    await screen.findByText(
      'Content changed since it was loaded. Refreshing current CMS data; review and save again.',
    );
    await waitFor(() => expect(mocks.fetchCmsProject).toHaveBeenCalledTimes(2));
    expect(within(projectEditForm()).getByLabelText('Name')).toHaveValue('Server Project A');
    expect(within(mappingForm()).getByLabelText('Generated source content')).toHaveValue(
      'Unsaved hello',
    );
  });

  it('clears stale package completeness when the publish locale scope changes', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByText('Project A header');
    const localeTagsInput = screen.getByLabelText('Locale tags');
    await user.type(localeTagsInput, 'fr-FR');
    await user.click(screen.getByRole('button', { name: 'Validate package' }));

    expect(await screen.findByText('Package complete: Yes for en, fr-FR.')).toBeVisible();

    await user.clear(localeTagsInput);
    await user.type(localeTagsInput, 'ja-JP');

    expect(screen.queryByText('Package complete: Yes for en, fr-FR.')).not.toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: 'Scope' })).not.toBeInTheDocument();
  });

  it('clears stale package completeness when revalidation fails', async () => {
    const user = userEvent.setup();
    let rejectRevalidation: (reason?: unknown) => void = () => {
      throw new Error('Expected pending completeness rejection');
    };
    mocks.fetchCmsProjectCompleteness
      .mockResolvedValueOnce(projectACompleteness)
      .mockImplementationOnce(
        () =>
          new Promise((_, reject) => {
            rejectRevalidation = reject;
          }),
      );
    renderPage();

    await screen.findByText('Project A header');
    await user.click(screen.getByRole('button', { name: 'Validate package' }));

    expect(await screen.findByText('Package complete: Yes for en, fr-FR.')).toBeVisible();

    await user.click(screen.getByRole('button', { name: 'Validate package' }));

    expect(screen.queryByText('Package complete: Yes for en, fr-FR.')).not.toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: 'Scope' })).not.toBeInTheDocument();

    rejectRevalidation(new Error('Completeness validation failed'));

    expect(await screen.findByText('Completeness validation failed')).toBeVisible();
    expect(screen.queryByText('Package complete: Yes for en, fr-FR.')).not.toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: 'Scope' })).not.toBeInTheDocument();
  });

  it('marks synchronous authoring requirements for native validation', async () => {
    renderPage();

    await screen.findByText('Project A header');
    expect(within(projectCreateForm()).getByLabelText('Project key')).toBeRequired();
    expect(within(projectCreateForm()).getByLabelText('Name')).toBeRequired();
    expect(within(projectCreateForm()).getByLabelText('Repository')).toBeRequired();
    expect(within(projectEditForm()).getByLabelText('Name')).toBeRequired();
    expect(within(contentTypeForm()).getByLabelText('Type key')).toBeRequired();
    expect(within(contentTypeForm()).getByLabelText('Name')).toBeRequired();
    expect(within(contentTypeEditForm()).getByLabelText('Content type')).toBeRequired();
    expect(within(contentTypeEditForm()).getByLabelText('Name')).toBeRequired();
    expect(within(fieldForm()).getByLabelText('Content type')).toBeRequired();
    expect(within(fieldForm()).getByLabelText('Field key')).toBeRequired();
    expect(within(fieldForm()).getByLabelText('Name')).toBeRequired();
    expect(within(fieldEditForm()).getByLabelText('Field')).toBeRequired();
    expect(within(fieldEditForm()).getByLabelText('Name')).toBeRequired();
    expect(within(entryForm()).getByLabelText('Content type')).toBeRequired();
    expect(within(entryForm()).getByLabelText('Entry key')).toBeRequired();
    expect(within(entryForm()).getByLabelText('Name')).toBeRequired();
    expect(within(entryEditForm()).getByLabelText('Entry')).toBeRequired();
    expect(within(entryEditForm()).getByLabelText('Name')).toBeRequired();
    expect(within(variantForm()).getByLabelText('Entry')).toBeRequired();
    expect(within(variantForm()).getByLabelText('Variant key')).toBeRequired();
    expect(within(variantForm()).getByLabelText('Name')).toBeRequired();
    expect(within(variantEditForm()).getByLabelText('Variant')).toBeRequired();
    expect(within(variantEditForm()).getByLabelText('Name')).toBeRequired();
    expect(within(mappingForm()).getByLabelText('Entry')).toBeRequired();
    expect(within(mappingForm()).getByLabelText('Variant')).toBeRequired();
    expect(within(mappingForm()).getByLabelText('Localizable field')).toBeRequired();
    expect(within(mappingForm()).getByLabelText('Generated source content')).toBeRequired();
    expect(within(mappingForm()).getByLabelText('Generated translator context')).toBeRequired();
  });

  it('requires translator context before generating a Mojito string ID', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByText('Project A header');
    const form = mappingForm();
    await user.selectOptions(within(form).getByLabelText('Entry'), '301');
    await user.selectOptions(within(form).getByLabelText('Variant'), '401');
    await user.selectOptions(within(form).getByLabelText('Localizable field'), '201');
    await user.type(within(form).getByLabelText('Generated source content'), 'Hello');
    await user.click(within(form).getByRole('button', { name: 'Map field' }));

    expect(mocks.upsertCmsFieldMapping).not.toHaveBeenCalled();
    expect(within(form).getByLabelText('Generated translator context')).toBeInvalid();
  });

  it('maps an existing Mojito string ID without generated-copy fields', async () => {
    const user = userEvent.setup();
    mocks.upsertCmsFieldMapping.mockResolvedValue(projectA);
    renderPage();

    await screen.findByText('Project A header');
    const form = mappingForm();
    await user.selectOptions(within(form).getByLabelText('Entry'), '301');
    await user.selectOptions(within(form).getByLabelText('Variant'), '401');
    await user.selectOptions(within(form).getByLabelText('Localizable field'), '201');
    await user.selectOptions(within(form).getByLabelText('Mapping source'), 'STRING_ID');
    expect(within(form).getByLabelText('Generated source content')).toBeDisabled();
    expect(within(form).getByLabelText('Generated translator context')).toBeDisabled();
    await user.type(
      within(form).getByLabelText('Existing Mojito string ID'),
      'cms.project-a.welcome.default.header',
    );
    await user.click(within(form).getByRole('button', { name: 'Map field' }));

    await waitFor(() =>
      expect(mocks.upsertCmsFieldMapping).toHaveBeenCalledWith(401, {
        expectedVersion: null,
        fieldId: 201,
        sourceComment: null,
        sourceContent: null,
        stringId: 'cms.project-a.welcome.default.header',
        tmTextUnitId: null,
      }),
    );
  });

  it('preserves generated source copy whitespace', async () => {
    const user = userEvent.setup();
    mocks.upsertCmsFieldMapping.mockResolvedValue(projectA);
    renderPage();

    await screen.findByText('Project A header');
    const form = mappingForm();
    await user.selectOptions(within(form).getByLabelText('Entry'), '301');
    await user.selectOptions(within(form).getByLabelText('Variant'), '401');
    await user.selectOptions(within(form).getByLabelText('Localizable field'), '201');
    await user.type(within(form).getByLabelText('Generated source content'), '  Hello  ');
    await user.type(
      within(form).getByLabelText('Generated translator context'),
      'Shown in email header',
    );
    await user.click(within(form).getByRole('button', { name: 'Map field' }));

    await waitFor(() =>
      expect(mocks.upsertCmsFieldMapping).toHaveBeenCalledWith(401, {
        expectedVersion: null,
        fieldId: 201,
        sourceComment: 'Shown in email header',
        sourceContent: '  Hello  ',
        stringId: null,
        tmTextUnitId: null,
      }),
    );
  });

  it('marks candidate groups required for live candidate variants', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByText('Project A header');
    const form = variantForm();
    expect(within(form).getByLabelText('Candidate group (required)')).toHaveAttribute('required');

    await user.selectOptions(within(form).getByLabelText('Status'), 'CONTROL');

    expect(within(form).getByLabelText('Candidate group')).not.toHaveAttribute('required');
  });

  it('does not offer ready status before a new entry has mappings', async () => {
    renderPage();

    await screen.findByText('Project A header');
    expect(within(entryForm()).queryByRole('option', { name: 'Ready' })).not.toBeInTheDocument();
  });

  it('confirms candidate promotion before archiving the current control', async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    mocks.fetchCmsProject.mockResolvedValue(candidateProjectA);
    mocks.updateCmsVariant.mockResolvedValue(candidateProjectA);
    renderPage();

    await screen.findByText('Project A header');
    const form = variantEditForm();
    await user.selectOptions(within(form).getByLabelText('Variant'), '411');
    await waitFor(() => expect(within(form).getByLabelText('Status')).toHaveValue('CANDIDATE'));
    await user.selectOptions(within(form).getByLabelText('Status'), 'CONTROL');
    await user.click(within(form).getByRole('button', { name: 'Update variant' }));

    expect(confirm).toHaveBeenCalledWith(
      'Promote Winner to control? This archives the current control variant for rollback.',
    );
    expect(mocks.updateCmsVariant).not.toHaveBeenCalled();

    confirm.mockReturnValue(true);
    await user.click(within(form).getByRole('button', { name: 'Update variant' }));

    await waitFor(() =>
      expect(mocks.updateCmsVariant).toHaveBeenCalledWith(411, {
        candidateGroupKey: 'welcome-subject',
        expectedVersion: 2,
        metadataJson: null,
        name: 'Winner',
        sortOrder: 1,
        status: 'CONTROL',
      }),
    );
    confirm.mockRestore();
  });

  it('confirms disabling a project before hiding latest delivery discovery', async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    mocks.updateCmsProject.mockResolvedValue(projectA);
    renderPage();

    await screen.findByText('Project A header');
    const form = projectEditForm();
    await user.click(within(form).getByLabelText('Enabled'));
    await user.click(within(form).getByRole('button', { name: 'Update project' }));

    expect(confirm).toHaveBeenCalledWith(
      'Disable Project A? New publish and latest delivery discovery stop; exact snapshot exports remain available for rollback.',
    );
    expect(mocks.updateCmsProject).not.toHaveBeenCalled();

    confirm.mockReturnValue(true);
    await user.click(within(form).getByRole('button', { name: 'Update project' }));

    await waitFor(() =>
      expect(mocks.updateCmsProject).toHaveBeenCalledWith(1, {
        deliveryHint: 'BLOB_CDN',
        description: null,
        enabled: false,
        expectedVersion: 0,
        name: 'Project A',
      }),
    );
    confirm.mockRestore();
  });

  it('renders the server-returned project detail before invalidated refetch completes', async () => {
    const user = userEvent.setup();
    const serverProject = {
      ...projectA,
      project: { ...projectA.project, name: 'Server Project Z', entityVersion: 1 },
    };
    mocks.fetchCmsProjects
      .mockResolvedValueOnce({
        projects: [projectA.project, projectB.project],
        totalCount: 2,
      })
      .mockImplementation(() => new Promise(() => {}));
    mocks.fetchCmsProject
      .mockResolvedValueOnce(projectA)
      .mockImplementation(() => new Promise(() => {}));
    mocks.updateCmsProject.mockResolvedValue(serverProject);
    renderPage();

    await screen.findByText('Project A header');
    const form = projectEditForm();
    const nameInput = within(form).getByLabelText('Name');
    await user.clear(nameInput);
    await user.type(nameInput, 'Client Project A');
    await user.click(within(form).getByRole('button', { name: 'Update project' }));

    expect(await screen.findByText('Updated content project Server Project Z.')).toBeVisible();
    expect(screen.getByText('Server Project Z')).toBeVisible();
    expect(
      Array.from(
        document.querySelectorAll('.content-cms-admin-page__project-row-name'),
        (row) => row.textContent,
      ),
    ).toEqual(['Project B', 'Server Project Z']);
    expect(within(projectEditForm()).getByLabelText('Name')).toHaveValue('Server Project Z');
  });

  it('removes a renamed project from a stale matching project search', async () => {
    const user = userEvent.setup();
    let holdProjectSearchRefetch = false;
    const serverProject = {
      ...projectA,
      project: { ...projectA.project, name: 'Server Project B', entityVersion: 1 },
    };
    mocks.fetchCmsProjects.mockImplementation(({ search }: { search?: string }) => {
      if (search === 'Project A') {
        return holdProjectSearchRefetch
          ? new Promise(() => {})
          : Promise.resolve({ projects: [projectA.project], totalCount: 1 });
      }
      return Promise.resolve({ projects: [projectA.project, projectB.project], totalCount: 2 });
    });
    mocks.fetchCmsProject
      .mockResolvedValueOnce(projectA)
      .mockImplementation(() => new Promise(() => {}));
    mocks.updateCmsProject.mockResolvedValue(serverProject);
    renderPage();

    await screen.findByText('Project A header');
    await user.type(screen.getByLabelText('Search projects'), 'Project A');
    await waitFor(() =>
      expect(mocks.fetchCmsProjects).toHaveBeenLastCalledWith({
        search: 'Project A',
        enabled: null,
        limit: 100,
      }),
    );
    expect(screen.getByRole('button', { name: /Project A/ })).toBeVisible();

    holdProjectSearchRefetch = true;
    const form = projectEditForm();
    const nameInput = within(form).getByLabelText('Name');
    await user.clear(nameInput);
    await user.type(nameInput, 'Client Project B');
    await user.click(within(form).getByRole('button', { name: 'Update project' }));

    expect(await screen.findByText('Updated content project Server Project B.')).toBeVisible();
    expect(screen.getByText('No matching content projects.')).toBeVisible();
    expect(screen.queryByRole('button', { name: /Server Project B/ })).not.toBeInTheDocument();
    expect(within(projectEditForm()).getByLabelText('Name')).toHaveValue('Server Project B');
  });

  it('adds a created project to a stale matching project search', async () => {
    const user = userEvent.setup();
    let holdProjectSearchRefetch = false;
    const growthProject = projectDetail({
      projectId: 3,
      projectKey: 'growth-email',
      projectName: 'Growth email copy',
      contentTypeId: 103,
      fieldId: 203,
      entryId: 303,
      variantId: 403,
    });
    mocks.fetchCmsProjects.mockImplementation(({ search }: { search?: string }) => {
      if (search === 'growth') {
        return holdProjectSearchRefetch
          ? new Promise(() => {})
          : Promise.resolve({ projects: [], totalCount: 0 });
      }
      return Promise.resolve({ projects: [projectA.project, projectB.project], totalCount: 2 });
    });
    mocks.fetchCmsProject
      .mockResolvedValueOnce(projectA)
      .mockImplementation(() => new Promise(() => {}));
    mocks.createCmsProject.mockResolvedValue(growthProject);
    renderPage();

    await screen.findByText('Project A header');
    await user.type(screen.getByLabelText('Search projects'), 'growth');
    expect(await screen.findByText('No matching content projects.')).toBeVisible();

    const form = screen.getByRole('heading', { name: 'Create project' }).closest('form');
    if (form == null) {
      throw new Error('Project create form not found');
    }
    await user.type(within(form).getByLabelText('Project key'), 'growth-email');
    await user.type(within(form).getByLabelText('Name'), 'Growth email copy');
    await user.selectOptions(within(form).getByLabelText('Repository'), String(repository.id));
    holdProjectSearchRefetch = true;
    await user.click(within(form).getByRole('button', { name: 'Create project' }));

    expect(await screen.findByText('Created content project Growth email copy.')).toBeVisible();
    expect(screen.getByRole('button', { name: /Growth email copy/ })).toHaveClass('is-active');
    expect(screen.getAllByText('Growth email copy header')).not.toHaveLength(0);
  });

  it('rejects blank edit sort order before sending a null patch field', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByText('Project A header');
    const form = fieldEditForm();
    const sortOrder = within(form).getByLabelText('Sort order');
    expect(sortOrder).toBeRequired();
    await user.clear(sortOrder);
    form.noValidate = true;
    await user.click(within(form).getByRole('button', { name: 'Update field' }));

    expect(await screen.findByText('Field sort order is required.')).toBeVisible();
    expect(mocks.updateCmsContentTypeField).not.toHaveBeenCalled();
  });

  it('confirms removing a ready entry from the next package', async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    mocks.fetchCmsProject.mockResolvedValue(readyProjectA);
    mocks.updateCmsEntry.mockResolvedValue(readyProjectA);
    renderPage();

    await screen.findByText('Project A header');
    const form = entryEditForm();
    await user.selectOptions(within(form).getByLabelText('Status'), 'ARCHIVED');
    await user.click(within(form).getByRole('button', { name: 'Update entry' }));

    expect(confirm).toHaveBeenCalledWith(
      'Move Project A welcome from READY to ARCHIVED? It will be excluded from the next published package.',
    );
    expect(mocks.updateCmsEntry).not.toHaveBeenCalled();

    confirm.mockReturnValue(true);
    await user.click(within(form).getByRole('button', { name: 'Update entry' }));

    await waitFor(() =>
      expect(mocks.updateCmsEntry).toHaveBeenCalledWith(301, {
        description: null,
        expectedVersion: 0,
        metadataJson: null,
        name: 'Project A welcome',
        status: 'ARCHIVED',
      }),
    );
    confirm.mockRestore();
  });

  it('confirms archiving an active candidate before removing it from the next package', async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    mocks.fetchCmsProject.mockResolvedValue(candidateProjectA);
    mocks.updateCmsVariant.mockResolvedValue(candidateProjectA);
    renderPage();

    await screen.findByText('Project A header');
    const form = variantEditForm();
    await user.selectOptions(within(form).getByLabelText('Variant'), '411');
    await waitFor(() => expect(within(form).getByLabelText('Status')).toHaveValue('CANDIDATE'));
    await user.selectOptions(within(form).getByLabelText('Status'), 'ARCHIVED');
    await user.click(within(form).getByRole('button', { name: 'Update variant' }));

    expect(confirm).toHaveBeenCalledWith(
      'Archive Winner? It will be excluded from the next published package.',
    );
    expect(mocks.updateCmsVariant).not.toHaveBeenCalled();

    confirm.mockReturnValue(true);
    await user.click(within(form).getByRole('button', { name: 'Update variant' }));

    await waitFor(() =>
      expect(mocks.updateCmsVariant).toHaveBeenCalledWith(411, {
        candidateGroupKey: 'welcome-subject',
        expectedVersion: 2,
        metadataJson: null,
        name: 'Winner',
        sortOrder: 1,
        status: 'ARCHIVED',
      }),
    );
    confirm.mockRestore();
  });

  it('preserves blank explicit publish locale tokens for server validation', async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    mocks.publishCmsProject.mockResolvedValue(publishedProjectA.publishSnapshots[0]);
    renderPage();

    await screen.findByText('Project A header');
    await user.type(screen.getByLabelText('Locale tags'), 'fr-FR,,ja-JP');
    await validatePackage(user);
    await user.click(screen.getByRole('button', { name: 'Publish JSON' }));

    await waitFor(() =>
      expect(mocks.publishCmsProject).toHaveBeenCalledWith(
        projectA.project.id,
        ['fr-FR', '', 'ja-JP'],
        projectA.authoringSha256,
        projectACompleteness.publishPackageSha256,
        'publish-request',
      ),
    );
    confirm.mockRestore();
  });

  it('uses the server cursor to load older retained snapshot metadata on demand', async () => {
    const user = userEvent.setup();
    const pagedProject = {
      ...publishedProjectA,
      hasMorePublishSnapshots: true,
      nextBeforePublishSnapshotVersion: 3,
    };
    const olderSnapshot = {
      ...publishedProjectA.publishSnapshots[0],
      id: 600,
      snapshotVersion: 2,
      artifactFilename: 'project-a.v2.json',
      artifactExportPath: '/delivery/project-a.v2.json',
    };
    mocks.fetchCmsProject.mockResolvedValue(pagedProject);
    mocks.fetchCmsPublishSnapshots.mockResolvedValue({
      snapshots: [olderSnapshot],
      hasMore: false,
      nextBeforeSnapshotVersion: null,
    });
    renderPage();

    await screen.findByText('v4');
    await user.click(screen.getByRole('button', { name: 'Load older snapshots' }));

    await waitFor(() =>
      expect(mocks.fetchCmsPublishSnapshots).toHaveBeenCalledWith(projectA.project.id, {
        beforeVersion: 3,
        limit: 10,
      }),
    );
    expect(await screen.findByText('v2')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Load older snapshots' })).not.toBeInTheDocument();
  });

  it('requires a current package validation before publish', async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    renderPage();

    await screen.findByText('Project A header');
    await user.click(screen.getByRole('button', { name: 'Publish JSON' }));

    expect(await screen.findByText('Validate package before publishing.')).toBeVisible();
    expect(screen.getByRole('alert')).toHaveTextContent('Validate package before publishing.');
    expect(confirm).not.toHaveBeenCalled();
    expect(mocks.publishCmsProject).not.toHaveBeenCalled();
    confirm.mockRestore();
  });

  it('announces successful package validation as status', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByText('Project A header');
    await user.click(screen.getByRole('button', { name: 'Validate package' }));

    expect(await screen.findByRole('status')).toHaveTextContent(
      'Validated publish package for project-a.',
    );
  });

  it('does not publish an incomplete validated package', async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    mocks.fetchCmsProjectCompleteness.mockResolvedValue({
      ...projectACompleteness,
      locales: projectACompleteness.locales.map((locale) =>
        locale.localeTag === 'fr-FR'
          ? {
              ...locale,
              approvedFields: 0,
              translationNeededFields: 1,
              complete: false,
            }
          : locale,
      ),
      complete: false,
    });
    renderPage();

    await screen.findByText('Project A header');
    await user.click(screen.getByRole('button', { name: 'Validate package' }));
    expect(await screen.findByText('Package complete: No for en, fr-FR.')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Publish JSON' }));

    expect(
      await screen.findByText('Cannot publish until the validated package is complete.'),
    ).toBeVisible();
    expect(confirm).not.toHaveBeenCalled();
    expect(mocks.publishCmsProject).not.toHaveBeenCalled();
    confirm.mockRestore();
  });

  it('does not reuse a publish request key after duplicate locale tokens are corrected', async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    mocks.createCmsPublishRequestKey
      .mockReturnValueOnce('publish-request')
      .mockReturnValueOnce('publish-request-2');
    mocks.publishCmsProject
      .mockRejectedValueOnce(new Error('Locale tags must not contain duplicate values: fr-FR'))
      .mockResolvedValue(publishedProjectA.publishSnapshots[0]);
    renderPage();

    await screen.findByText('Project A header');
    const localeTagsInput = screen.getByLabelText('Locale tags');
    await user.type(localeTagsInput, 'fr-FR,fr-FR');
    await validatePackage(user);
    await user.click(screen.getByRole('button', { name: 'Publish JSON' }));
    await screen.findByText('Locale tags must not contain duplicate values: fr-FR');

    await user.clear(localeTagsInput);
    await user.type(localeTagsInput, 'fr-FR');
    await validatePackage(user);
    await user.click(screen.getByRole('button', { name: 'Publish JSON' }));

    await waitFor(() => expect(mocks.publishCmsProject).toHaveBeenCalledTimes(2));
    expect(mocks.publishCmsProject).toHaveBeenNthCalledWith(
      1,
      projectA.project.id,
      ['fr-FR', 'fr-FR'],
      projectA.authoringSha256,
      projectACompleteness.publishPackageSha256,
      'publish-request',
    );
    expect(mocks.publishCmsProject).toHaveBeenNthCalledWith(
      2,
      projectA.project.id,
      ['fr-FR'],
      projectA.authoringSha256,
      projectACompleteness.publishPackageSha256,
      'publish-request-2',
    );
    expect(mocks.createCmsPublishRequestKey).toHaveBeenCalledTimes(2);
    confirm.mockRestore();
  });

  it('confirms immutable publish scope before creating a snapshot', async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    mocks.publishCmsProject.mockResolvedValue(publishedProjectA.publishSnapshots[0]);
    renderPage();

    await screen.findByText('Project A header');
    await user.type(screen.getByLabelText('Locale tags'), 'fr-FR');
    await validatePackage(user);
    await user.click(screen.getByRole('button', { name: 'Publish JSON' }));

    expect(confirm).toHaveBeenCalledWith(
      'Publish immutable JSON snapshot for project-a using fr-FR?',
    );
    expect(mocks.publishCmsProject).not.toHaveBeenCalled();

    confirm.mockReturnValue(true);
    await user.click(screen.getByRole('button', { name: 'Publish JSON' }));

    await waitFor(() =>
      expect(mocks.publishCmsProject).toHaveBeenCalledWith(
        projectA.project.id,
        ['fr-FR'],
        projectA.authoringSha256,
        projectACompleteness.publishPackageSha256,
        'publish-request',
      ),
    );
    confirm.mockRestore();
  });

  it('renders the server-returned snapshot before invalidated refetch completes', async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    mocks.fetchCmsProject
      .mockResolvedValueOnce(projectA)
      .mockImplementation(() => new Promise(() => {}));
    mocks.publishCmsProject.mockResolvedValue(publishedProjectA.publishSnapshots[0]);
    renderPage();

    await screen.findByText('Project A header');
    await validatePackage(user);
    await user.click(screen.getByRole('button', { name: 'Publish JSON' }));

    expect(await screen.findByText('Published snapshot v4.')).toBeVisible();
    expect(screen.queryByText('No published snapshots.')).not.toBeInTheDocument();
    expect(screen.getByText('v4')).toBeVisible();
    expect(screen.getByRole('link', { name: 'JSON' })).toHaveAttribute(
      'href',
      '/delivery/project-a.v4.json',
    );
    confirm.mockRestore();
  });

  it('reuses the publish request key for the same unresolved publish retry', async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    mocks.publishCmsProject
      .mockRejectedValueOnce(new Error('Network failed'))
      .mockResolvedValue(publishedProjectA.publishSnapshots[0]);
    renderPage();

    await screen.findByText('Project A header');
    await user.type(screen.getByLabelText('Locale tags'), 'fr-FR');
    await validatePackage(user);
    await user.click(screen.getByRole('button', { name: 'Publish JSON' }));
    await screen.findByText('Network failed');
    await user.click(screen.getByRole('button', { name: 'Publish JSON' }));

    await waitFor(() => expect(mocks.publishCmsProject).toHaveBeenCalledTimes(2));
    expect(mocks.publishCmsProject).toHaveBeenNthCalledWith(
      1,
      projectA.project.id,
      ['fr-FR'],
      projectA.authoringSha256,
      projectACompleteness.publishPackageSha256,
      'publish-request',
    );
    expect(mocks.publishCmsProject).toHaveBeenNthCalledWith(
      2,
      projectA.project.id,
      ['fr-FR'],
      projectA.authoringSha256,
      projectACompleteness.publishPackageSha256,
      'publish-request',
    );
    expect(mocks.createCmsPublishRequestKey).toHaveBeenCalledTimes(1);
    confirm.mockRestore();
  });

  it('reuses the publish request key after remount for the same unresolved publish retry', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    mocks.publishCmsProject
      .mockRejectedValueOnce(new Error('Network failed'))
      .mockResolvedValue(publishedProjectA.publishSnapshots[0]);
    const firstPage = renderPage();
    const firstUser = userEvent.setup();

    await screen.findByText('Project A header');
    await firstUser.type(screen.getByLabelText('Locale tags'), 'fr-FR');
    await validatePackage(firstUser);
    await firstUser.click(screen.getByRole('button', { name: 'Publish JSON' }));
    await screen.findByText('Network failed');
    firstPage.unmount();

    renderPage();
    const secondUser = userEvent.setup();
    await screen.findByText('Project A header');
    await secondUser.type(screen.getByLabelText('Locale tags'), 'fr-FR');
    await validatePackage(secondUser);
    await secondUser.click(screen.getByRole('button', { name: 'Publish JSON' }));

    await waitFor(() => expect(mocks.publishCmsProject).toHaveBeenCalledTimes(2));
    expect(mocks.publishCmsProject).toHaveBeenNthCalledWith(
      2,
      projectA.project.id,
      ['fr-FR'],
      projectA.authoringSha256,
      projectACompleteness.publishPackageSha256,
      'publish-request',
    );
    expect(mocks.createCmsPublishRequestKey).toHaveBeenCalledTimes(1);
    confirm.mockRestore();
  });

  it('creates a new publish request key after authoring state changes across remount', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    mocks.createCmsPublishRequestKey
      .mockReturnValueOnce('publish-request')
      .mockReturnValueOnce('publish-request-2');
    mocks.publishCmsProject
      .mockRejectedValueOnce(new Error('Network failed'))
      .mockResolvedValue(publishedProjectA.publishSnapshots[0]);
    const firstPage = renderPage();
    const firstUser = userEvent.setup();

    await screen.findByText('Project A header');
    await firstUser.type(screen.getByLabelText('Locale tags'), 'fr-FR');
    await validatePackage(firstUser);
    await firstUser.click(screen.getByRole('button', { name: 'Publish JSON' }));
    await screen.findByText('Network failed');
    firstPage.unmount();

    mocks.fetchCmsProject.mockResolvedValue({
      ...projectA,
      project: { ...projectA.project, entityVersion: 1 },
      authoringSha256: 'c'.repeat(64),
    });
    mocks.fetchCmsProjectCompleteness.mockResolvedValue({
      ...projectACompleteness,
      authoringSha256: 'c'.repeat(64),
      publishPackageSha256: 'd'.repeat(64),
    });
    renderPage();
    const secondUser = userEvent.setup();
    await screen.findByText('Project A header');
    await secondUser.type(screen.getByLabelText('Locale tags'), 'fr-FR');
    await validatePackage(secondUser);
    await secondUser.click(screen.getByRole('button', { name: 'Publish JSON' }));

    await waitFor(() => expect(mocks.publishCmsProject).toHaveBeenCalledTimes(2));
    expect(mocks.publishCmsProject).toHaveBeenNthCalledWith(
      2,
      projectA.project.id,
      ['fr-FR'],
      'c'.repeat(64),
      'd'.repeat(64),
      'publish-request-2',
    );
    expect(mocks.createCmsPublishRequestKey).toHaveBeenCalledTimes(2);
    confirm.mockRestore();
  });

  it('creates a new publish request key after the server rejects a stale publish intent', async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    mocks.createCmsPublishRequestKey
      .mockReturnValueOnce('publish-request')
      .mockReturnValueOnce('publish-request-2');
    mocks.isCmsConflictError.mockReturnValueOnce(true);
    mocks.publishCmsProject
      .mockRejectedValueOnce(
        new Error('Publish request key was already used before configured locale scope changed'),
      )
      .mockResolvedValue(publishedProjectA.publishSnapshots[0]);
    renderPage();

    await screen.findByText('Project A header');
    await validatePackage(user);
    await user.click(screen.getByRole('button', { name: 'Publish JSON' }));
    await screen.findByText(
      'Publish intent no longer matches current content or locale scope. Refreshing current CMS data; review and publish again.',
    );
    await validatePackage(user);
    await user.click(screen.getByRole('button', { name: 'Publish JSON' }));

    await waitFor(() => expect(mocks.publishCmsProject).toHaveBeenCalledTimes(2));
    expect(mocks.publishCmsProject).toHaveBeenNthCalledWith(
      1,
      projectA.project.id,
      [],
      projectA.authoringSha256,
      projectACompleteness.publishPackageSha256,
      'publish-request',
    );
    expect(mocks.publishCmsProject).toHaveBeenNthCalledWith(
      2,
      projectA.project.id,
      [],
      projectA.authoringSha256,
      projectACompleteness.publishPackageSha256,
      'publish-request-2',
    );
    expect(mocks.createCmsPublishRequestKey).toHaveBeenCalledTimes(2);
    confirm.mockRestore();
  });

  it('clears stale package state after a create conflict', async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    mocks.createCmsPublishRequestKey
      .mockReturnValueOnce('publish-request')
      .mockReturnValueOnce('publish-request-2');
    mocks.publishCmsProject
      .mockRejectedValueOnce(new Error('Network failed'))
      .mockResolvedValue(publishedProjectA.publishSnapshots[0]);
    mocks.createCmsEntry.mockRejectedValueOnce(new Error('Content write conflicts'));
    renderPage();

    await screen.findByText('Project A header');
    await validatePackage(user);
    await user.click(screen.getByRole('button', { name: 'Publish JSON' }));
    await screen.findByText('Network failed');

    mocks.isCmsConflictError.mockReturnValueOnce(true);
    const form = entryForm();
    await user.selectOptions(within(form).getByLabelText('Content type'), '101');
    await user.type(within(form).getByLabelText('Entry key'), 'welcome-two');
    await user.type(within(form).getByLabelText('Name'), 'Welcome two');
    await user.click(within(form).getByRole('button', { name: 'Create entry' }));

    await screen.findByText(
      'Content changed since it was loaded. Refreshing current CMS data; review and save again.',
    );
    await waitFor(() => expect(mocks.fetchCmsProject).toHaveBeenCalledTimes(2));
    expect(screen.queryByText('Package complete: Yes for en, fr-FR.')).not.toBeInTheDocument();

    await validatePackage(user);
    await user.click(screen.getByRole('button', { name: 'Publish JSON' }));
    await waitFor(() => expect(mocks.publishCmsProject).toHaveBeenCalledTimes(2));
    expect(mocks.publishCmsProject).toHaveBeenNthCalledWith(
      2,
      projectA.project.id,
      [],
      projectA.authoringSha256,
      projectACompleteness.publishPackageSha256,
      'publish-request-2',
    );
    expect(mocks.createCmsPublishRequestKey).toHaveBeenCalledTimes(2);
    confirm.mockRestore();
  });

  it('confirms unmapping a selected CMS field without deleting its Mojito string', async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    mocks.fetchCmsProject.mockResolvedValue(mappedProjectA);
    mocks.unmapCmsFieldMapping.mockResolvedValue(projectA);
    renderPage();

    await screen.findByText('Project A header');
    const form = mappingForm();
    await user.selectOptions(within(form).getByLabelText('Entry'), '301');
    await user.selectOptions(within(form).getByLabelText('Variant'), '401');
    await user.selectOptions(within(form).getByLabelText('Localizable field'), '201');
    await waitFor(() =>
      expect(within(form).getByLabelText('Mapping source')).toHaveValue('GENERATED'),
    );
    expect(within(form).getByText('Current Mojito string ID').parentElement).toHaveTextContent(
      'cms.project-a.welcome.default.header',
    );

    await user.click(within(form).getByRole('button', { name: 'Unmap field' }));

    expect(confirm).toHaveBeenCalledWith(
      'Unmap cms.project-a.welcome.default.header? It will be excluded from the next published package; the Mojito text unit remains.',
    );
    expect(mocks.unmapCmsFieldMapping).not.toHaveBeenCalled();

    confirm.mockReturnValue(true);
    await user.click(within(form).getByRole('button', { name: 'Unmap field' }));

    await waitFor(() => expect(mocks.unmapCmsFieldMapping).toHaveBeenCalledWith(501, 3));
    confirm.mockRestore();
  });

  it('updates generated mapping source copy without remapping through its current string ID', async () => {
    const user = userEvent.setup();
    mocks.fetchCmsProject.mockResolvedValue(mappedProjectA);
    mocks.upsertCmsFieldMapping.mockResolvedValue(mappedProjectA);
    renderPage();

    await screen.findByText('Project A header');
    const form = mappingForm();
    await user.selectOptions(within(form).getByLabelText('Entry'), '301');
    await user.selectOptions(within(form).getByLabelText('Variant'), '401');
    await user.selectOptions(within(form).getByLabelText('Localizable field'), '201');
    await waitFor(() =>
      expect(within(form).getByLabelText('Mapping source')).toHaveValue('GENERATED'),
    );

    await user.clear(within(form).getByLabelText('Generated source content'));
    await user.type(within(form).getByLabelText('Generated source content'), 'Updated hello');
    await user.clear(within(form).getByLabelText('Generated translator context'));
    await user.type(
      within(form).getByLabelText('Generated translator context'),
      'Updated header context',
    );
    await user.click(within(form).getByRole('button', { name: 'Save mapping' }));

    await waitFor(() =>
      expect(mocks.upsertCmsFieldMapping).toHaveBeenCalledWith(401, {
        expectedVersion: 3,
        fieldId: 201,
        sourceComment: 'Updated header context',
        sourceContent: 'Updated hello',
        stringId: null,
        tmTextUnitId: null,
      }),
    );
  });

  it('keeps non-generated mappings pinned to their exact text unit by default', async () => {
    const user = userEvent.setup();
    mocks.fetchCmsProject.mockResolvedValue(externallyMappedProjectA);
    mocks.upsertCmsFieldMapping.mockResolvedValue(externallyMappedProjectA);
    renderPage();

    await screen.findByText('Project A header');
    const form = mappingForm();
    await user.selectOptions(within(form).getByLabelText('Entry'), '301');
    await user.selectOptions(within(form).getByLabelText('Variant'), '401');
    await user.selectOptions(within(form).getByLabelText('Localizable field'), '201');
    await waitFor(() =>
      expect(within(form).getByLabelText('Mapping source')).toHaveValue('TM_TEXT_UNIT_ID'),
    );
    expect(within(form).getByLabelText('Exact TM text unit ID')).toHaveValue(601);
    expect(within(form).getByText('product-copy.welcome.header')).toBeVisible();
    expect(within(form).getByLabelText('Generated source content')).toBeDisabled();
    expect(within(form).getByLabelText('Generated translator context')).toBeDisabled();

    await user.click(within(form).getByRole('button', { name: 'Save mapping' }));

    await waitFor(() =>
      expect(mocks.upsertCmsFieldMapping).toHaveBeenCalledWith(401, {
        expectedVersion: 3,
        fieldId: 201,
        sourceComment: null,
        sourceContent: null,
        stringId: null,
        tmTextUnitId: 601,
      }),
    );
  });
});
