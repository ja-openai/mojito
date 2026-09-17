import { emailCompanionPaths } from '../utils/emailAssetPaths';
import type { ApiReviewProjectDocument } from './review-projects';

export type ApiContentAsset = {
  assetId: number;
  assetPath: string;
  branchId: number;
  branchName: string | null;
  sourceContentMd5: string;
};

export type ApiContentAssets = {
  repositoryId: number;
  sourceLocaleTag: string;
  branchId: number | null;
  branchName: string | null;
  branches: { id: number; name: string | null }[];
  assets: ApiContentAsset[];
  offset: number;
  hasMore: boolean;
  nextCursor?: string | null;
  previousCursor?: string | null;
  warnings: string[];
};

export function normalizedDirectory(value: string) {
  if (!value) return '';
  if (
    value.length > 255 ||
    value.startsWith('/') ||
    value.includes('\\') ||
    [...value].some((character) => {
      const code = character.charCodeAt(0);
      return code < 32 || (code >= 127 && code <= 159);
    })
  )
    return null;
  const parts = value.split('/').filter(Boolean);
  if (parts.some((part) => part === '.' || part === '..')) return null;
  const normalized = parts.join('/') + '/';
  return normalized.length <= 255 ? normalized : null;
}

export type ContentSearchMode = 'prefix' | 'exact' | 'contains';

export type ApiContentDirectories = {
  repositoryId: number;
  branchId: number | null;
  directory: string;
  directories: string[];
  nextCursor: string | null;
};

export type ApiContentPreview = {
  repositoryId: number;
  branchId: number;
  branchName: string | null;
  sourceLocaleTag: string;
  localeTag: string;
  document: ApiReviewProjectDocument | null;
  warnings: string[];
};

async function readContent<T>(url: string): Promise<T> {
  const response = await fetch(url);
  if (!response.ok) throw new Error('Could not load repository content.');
  return (await response.json()) as T;
}

export type ContentAssetsOptions = {
  branchId: number | null;
  query: string;
  offset?: number;
  searchMode?: ContentSearchMode;
  directory?: string;
  recursive?: boolean;
  after?: string | null;
  before?: string | null;
};

export function contentAssetsQueryKey(repositoryId: number | null, options: ContentAssetsOptions) {
  return [
    'content-assets',
    repositoryId,
    options.branchId,
    options.query,
    options.searchMode ?? (options.query ? 'contains' : 'prefix'),
    options.directory ?? '',
    options.recursive ?? true,
    options.after ?? null,
    options.before ?? null,
    options.offset ?? 0,
  ];
}

export function fetchContentAssets(repositoryId: number, options: ContentAssetsOptions) {
  const params = new URLSearchParams({ limit: '100' });
  if (options.offset) params.set('offset', String(options.offset));
  else params.set('pagination', 'cursor');
  if (options.branchId != null) params.set('branchId', String(options.branchId));
  if (options.query) params.set('q', options.query);
  if (options.searchMode) params.set('searchMode', options.searchMode);
  if (options.directory) params.set('directory', options.directory);
  if (options.recursive === false) params.set('recursive', 'false');
  if (options.after) params.set('after', options.after);
  if (options.before) params.set('before', options.before);
  return readContent<ApiContentAssets>(`/api/repositories/${repositoryId}/content?${params}`);
}

export function fetchContentDirectories(
  repositoryId: number,
  options: { branchId: number | null; directory: string; after: string | null },
) {
  const params = new URLSearchParams({ limit: '50' });
  if (options.branchId != null) params.set('branchId', String(options.branchId));
  if (options.directory) params.set('directory', options.directory);
  if (options.after) params.set('after', options.after);
  return readContent<ApiContentDirectories>(
    `/api/repositories/${repositoryId}/content/directories?${params}`,
  );
}

export function fetchContentPreview(
  repositoryId: number,
  assetId: number,
  branchId: number | null,
  locale: string,
) {
  const params = new URLSearchParams({ locale });
  if (branchId != null) params.set('branchId', String(branchId));
  return readContent<ApiContentPreview>(
    `/api/repositories/${repositoryId}/content/${assetId}?${params}`,
  );
}

export async function fetchContentEmailParts(
  repositoryId: number,
  branchId: number,
  locale: string,
  bodyPath: string,
) {
  const previews = await Promise.all(
    emailCompanionPaths(bodyPath).map(async (path) => {
      const index = await fetchContentAssets(repositoryId, {
        branchId,
        query: path,
        searchMode: 'exact',
      });
      const asset = index.assets.find(
        (asset) => asset.assetPath === path && asset.branchId === branchId,
      );
      return asset ? fetchContentPreview(repositoryId, asset.assetId, branchId, locale) : null;
    }),
  );
  return {
    documents: previews.flatMap((preview) => (preview?.document ? [preview.document] : [])),
    warnings: previews.flatMap((preview) => preview?.warnings ?? []),
  };
}
