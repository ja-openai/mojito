export type ApiAiTranslateLocalePromptSuffix = {
  localeTag: string;
  promptSuffix: string;
  createdAt: string | null;
  updatedAt: string | null;
};

type UpsertAiTranslateLocalePromptSuffixRequest = {
  localeTag: string;
  promptSuffix: string;
};

export const fetchAiTranslateLocalePromptSuffixes =
  async (): Promise<ApiAiTranslateLocalePromptSuffix[]> => {
    const response = await fetch('/api/ai-translate/locale-prompt-suffixes', {
      method: 'GET',
      credentials: 'same-origin',
    });

    if (!response.ok) {
      const message = await response.text().catch(() => '');
      throw new Error(message || 'Failed to load AI locale prompt suffixes');
    }

    return (await response.json()) as ApiAiTranslateLocalePromptSuffix[];
  };

export const upsertAiTranslateLocalePromptSuffix = async (
  payload: UpsertAiTranslateLocalePromptSuffixRequest,
): Promise<ApiAiTranslateLocalePromptSuffix> => {
  const response = await fetch('/api/ai-translate/locale-prompt-suffixes', {
    method: 'PUT',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to save AI locale prompt suffix');
  }

  return (await response.json()) as ApiAiTranslateLocalePromptSuffix;
};

export const deleteAiTranslateLocalePromptSuffix = async (localeTag: string): Promise<void> => {
  const response = await fetch(`/api/ai-translate/locale-prompt-suffixes/${encodeURIComponent(localeTag)}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to delete AI locale prompt suffix');
  }
};
