import { useParams } from 'react-router-dom';

import { useReviewProjectDetail } from '../../hooks/useReviewProjectDetail';
import { useReviewProjectMutations } from './review-project-mutations';
import { ReviewProjectPageView } from './ReviewProjectPageView';

export function ReviewProjectPage() {
  const { projectId: projectIdParam } = useParams<{ projectId: string }>();

  const parsedProjectId = projectIdParam ? Number(projectIdParam) : NaN;
  const projectId =
    Number.isFinite(parsedProjectId) && parsedProjectId > 0 ? parsedProjectId : undefined;
  const projectDetailQuery = useReviewProjectDetail(projectId);
  const mutationControls = useReviewProjectMutations(projectId);

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
