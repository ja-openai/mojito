import { useState } from 'react';

/** Keep feedback in place until the reviewer leaves this string or editing session. */
export function useReviewFeedbackVisibility(scope: string | null, shouldShow: boolean): boolean {
  const [seen, setSeen] = useState({ scope, visible: scope !== null && shouldShow });
  const visible = scope !== null && (shouldShow || (seen.scope === scope && seen.visible));
  if (seen.scope !== scope || seen.visible !== visible) setSeen({ scope, visible });
  return visible;
}
