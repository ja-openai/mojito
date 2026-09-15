import { useLayoutEffect } from 'react';

import type { ReviewFeedbackReason } from '../../api/review-feedback';
import type { ReviewProjectActionState } from './review-project-mutations';
import { useReviewProjectFormDraft } from './useReviewProjectFormDraft';

const EMPTY = {
  reason: '' as ReviewFeedbackReason | '',
  aiSuggestionUsed: false,
  // A Report explanation can justify keeping the original; an edit note cannot.
  noteFromEdit: false,
};
export function useReviewEditFeedback(
  username: string,
  projectId: number,
  textUnitId: number,
  action: ReviewProjectActionState,
) {
  const draft = useReviewProjectFormDraft(username, projectId, textUnitId, 'edit-feedback', EMPTY);
  const { finishOperation, cancelOperation } = draft;
  useLayoutEffect(() => {
    if (action.phase === 'idle') cancelOperation();
    else if (
      action.phase === 'succeeded' &&
      action.textUnit.id === textUnitId &&
      'reviewFeedback' in action.action.request
    )
      finishOperation(action.operationId, EMPTY);
  }, [action, textUnitId, finishOperation, cancelOperation]);
  return draft;
}
