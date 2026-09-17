import type {
  ApiReviewProjectDocument,
  ApiReviewProjectDocumentBlock,
  ApiReviewProjectDocuments,
  ApiReviewProjectTextUnit,
} from '../../api/review-projects';
import { documentBlockRow } from './review-project-document';

export type ReviewProjectDocumentNavigationEntry = {
  key: string;
  title: string;
  assetPath: string;
  repositoryId: number;
  repositoryName: string;
  branchName: string | null;
  isModule: boolean;
  parentKeys: string[];
  searchText: string;
  document: ApiReviewProjectDocument;
};

export function documentKey(document: ApiReviewProjectDocument): string {
  return JSON.stringify([
    document.repositoryId,
    document.assetId,
    document.branchId ?? null,
    document.branchName,
  ]);
}

export function normalizeDocumentSearch(value: string): string {
  return value.normalize('NFKD').replace(/\p{M}/gu, '').toLocaleLowerCase();
}

function savedBlockText(
  block: ApiReviewProjectDocumentBlock,
  rows: ReadonlyMap<number, ApiReviewProjectTextUnit>,
): string {
  const row = documentBlockRow(block, rows);
  const variant =
    row?.currentTmTextUnitVariant?.id != null
      ? row.currentTmTextUnitVariant
      : row?.baselineTmTextUnitVariant;
  return variant?.content != null && variant.content.length > 0 ? variant.content : block.source;
}

function plainTitle(value: string): string {
  return value
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/[*_`~]/g, '')
    .trim();
}

function documentScope(document: ApiReviewProjectDocument): string {
  return JSON.stringify([document.repositoryId, document.branchId ?? null, document.branchName]);
}

export function buildDocumentNavigation(
  data: ApiReviewProjectDocuments,
  textUnits: ApiReviewProjectTextUnit[],
): ReviewProjectDocumentNavigationEntry[] {
  const rows = new Map(textUnits.map((row) => [row.id, row]));
  const repositoryNames = new Map<number, string>();
  for (const row of textUnits) {
    const repository = row.tmTextUnit?.asset?.repository;
    if (repository?.id != null && repository.name?.trim()) {
      repositoryNames.set(repository.id, repository.name.trim());
    }
  }
  const entries = data.documents.map((document): ReviewProjectDocumentNavigationEntry => {
    const repositoryName =
      repositoryNames.get(document.repositoryId) ?? `Repository ${document.repositoryId}`;
    const heading = document.blocks.find(
      (block) =>
        block.type === 'heading' &&
        (block.moduleDepth ?? 0) === 0 &&
        (block.assetId == null || block.assetId === document.assetId),
    );
    const title = heading
      ? plainTitle(savedBlockText(heading, rows))
      : document.assetPath.split('/').pop() || document.assetPath;
    return {
      key: documentKey(document),
      title,
      assetPath: document.assetPath,
      repositoryId: document.repositoryId,
      repositoryName,
      branchName: document.branchName,
      isModule: false,
      parentKeys: [],
      searchText: normalizeDocumentSearch(
        [
          title,
          repositoryName,
          heading?.source ?? '',
          document.assetPath,
          document.branchName ?? '',
          ...document.blocks.flatMap((block) => [block.source, savedBlockText(block, rows)]),
        ].join('\n'),
      ),
      document,
    };
  });
  for (const parent of entries) {
    const scope = documentScope(parent.document);
    const candidates = entries.filter((entry) => documentScope(entry.document) === scope);
    const included = new Set<string>();
    for (const block of parent.document.blocks) {
      for (const candidate of candidates) {
        const matchesPath =
          block.type === 'component' &&
          block.moduleStatus === 'EXPANDED' &&
          block.modulePath === candidate.assetPath;
        const matchesDescendant =
          (block.moduleDepth ?? 0) > 0 && block.assetId === candidate.document.assetId;
        if (matchesPath || matchesDescendant) included.add(candidate.key);
      }
    }
    for (const child of candidates) {
      if (parent.key === child.key) continue;
      if (!included.has(child.key)) continue;
      child.isModule = true;
      if (!child.parentKeys.includes(parent.key)) {
        child.parentKeys.push(parent.key);
      }
    }
  }
  return entries;
}

export type ReviewDocumentTreeNode =
  | {
      key: string;
      kind: 'repository' | 'branch' | 'directory';
      label: string;
      children: ReviewDocumentTreeNode[];
    }
  | { key: string; entry: ReviewProjectDocumentNavigationEntry };

/** Build only from the documents included in this review, never the repository catalogue. */
export function buildReviewDocumentTree(
  entries: ReviewProjectDocumentNavigationEntry[],
): ReviewDocumentTreeNode[] {
  const repositories = new Map<number, ReviewProjectDocumentNavigationEntry[]>();
  for (const entry of entries) {
    const group = repositories.get(entry.repositoryId) ?? [];
    group.push(entry);
    repositories.set(entry.repositoryId, group);
  }
  const tree: ReviewDocumentTreeNode[] = [...repositories].map(
    ([repositoryId, repositoryEntries]) => {
      const repositoryKey = JSON.stringify(['repository', repositoryId]);
      const children: ReviewDocumentTreeNode[] = [];
      const branches = new Map<string, ReviewProjectDocumentNavigationEntry[]>();
      for (const entry of repositoryEntries) {
        const key = documentScope(entry.document);
        const branch = branches.get(key) ?? [];
        branch.push(entry);
        branches.set(key, branch);
      }
      for (const [scope, branchEntries] of branches) {
        const folderChildren: ReviewDocumentTreeNode[] = [];
        const directories = new Map<string, ReviewDocumentTreeNode[]>();
        directories.set('', folderChildren);
        for (const entry of branchEntries) {
          const segments = entry.assetPath.split('/').filter(Boolean);
          let path = '';
          let parent = folderChildren;
          for (const segment of segments.slice(0, -1)) {
            path += `${segment}/`;
            let nested = directories.get(path);
            if (!nested) {
              nested = [];
              directories.set(path, nested);
              parent.push({
                key: JSON.stringify(['directory', scope, path]),
                kind: 'directory',
                label: segment,
                children: nested,
              });
            }
            parent = nested;
          }
          parent.push({ key: entry.key, entry });
        }
        if (branches.size > 1) {
          const branch = branchEntries[0];
          const label = branch.branchName ?? 'Default branch';
          children.push({
            key: JSON.stringify(['branch', scope]),
            kind: 'branch',
            label: label || 'Unnamed branch',
            children: folderChildren,
          });
        } else {
          children.push(...folderChildren);
        }
      }
      return {
        key: repositoryKey,
        kind: 'repository',
        label: repositoryEntries[0].repositoryName,
        children,
      };
    },
  );
  const pending = [tree];
  while (pending.length) {
    const siblings = pending.pop()!;
    siblings.sort((left, right) => {
      const leftFolder = 'children' in left;
      const rightFolder = 'children' in right;
      if (leftFolder !== rightFolder) return leftFolder ? -1 : 1;
      const label = (node: ReviewDocumentTreeNode) =>
        'entry' in node ? node.entry.assetPath : node.label;
      return label(left).localeCompare(label(right));
    });
    for (const node of siblings) if ('children' in node) pending.push(node.children);
  }
  return tree;
}
