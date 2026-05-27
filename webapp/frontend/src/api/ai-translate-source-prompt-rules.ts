export type ApiAiTranslateSourcePromptRule = {
  id: number;
  name: string;
  description: string | null;
  enabled: boolean;
  priority: number;
  matchType: 'REGEX';
  sourceRegex: string;
  promptSuffix: string;
  createdAt: string | null;
  updatedAt: string | null;
};

export type UpsertAiTranslateSourcePromptRuleRequest = {
  id?: number | null;
  name: string;
  description?: string | null;
  enabled: boolean;
  priority: number;
  matchType: 'REGEX';
  sourceRegex: string;
  promptSuffix: string;
};

export type ApiAiTranslateSourcePromptRuleTestResponse = {
  matches: boolean;
  matchesList: Array<{
    start: number;
    end: number;
    snippet: string;
  }>;
};

const SOURCE_PROMPT_RULES_URL = '/api/ai-translate/source-prompt-rules';

export const fetchAiTranslateSourcePromptRules = async (): Promise<
  ApiAiTranslateSourcePromptRule[]
> => {
  const response = await fetch(SOURCE_PROMPT_RULES_URL, {
    method: 'GET',
    credentials: 'same-origin',
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load AI source prompt rules');
  }

  return (await response.json()) as ApiAiTranslateSourcePromptRule[];
};

export const upsertAiTranslateSourcePromptRule = async (
  payload: UpsertAiTranslateSourcePromptRuleRequest,
): Promise<ApiAiTranslateSourcePromptRule> => {
  const response = await fetch(SOURCE_PROMPT_RULES_URL, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to save AI source prompt rule');
  }

  return (await response.json()) as ApiAiTranslateSourcePromptRule;
};

export const deleteAiTranslateSourcePromptRule = async (id: number): Promise<void> => {
  const response = await fetch(`${SOURCE_PROMPT_RULES_URL}/${id}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to delete AI source prompt rule');
  }
};

export const testAiTranslateSourcePromptRule = async ({
  sourceRegex,
  sourceText,
}: {
  sourceRegex: string;
  sourceText: string;
}): Promise<ApiAiTranslateSourcePromptRuleTestResponse> => {
  const response = await fetch(`${SOURCE_PROMPT_RULES_URL}/test`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ sourceRegex, sourceText }),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to test AI source prompt rule');
  }

  return (await response.json()) as ApiAiTranslateSourcePromptRuleTestResponse;
};
