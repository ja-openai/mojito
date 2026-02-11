import { useCallback } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';

import { useReviewProjectDetail } from '../../hooks/useReviewProjectDetail';
import { useReviewProjectMutations } from './review-project-mutations';
import { ReviewProjectPageView } from './ReviewProjectPageView';

export function ReviewProjectPage() {
  const { projectId: projectIdParam } = useParams<{ projectId: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const selectedTextUnitParam = searchParams.get('tu');
  const requestDetailsParam = searchParams.get('requestDetails');

  const parsedProjectId = projectIdParam ? Number(projectIdParam) : NaN;
  const projectId =
    Number.isFinite(parsedProjectId) && parsedProjectId > 0 ? parsedProjectId : undefined;
  const parsedSelectedTextUnitId = selectedTextUnitParam ? Number(selectedTextUnitParam) : NaN;
  const selectedTextUnitId =
    Number.isInteger(parsedSelectedTextUnitId) && parsedSelectedTextUnitId > 0
      ? parsedSelectedTextUnitId
      : null;
  const openRequestDetails = requestDetailsParam === '1';
  const projectDetailQuery = useReviewProjectDetail(projectId);
  const mutationControls = useReviewProjectMutations(projectId);

  const handleSelectedTextUnitIdChange = useCallback(
    (nextTextUnitId: number | null, options?: { replace?: boolean }) => {
      const nextParams = new URLSearchParams(searchParams);
      if (nextTextUnitId == null) {
        nextParams.delete('tu');
      } else {
        nextParams.set('tu', String(nextTextUnitId));
      }
      if (nextParams.toString() === searchParams.toString()) {
        return;
      }
      setSearchParams(nextParams, { replace: options?.replace ?? false });
    },
    [searchParams, setSearchParams],
  );

  const handleRequestDetailsQueryHandled = useCallback(() => {
    const nextParams = new URLSearchParams(searchParams);
    if (!nextParams.has('requestDetails')) {
      return;
    }
    nextParams.delete('requestDetails');
    setSearchParams(nextParams, { replace: true });
  }, [searchParams, setSearchParams]);

  if (projectId == null) {
    return <ErrorState message="Missing or invalid project id." />;
  }

  if (projectDetailQuery.isLoading) {
    return <LoadingState />;
  }

  if (projectDetailQuery.isError) {
    const message =
      projectDetailQuery.error instanceof Error
        ? projectDetailQuery.error.message
        : 'Failed to load project';
    return <ErrorState message={message} />;
  }

  return (
    <ReviewProjectPageView
      projectId={projectId}
      project={projectDetailQuery.data ?? null}
      mutations={mutationControls}
      selectedTextUnitQueryId={selectedTextUnitId}
      onSelectedTextUnitIdChange={handleSelectedTextUnitIdChange}
      openRequestDetailsQuery={openRequestDetails}
      onRequestDetailsQueryHandled={handleRequestDetailsQueryHandled}
    />
  );
}

function LoadingState() {
  return (
    <div className="review-project-page__state">
      <div className="spinner spinner--md" aria-hidden />
      <div>Loading project…</div>
    </div>
  );
}

function ErrorState({ message }: { message: string }) {
  return (
    <div className="review-project-page__state review-project-page__state--error">
      <div>{message}</div>
    </div>
  );
}
