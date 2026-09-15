import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';

import {
  ownReviewProjectAiSuggestions,
  reviewProjectAiSuggestionOrigin,
} from '../../api/review-project-client-context';
import type { AiChatReviewMessage } from '../../components/AiChatReview';
import { useAgentReviewChat } from './useAgentReviewChat';

const prompt: AiChatReviewMessage = {
  id: 'user-1',
  sender: 'user',
  content: 'Why is this translation incorrect?',
};
const origin = {
  kind: 'ai_suggestion' as const,
  owner: { projectId: 7, textUnitId: 10, tmTextUnitId: 20, reviewStateRevision: 'revision-1' },
  aiRequestId: 'request-1',
};
const options = {
  username: 'translator',
  projectId: 7,
  textUnitId: 10,
  contextKey: 'source-and-translation-1',
  enabled: true,
};

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return {
    client,
    wrapper: ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    ),
  };
}

describe('incident review chat session', () => {
  it('retains messages when a decision refreshes the report and when returning to the page', async () => {
    const { wrapper, client } = setup();
    const { result, rerender, unmount } = renderHook(useAgentReviewChat, {
      initialProps: options,
      wrapper,
    });
    act(() => result.current.setMessages((messages) => [...messages, prompt]));
    await waitFor(() => expect(result.current.messages).toEqual([prompt]));
    rerender({ ...options });
    expect(result.current.messages).toEqual([prompt]);
    unmount();
    const revisited = renderHook(useAgentReviewChat, { initialProps: options, wrapper });
    expect(revisited.result.current.messages).toEqual([prompt]);
    client.clear();
  });

  it('isolates each row, project and reviewer while restoring the original conversation', async () => {
    const { wrapper, client } = setup();
    const { result, rerender } = renderHook(useAgentReviewChat, {
      initialProps: options,
      wrapper,
    });
    act(() => result.current.setMessages((messages) => [...messages, prompt]));
    await waitFor(() => expect(result.current.messages).toHaveLength(1));
    for (const scope of [
      { textUnitId: 11 },
      { projectId: 8 },
      { username: 'another-translator' },
    ]) {
      rerender({ ...options, ...scope });
      expect(result.current.messages).toEqual([]);
    }
    rerender(options);
    expect(result.current.messages).toEqual([prompt]);
    client.clear();
  });

  it('retains an inactive conversation through cache refetches without fetching or errors', async () => {
    const { wrapper, client } = setup();
    const { result, unmount } = renderHook(useAgentReviewChat, {
      initialProps: options,
      wrapper,
    });
    act(() => result.current.setMessages((messages) => [...messages, prompt]));
    await waitFor(() => expect(result.current.messages).toEqual([prompt]));
    unmount();
    await client.refetchQueries({ queryKey: ['agent-review-chat'], type: 'all' });
    const cached = client.getQueryCache().find({ queryKey: ['agent-review-chat'], exact: false });
    expect(cached?.state.error).toBeNull();
    expect(cached?.state.fetchStatus).toBe('idle');
    const revisited = renderHook(useAgentReviewChat, { initialProps: options, wrapper });
    expect(revisited.result.current.messages).toEqual([prompt]);
    client.clear();
  });

  it('keeps earlier replies readable but only enables suggestions from the current context', async () => {
    const { wrapper, client } = setup();
    const { result, rerender } = renderHook(useAgentReviewChat, {
      initialProps: options,
      wrapper,
    });
    const response: AiChatReviewMessage = {
      id: 'reply-1',
      sender: 'assistant',
      content: 'The source means enable.',
      suggestions: ownReviewProjectAiSuggestions([{ content: 'Activer' }], origin),
    };
    act(() => result.current.setMessages((messages) => [...messages, response]));
    await waitFor(() => expect(result.current.messages).toHaveLength(1));
    expect(result.current.isStaleSuggestion(response.suggestions![0])).toBe(false);
    expect(reviewProjectAiSuggestionOrigin(result.current.messages[0].suggestions![0])).toEqual(
      origin,
    );
    rerender({ ...options, contextKey: 'changed-source-translation-or-proposal' });
    expect(result.current.messages[0]).toBe(response);
    expect(result.current.isStaleSuggestion(response.suggestions![0])).toBe(true);
    const newOrigin = { ...origin, aiRequestId: 'request-2' };
    const newResponse = {
      ...response,
      id: 'reply-2',
      suggestions: ownReviewProjectAiSuggestions([{ content: 'Activer' }], newOrigin),
    };
    act(() => result.current.setMessages((messages) => [...messages, newResponse]));
    await waitFor(() => expect(result.current.messages).toHaveLength(2));
    expect(result.current.isStaleSuggestion(response.suggestions![0])).toBe(true);
    expect(result.current.isStaleSuggestion(newResponse.suggestions[0])).toBe(false);
    expect(reviewProjectAiSuggestionOrigin(result.current.messages[1].suggestions![0])).toEqual(
      newOrigin,
    );
    client.clear();
  });
});
