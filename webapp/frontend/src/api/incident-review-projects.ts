import type { ApiReviewProjectType } from './review-projects';

export type ReviewSource = 'CURRENT_TRANSLATIONS' | 'INCIDENTS';

export type IncidentReviewProjectRequest = {
  allRepositories?: boolean;
  repositoryIds?: number[] | null;
  reviewFeatureIds?: number[] | null;
  localeTags: string[];
  reviewType?: string | null;
  teamId: number;
  name: string;
  dueDate: string;
  maxWordCountPerProject: number | null;
  assignTranslator: boolean;
  type: ApiReviewProjectType;
  notes: string | null;
  screenshotImageIds: string[];
};

export type IncidentReviewProjectResult = {
  eligibleIncidentCount: number;
  skippedIncidentCount: number;
  projectCount: number;
  localeTags: string[];
  projectIds: number[];
  requestIds: number[];
  skipped: Array<{ incidentId: number; reason: string }>;
  scannedIncidentCount?: number;
  hasMore?: boolean;
};

async function submitIncidentReviewRequest(
  path: string,
  request: IncidentReviewProjectRequest,
): Promise<IncidentReviewProjectResult> {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
  if (!response.ok) {
    const body = await response.text();
    let message = 'Unable to prepare incident review projects.';
    try {
      const error = JSON.parse(body) as { message?: string };
      if (typeof error.message === 'string') message = error.message;
    } catch {
      // A proxy error may not contain a JSON application response.
    }
    throw new Error(message);
  }
  return response.json() as Promise<IncidentReviewProjectResult>;
}

export function previewIncidentReviewProjects(request: IncidentReviewProjectRequest) {
  return submitIncidentReviewRequest('/api/incident-review-projects/preview', request);
}

export function createIncidentReviewProjects(request: IncidentReviewProjectRequest) {
  return submitIncidentReviewRequest('/api/incident-review-projects', request);
}
