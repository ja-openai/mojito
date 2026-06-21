import { useEffect, useState } from 'react';

import {
  loadVisibleTextEditorEnabled,
  subscribeVisibleTextEditorPreference,
} from '../utils/visibleTextEditorPreference';
import { useUser } from './useUser';

export function useVisibleTextEditorEnabled(): boolean {
  const user = useUser();
  const username = user.username;
  const [enabled, setEnabled] = useState(() => loadVisibleTextEditorEnabled(username));

  useEffect(() => {
    setEnabled(loadVisibleTextEditorEnabled(username));
    return subscribeVisibleTextEditorPreference(username, () => {
      setEnabled(loadVisibleTextEditorEnabled(username));
    });
  }, [username]);

  return enabled;
}
