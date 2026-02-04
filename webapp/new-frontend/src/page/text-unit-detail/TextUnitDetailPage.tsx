import '../review-project/review-project-page.css';
import './text-unit-detail-page.css';

import { useMemo } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';

type LocationState = {
  from?: string;
};

export function TextUnitDetailPage() {
  const { tmTextUnitId: tmTextUnitIdParam } = useParams<{ tmTextUnitId: string }>();
  const parsedTmTextUnitId = tmTextUnitIdParam ? Number(tmTextUnitIdParam) : NaN;
  const tmTextUnitId =
    Number.isFinite(parsedTmTextUnitId) && parsedTmTextUnitId > 0 ? parsedTmTextUnitId : null;

  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const locationState = (location.state as LocationState | null) ?? null;

  const details = useMemo(
    () => ({
      name: searchParams.get('name'),
      locale: searchParams.get('locale'),
      repository: searchParams.get('repository'),
    }),
    [searchParams],
  );

  const handleBack = () => {
    if (window.history.length > 1) {
      void navigate(-1);
      return;
    }
    void navigate(locationState?.from ?? '/workbench');
  };

  if (tmTextUnitId === null) {
    return (
      <div className="review-project-page__state review-project-page__state--error">
        <div>Missing or invalid text unit id.</div>
      </div>
    );
  }

  return (
    <div className="review-project-page text-unit-detail-page">
      <header className="review-project-page__header">
        <div className="review-project-page__header-row">
          <div className="review-project-page__header-group review-project-page__header-group--left">
            <button
              type="button"
              className="review-project-page__header-back-link"
              onClick={handleBack}
              aria-label="Back to workbench"
              title="Back to workbench"
            >
              <svg
                className="review-project-page__header-back-icon"
                viewBox="0 0 24 24"
                aria-hidden="true"
                focusable="false"
              >
                <path
                  d="M20 12H6m0 0l5-5m-5 5l5 5"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
            </button>
            <span className="review-project-page__header-name">Text unit {tmTextUnitId}</span>
          </div>
          <div className="review-project-page__header-group review-project-page__header-group--stats" />
          <div className="review-project-page__header-group review-project-page__header-group--meta" />
        </div>
      </header>

      <div className="text-unit-detail-page__content">
        <section className="text-unit-detail-page__panel">
          <h1 className="text-unit-detail-page__title">Text Unit Details</h1>
          <p className="text-unit-detail-page__subtitle">
            Initial scaffold. We can iterate on fields/layout next.
          </p>
          <dl className="text-unit-detail-page__meta">
            <div className="text-unit-detail-page__meta-row">
              <dt>ID</dt>
              <dd>{tmTextUnitId}</dd>
            </div>
            <div className="text-unit-detail-page__meta-row">
              <dt>Name</dt>
              <dd>{details.name ?? '—'}</dd>
            </div>
            <div className="text-unit-detail-page__meta-row">
              <dt>Locale</dt>
              <dd>{details.locale ?? '—'}</dd>
            </div>
            <div className="text-unit-detail-page__meta-row">
              <dt>Repository</dt>
              <dd>{details.repository ?? '—'}</dd>
            </div>
          </dl>
        </section>
      </div>
    </div>
  );
}
