import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
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
  useRepositories: () => ({ data: [], isLoading: false, isError: false }),
}));

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({
    username: 'admin',
    role: 'ROLE_ADMIN',
    canTranslateAllLocales: true,
    userLocales: [],
  }),
}));

const cmsAudit = {
  createdDate: null,
  lastModifiedDate: null,
  createdByUsername: 'admin',
  lastModifiedByUsername: 'admin',
};

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
  };
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      mutations: { retry: false },
      queries: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/settings/system/content-cms']}>
        <Routes>
          <Route path="/settings/system/content-cms" element={<AdminContentCmsPage />} />
          <Route path="/repositories" element={<div>Repositories</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
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

function variantForm() {
  const form = screen.getByRole('heading', { name: 'Add variant' }).closest('form');
  if (form == null) {
    throw new Error('Variant form not found');
  }
  return form;
}

function variantEditForm() {
  const form = screen.getByRole('heading', { name: 'Edit variant' }).closest('form');
  if (form == null) {
    throw new Error('Variant edit form not found');
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
    mocks.fetchCmsProjects.mockResolvedValue({
      projects: [projectA.project, projectB.project],
      totalCount: 2,
    });
    mocks.fetchCmsProject.mockImplementation((projectId: number) =>
      Promise.resolve(projectId === projectB.project.id ? projectB : projectA),
    );
    mocks.fetchCmsProjectCompleteness.mockResolvedValue(projectACompleteness);
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
    expect(await screen.findByText('Variant is required.')).toBeVisible();
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
    expect(
      await screen.findByText('Translator context is required when generating a CMS string ID.'),
    ).toBeVisible();
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

  it('requires a current package validation before publish', async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    renderPage();

    await screen.findByText('Project A header');
    await user.click(screen.getByRole('button', { name: 'Publish JSON' }));

    expect(await screen.findByText('Validate package before publishing.')).toBeVisible();
    expect(confirm).not.toHaveBeenCalled();
    expect(mocks.publishCmsProject).not.toHaveBeenCalled();
    confirm.mockRestore();
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
