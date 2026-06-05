import type { ApiCmsProjectDetail } from '../../api/content-cms';

export type CmsPublishIntent = {
  projectId: number;
  localeTagsKey: string;
  publishStateKey: string;
  publishRequestKey: string;
};

type StoredCmsPublishIntent = CmsPublishIntent & {
  version: 1;
};

const STORAGE_PREFIX = 'content-cms.publish-intent.v1:';
const STORAGE_VERSION = 1;
const PUBLISH_REQUEST_KEY_MAX_LENGTH = 128;
const PUBLISH_REQUEST_KEY_PATTERN = /^[a-z0-9][a-z0-9_-]*$/;

function getStorage(): Storage | null {
  if (typeof window === 'undefined') {
    return null;
  }
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

function publishIntentStorageKey(projectId: number, localeTagsKey: string) {
  return `${STORAGE_PREFIX}${projectId}:${encodeURIComponent(localeTagsKey)}`;
}

function parseStoredPublishIntent(raw: string): StoredCmsPublishIntent | null {
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!parsed || typeof parsed !== 'object') {
      return null;
    }
    const intent = parsed as Partial<StoredCmsPublishIntent>;
    if (
      intent.version !== STORAGE_VERSION ||
      !Number.isInteger(intent.projectId) ||
      (intent.projectId ?? 0) < 1 ||
      typeof intent.localeTagsKey !== 'string' ||
      typeof intent.publishStateKey !== 'string' ||
      typeof intent.publishRequestKey !== 'string' ||
      !isValidPublishRequestKey(intent.publishRequestKey)
    ) {
      return null;
    }
    return intent as StoredCmsPublishIntent;
  } catch {
    return null;
  }
}

export function buildCmsPublishLocaleTagsKey(localeTags: string[]) {
  return localeTags
    .map((localeTag) => localeTag.trim())
    .sort()
    .join(',');
}

function isValidPublishRequestKey(publishRequestKey: string) {
  return (
    publishRequestKey.length <= PUBLISH_REQUEST_KEY_MAX_LENGTH &&
    PUBLISH_REQUEST_KEY_PATTERN.test(publishRequestKey)
  );
}

export function buildCmsPublishStateKey(detail: ApiCmsProjectDetail, publishPackageSha256: string) {
  return `${detail.authoringSha256}:${publishPackageSha256}`;
}

export function loadCmsPublishIntent(
  projectId: number,
  localeTagsKey: string,
  publishStateKey: string,
): CmsPublishIntent | null {
  const storage = getStorage();
  if (!storage) {
    return null;
  }
  const storageKey = publishIntentStorageKey(projectId, localeTagsKey);
  const raw = storage.getItem(storageKey);
  if (!raw) {
    return null;
  }
  const intent = parseStoredPublishIntent(raw);
  if (
    intent == null ||
    intent.projectId !== projectId ||
    intent.localeTagsKey !== localeTagsKey ||
    intent.publishStateKey !== publishStateKey
  ) {
    storage.removeItem(storageKey);
    return null;
  }
  return intent;
}

export function saveCmsPublishIntent(intent: CmsPublishIntent) {
  const storage = getStorage();
  if (!storage) {
    return;
  }
  try {
    storage.setItem(
      publishIntentStorageKey(intent.projectId, intent.localeTagsKey),
      JSON.stringify({ version: STORAGE_VERSION, ...intent } satisfies StoredCmsPublishIntent),
    );
  } catch {
    // In-memory retry still works if session storage is unavailable or full.
  }
}

export function clearCmsPublishIntent(projectId: number, localeTagsKey: string) {
  getStorage()?.removeItem(publishIntentStorageKey(projectId, localeTagsKey));
}

export function clearCmsPublishIntentsForProject(projectId: number) {
  const storage = getStorage();
  if (!storage) {
    return;
  }
  const storagePrefix = `${STORAGE_PREFIX}${projectId}:`;
  const storageKeys: string[] = [];
  for (let index = 0; index < storage.length; index += 1) {
    const storageKey = storage.key(index);
    if (storageKey?.startsWith(storagePrefix)) {
      storageKeys.push(storageKey);
    }
  }
  storageKeys.forEach((storageKey) => storage.removeItem(storageKey));
}
