import { skipToken, useQuery, useQueryClient } from '@tanstack/react-query';
import { type SetStateAction, useCallback, useMemo } from 'react';

import type { AiReviewSuggestion } from '../../api/ai-review';
import type { AiChatReviewMessage } from '../../components/AiChatReview';

type ConversationEntry = { contextKey: string; message: AiChatReviewMessage };
const EMPTY_ENTRIES: ConversationEntry[] = [];

/** Keep a row's conversation for this session, independently of its review status. */
export function useAgentReviewChat({
  username,
  projectId,
  textUnitId,
  contextKey,
  enabled,
}: {
  username: string;
  projectId: number;
  textUnitId: number;
  contextKey: string;
  enabled: boolean;
}) {
  const queryClient = useQueryClient();
  const queryKey = useMemo(
    () => ['agent-review-chat', username, projectId, textUnitId],
    [username, projectId, textUnitId],
  );
  const { data: entries = EMPTY_ENTRIES } = useQuery<ConversationEntry[]>({
    queryKey,
    queryFn: skipToken,
    initialData: EMPTY_ENTRIES,
    enabled: false,
    gcTime: enabled ? Infinity : 0,
    // Suggestion ownership is attached to object identity, including across row navigation.
    structuralSharing: false,
  });
  const messages = useMemo(() => entries.map((entry) => entry.message), [entries]);
  const setMessages = useCallback(
    (update: SetStateAction<AiChatReviewMessage[]>) => {
      queryClient.setQueryData<ConversationEntry[]>(queryKey, (cached = []) => {
        const previous = cached.map((entry) => entry.message);
        const next = typeof update === 'function' ? update(previous) : update;
        return next.map((message) => {
          const existing = cached.find((entry) => entry.message === message);
          return existing ?? { contextKey, message };
        });
      });
    },
    [contextKey, queryClient, queryKey],
  );
  const isStaleSuggestion = useCallback(
    (suggestion: AiReviewSuggestion) =>
      entries.some(
        (entry) =>
          entry.contextKey !== contextKey && entry.message.suggestions?.includes(suggestion),
      ),
    [contextKey, entries],
  );
  return { messages, setMessages, isStaleSuggestion };
}
