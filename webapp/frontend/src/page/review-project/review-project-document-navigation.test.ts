import { describe, expect, it } from 'vitest';

import type {
  ApiReviewProjectDocument,
  ApiReviewProjectDocumentBlock,
  ApiReviewProjectTextUnit,
} from '../../api/review-projects';
import {
  buildDocumentNavigation,
  buildReviewDocumentTree,
  documentKey,
} from './review-project-document-navigation';

function block(
  overrides: Partial<ApiReviewProjectDocumentBlock> = {},
): ApiReviewProjectDocumentBlock {
  return {
    id: 'heading',
    type: 'heading',
    depth: 1,
    source: 'Source title',
    line: 1,
    translatable: true,
    reviewProjectTextUnitId: null,
    tmTextUnitId: null,
    mappingStatus: 'NOT_IN_PROJECT',
    moduleDepth: 0,
    ...overrides,
  };
}

function document(
  assetId: number,
  assetPath: string,
  blocks: ApiReviewProjectDocumentBlock[] = [block()],
  overrides: Partial<ApiReviewProjectDocument> = {},
): ApiReviewProjectDocument {
  return {
    assetId,
    assetPath,
    repositoryId: 1,
    branchName: null,
    sourceContentMd5: 'source-revision',
    blocks,
    warnings: [],
    ...overrides,
  };
}

function moduleReference(assetPath: string): ApiReviewProjectDocumentBlock {
  return block({
    type: 'component',
    source: '<Module />',
    translatable: false,
    moduleStatus: 'EXPANDED',
    modulePath: assetPath,
    mappingStatus: 'CONTEXT',
  });
}

const row: ApiReviewProjectTextUnit = {
  id: 11,
  tmTextUnit: { id: 21, content: 'Source title' },
  baselineTmTextUnitVariant: { id: 31, content: 'Ancien titre' },
  currentTmTextUnitVariant: { id: 32, content: 'Titre **enregistré**' },
};

describe('document navigation', () => {
  it('uses project text units for repository names and only supplied review documents for files', () => {
    const first = document(1, 'content/index.mdx');
    const second = document(1, 'content/index.mdx', [block()], { repositoryId: 2 });
    const names = [
      { ...row, tmTextUnit: { id: 21, asset: { repository: { id: 1, name: 'Docs' } } } },
      { ...row, id: 12, tmTextUnit: { id: 22, asset: { repository: { id: 2, name: 'Website' } } } },
      {
        ...row,
        id: 13,
        tmTextUnit: { id: 23, asset: { repository: { id: 3, name: 'No retained documents' } } },
      },
    ];
    const entries = buildDocumentNavigation({ documents: [first, second], warnings: [] }, names);
    expect(entries.map((entry) => entry.repositoryName)).toEqual(['Docs', 'Website']);
    expect(entries[0].searchText).toContain('docs');
    expect(entries[0].key).not.toBe(entries[1].key);
    const tree = buildReviewDocumentTree(entries);
    expect(tree).toHaveLength(2);
    expect(tree).toMatchObject([
      {
        label: 'Docs',
        children: [{ label: 'content', children: [{ entry: { repositoryId: 1 } }] }],
      },
      {
        label: 'Website',
        children: [{ label: 'content', children: [{ entry: { repositoryId: 2 } }] }],
      },
    ]);
  });

  it('separates branches only when a repository has several and puts folders before files', () => {
    const documents = [
      document(1, 'index.mdx'),
      document(2, 'modules/tip.mdx'),
      document(1, 'index.mdx', [block()], { branchName: 'release', branchId: 8 }),
      document(3, 'single.mdx', [block()], { repositoryId: 2, branchName: 'release' }),
    ];
    const tree = buildReviewDocumentTree(buildDocumentNavigation({ documents, warnings: [] }, []));
    expect(tree).toMatchObject([
      {
        label: 'Repository 1',
        children: [
          {
            label: 'Default branch',
            children: [{ label: 'modules' }, { entry: { assetPath: 'index.mdx' } }],
          },
          { label: 'release', children: [{ entry: { assetPath: 'index.mdx' } }] },
        ],
      },
      { label: 'Repository 2', children: [{ entry: { assetPath: 'single.mdx' } }] },
    ]);
    expect(documentKey(documents[0])).not.toBe(documentKey(documents[2]));
    expect(documentKey(documents[2])).not.toBe(documentKey({ ...documents[2], branchId: 9 }));
  });

  it('groups nested and reused modules without duplicating their parent pages', () => {
    const home = document(1, 'index.mdx', [
      block({ source: 'Home' }),
      moduleReference('modules/Welcome.mdx'),
      block({ assetId: 2, moduleDepth: 1 }),
      moduleReference('modules/Welcome.mdx'),
      block({ assetId: 3, moduleDepth: 2, source: 'Nested note' }),
    ]);
    const guide = document(4, 'guide.mdx', [block(), moduleReference('modules/Tip.mdx')]);
    const welcome = document(2, 'modules/Welcome.mdx', [
      block(),
      moduleReference('modules/Tip.mdx'),
    ]);
    const tip = document(3, 'modules/Tip.mdx');
    const entries = buildDocumentNavigation(
      { documents: [home, guide, welcome, tip], warnings: [] },
      [],
    );
    expect(entries.filter((entry) => !entry.isModule).map((entry) => entry.assetPath)).toEqual([
      'index.mdx',
      'guide.mdx',
    ]);
    expect(entries[2].parentKeys).toEqual([documentKey(home)]);
    expect(entries[3].parentKeys).toEqual([
      documentKey(home),
      documentKey(guide),
      documentKey(welcome),
    ]);
    expect(entries).toHaveLength(4);
  });

  it('uses only own headings and saved translations, with source title also searchable', () => {
    const page = document(1, 'home.mdx', [
      block({ source: 'Nested module title', moduleDepth: 1, assetId: 2 }),
      block({ reviewProjectTextUnitId: 11, tmTextUnitId: 21, mappingStatus: 'MATCHED' }),
      block({ type: 'paragraph', source: 'Nested saved body', moduleDepth: 2, assetId: 3 }),
    ]);
    const [entry] = buildDocumentNavigation({ documents: [page], warnings: [] }, [row]);
    expect(entry.title).toBe('Titre enregistré');
    expect(entry.searchText).toContain('titre enregistre');
    expect(entry.searchText).toContain('source title');
    expect(entry.searchText).toContain('home.mdx');
    expect(entry.searchText).toContain('nested saved body');
    expect(entry.searchText).not.toContain('ancien titre');
  });

  it('searches the rendered saved module translation rather than an unrendered stale row', () => {
    const body = block({
      type: 'paragraph',
      reviewProjectTextUnitId: 11,
      tmTextUnitId: 21,
      mappingStatus: 'MATCHED',
      moduleDepth: 1,
      assetId: 2,
    });
    const page = document(1, 'home.mdx', [block({ source: 'Home' }), body]);
    const [saved] = buildDocumentNavigation({ documents: [page], warnings: [] }, [row]);
    expect(saved.searchText).toContain('titre **enregistre**');
    const stale = { ...row, tmTextUnit: { id: 21, content: 'New source version' } };
    const [fallback] = buildDocumentNavigation({ documents: [page], warnings: [] }, [stale]);
    expect(fallback.searchText).toContain('source title');
    expect(fallback.searchText).not.toContain('titre **enregistre**');
  });

  it('uses baseline when there is no saved current variant, and source on an empty translation', () => {
    const page = document(1, 'home.mdx', [
      block({ reviewProjectTextUnitId: 11, tmTextUnitId: 21, mappingStatus: 'MATCHED' }),
    ]);
    const data = { documents: [page], warnings: [] };
    const [baseline] = buildDocumentNavigation(data, [{ ...row, currentTmTextUnitVariant: null }]);
    expect(baseline.title).toBe('Ancien titre');
    const [empty] = buildDocumentNavigation(data, [
      { ...row, currentTmTextUnitVariant: { id: 32, content: '' } },
    ]);
    expect(empty.title).toBe('Source title');
  });

  it('keeps module inference within the exact repository and branch', () => {
    const root = document(1, 'index.mdx', [moduleReference('shared.mdx')]);
    const sameScope = document(2, 'shared.mdx');
    const anotherRepo = document(3, 'shared.mdx', [block()], { repositoryId: 2 });
    const anotherBranch = document(2, 'shared.mdx', [block()], { branchName: 'draft' });
    const emptyBranch = document(2, 'shared.mdx', [block()], { branchName: '' });
    const entries = buildDocumentNavigation(
      { documents: [root, sameScope, anotherRepo, anotherBranch, emptyBranch], warnings: [] },
      [],
    );
    expect(entries.map((entry) => entry.isModule)).toEqual([false, true, false, false, false]);
    expect(documentKey(sameScope)).not.toBe(documentKey(emptyBranch));
  });

  it('does not infer resolved targets from unavailable import specifiers', () => {
    const root = document(1, 'index.mdx', [
      { ...moduleReference('shared.mdx'), moduleStatus: 'UNAVAILABLE' },
    ]);
    const child = document(2, 'shared.mdx');
    const entries = buildDocumentNavigation({ documents: [root, child], warnings: [] }, []);
    expect(entries[1].isModule).toBe(false);
  });

  it('retains every document in a cycle even when no page root remains', () => {
    const first = document(1, 'first.mdx', [moduleReference('second.mdx')]);
    const second = document(2, 'second.mdx', [moduleReference('first.mdx')]);
    const entries = buildDocumentNavigation({ documents: [first, second], warnings: [] }, []);
    expect(entries).toHaveLength(2);
    expect(entries.every((entry) => entry.isModule)).toBe(true);
    expect(entries[0].parentKeys).toEqual([documentKey(second)]);
    expect(entries[1].parentKeys).toEqual([documentKey(first)]);
  });

  it('uses a filename when only included headings exist and never fabricates module documents', () => {
    const page = document(1, 'content/landing.mdx', [
      moduleReference('included-only.mdx'),
      block({ assetId: 2, moduleDepth: 1, source: 'A module heading' }),
    ]);
    const entries = buildDocumentNavigation({ documents: [page], warnings: [] }, []);
    expect(entries).toHaveLength(1);
    expect(entries[0].title).toBe('landing.mdx');
    expect(entries[0].searchText).toContain('a module heading');
  });
});
