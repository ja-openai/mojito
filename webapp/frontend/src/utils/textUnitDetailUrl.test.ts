import {
  buildCmsAuthoringPath,
  buildCmsSelectionPath,
  buildCmsTextUnitDetailLink,
  CMS_ADMIN_PATH,
  formatCmsTextUnitContext,
  getCmsAuthoringSelectionFromSearchParams,
  getCmsTextUnitContextFromSearchParams,
  getCmsTextUnitReturnTo,
  isCmsAuthoringPath,
  isCmsTextUnitContext,
} from './textUnitDetailUrl';

const cmsTextUnitContext = {
  projectId: 1,
  projectName: 'Growth email copy',
  entryId: 301,
  entryName: 'Welcome email',
  fieldId: 201,
  fieldName: 'Headline',
};

describe('textUnitDetailUrl CMS handoff', () => {
  it('builds a detail link with a durable CMS return path', () => {
    const link = buildCmsTextUnitDetailLink(601, cmsTextUnitContext, 'fr-FR');

    expect(link).toEqual({
      to: {
        pathname: '/text-units/601',
        search:
          '?locale=fr-FR&returnTo=%2Fsettings%2Fsystem%2Fcontent-cms%3FprojectId%3D1%26entryId%3D301%26fieldId%3D201%26locale%3Dfr-FR&cmsProject=Growth+email+copy&cmsEntry=Welcome+email&cmsField=Headline',
      },
      state: {
        from: '/settings/system/content-cms?projectId=1&entryId=301&fieldId=201&locale=fr-FR',
        cmsContext: cmsTextUnitContext,
      },
    });
  });

  it('parses only CMS-owned authoring selections and return paths', () => {
    const authoringPath = buildCmsAuthoringPath(cmsTextUnitContext);
    const adminPath = buildCmsSelectionPath(CMS_ADMIN_PATH, cmsTextUnitContext);
    const authoringSearchParams = new URL(authoringPath, 'http://localhost').searchParams;
    const detailSearchParams = new URL(
      buildCmsTextUnitDetailLink(601, cmsTextUnitContext, 'fr-FR').to.search,
      'http://localhost',
    ).searchParams;

    expect(getCmsAuthoringSelectionFromSearchParams(authoringSearchParams)).toEqual({
      projectId: 1,
      entryId: 301,
      fieldId: 201,
      localeTag: null,
    });
    expect(
      getCmsAuthoringSelectionFromSearchParams(
        new URL(buildCmsAuthoringPath(cmsTextUnitContext, 'fr-FR'), 'http://localhost')
          .searchParams,
      ),
    ).toEqual({
      projectId: 1,
      entryId: 301,
      fieldId: 201,
      localeTag: 'fr-FR',
    });
    expect(getCmsTextUnitReturnTo(new URLSearchParams({ returnTo: authoringPath }))).toBe(
      authoringPath,
    );
    expect(getCmsTextUnitReturnTo(new URLSearchParams({ returnTo: 'https://example.com' }))).toBe(
      null,
    );
    expect(getCmsTextUnitContextFromSearchParams(detailSearchParams)).toEqual(cmsTextUnitContext);
    expect(
      getCmsTextUnitContextFromSearchParams(
        new URLSearchParams({
          returnTo: authoringPath,
          cmsProject: 'Growth email copy',
          cmsEntry: 'Welcome email',
        }),
      ),
    ).toBeNull();
    expect(isCmsAuthoringPath(authoringPath)).toBe(true);
    expect(isCmsAuthoringPath(adminPath)).toBe(false);
    expect(isCmsAuthoringPath('/workbench')).toBe(false);
  });

  it('recognizes CMS context used by text-unit detail', () => {
    expect(isCmsTextUnitContext(cmsTextUnitContext)).toBe(true);
    expect(isCmsTextUnitContext({ ...cmsTextUnitContext, fieldId: 0 })).toBe(false);
    expect(isCmsTextUnitContext({ ...cmsTextUnitContext, fieldName: ' ' })).toBe(false);
    expect(formatCmsTextUnitContext(cmsTextUnitContext)).toBe(
      'Growth email copy / Welcome email / Headline',
    );
  });
});
