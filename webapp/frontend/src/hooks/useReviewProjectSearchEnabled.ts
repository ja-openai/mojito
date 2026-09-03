import { useCallback, useSyncExternalStore } from 'react';

import {
  loadReviewProjectSearchEnabled,
  subscribeReviewProjectSearchPreference,
} from '../utils/reviewProjectSearchPreference';
import { useUser } from './useUser';

export function useReviewProjectSearchEnabled(): boolean {
  const { username } = useUser();
  const subscribe = useCallback(
    (listener: () => void) => subscribeReviewProjectSearchPreference(username, listener),
    [username],
  );
  const getSnapshot = useCallback(() => loadReviewProjectSearchEnabled(username), [username]);
  return useSyncExternalStore(subscribe, getSnapshot, () => false);
}
