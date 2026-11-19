export type RepositoryTargetLocales = {
  sourceLocaleTag: string;
  targetLocaleTags: string[];
};

export async function fetchRepositoryTargetLocales(
  repositoryId: number,
): Promise<RepositoryTargetLocales> {
  const response = await fetch(`/api/repositories/${repositoryId}/target-locales`, {
    credentials: 'same-origin',
  });
  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load repository locales');
  }
  return (await response.json()) as RepositoryTargetLocales;
}

export async function updateRepositoryTargetLocales(
  repositoryId: number,
  localeTags: string[],
): Promise<RepositoryTargetLocales> {
  const response = await fetch(`/api/repositories/${repositoryId}/target-locales`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(localeTags),
  });
  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to save repository locales');
  }
  return (await response.json()) as RepositoryTargetLocales;
}
