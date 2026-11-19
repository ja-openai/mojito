const PATH = '/api/generation-excluded-locales';

export async function fetchGenerationExcludedLocales(): Promise<string[]> {
  const response = await fetch(PATH, { credentials: 'same-origin' });
  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load generation settings');
  }
  return (await response.json()) as string[];
}

export async function updateGenerationExcludedLocales(localeTags: string[]): Promise<string[]> {
  const response = await fetch(PATH, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(localeTags),
  });
  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to save generation settings');
  }
  return (await response.json()) as string[];
}
