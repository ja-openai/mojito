import { useEffect, useState } from 'react';

import {
  loadVisibleTextEditorEnabled,
  subscribeVisibleTextEditorPreference,
} from '../utils/visibleTextEditorPreference';

export function useVisibleTextEditorEnabled(): boolean {
  const [enabled, setEnabled] = useState(() => loadVisibleTextEditorEnabled());

  useEffect(
    () =>
      subscribeVisibleTextEditorPreference(() => {
        setEnabled(loadVisibleTextEditorEnabled());
      }),
    [],
  );

  return enabled;
}
