import '../review-project/review-project-page.css';
import './review-project-find-replace-page.css';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';

import {
  type ApiReviewProjectDetail,
  deleteReviewProjectTextUnitSuggestion,
  REVIEW_PROJECT_TYPE_LABELS,
  saveReviewProjectTextUnitDecision,
  saveReviewProjectTextUnitSuggestion,
} from '../../api/review-projects';
import { FindReplaceBar } from '../../components/FindReplaceBar';
import { FloatingStatusMessage } from '../../components/FloatingStatusMessage';
import { LocalePill } from '../../components/LocalePill';
import { Pill } from '../../components/Pill';
import {
  TranslationTextEditor,
  type TranslationTextEditorKeyDownEvent,
} from '../../components/TranslationTextEditor';
import { getRowHeightPx } from '../../components/virtual/getRowHeightPx';
import { useMeasuredRowRefs } from '../../components/virtual/useMeasuredRowRefs';
import { useVirtualRows } from '../../components/virtual/useVirtualRows';
import { VirtualList } from '../../components/virtual/VirtualList';
import type { VisibleTextMarksMode } from '../../components/VisibleTextEditor';
import { useProtectedTextTokenGuard } from '../../hooks/useProtectedTextTokenGuard';
import { useReviewProjectDetail } from '../../hooks/useReviewProjectDetail';
import { useVisibleTextEditorEnabled } from '../../hooks/useVisibleTextEditorEnabled';
import {
  type FindReplaceMatchRange,
  getFindReplaceMatchRanges,
  replaceFindReplaceRange,
  validateFindReplaceOptions,
} from '../../utils/findReplace';
import { buildInlineDiffParts } from '../../utils/inlineDiff';
import { toHtmlLangTag } from '../../utils/localeTag';
import { formatIntegerCount } from '../../utils/numberFormat';
import {
  applyReviewProjectFindReplacePlan,
  buildReviewProjectFindReplacePlan,
  buildReviewProjectFindReplaceRow,
  buildReviewProjectFindReplaceRows,
  countChangedReviewProjectFindReplaceRows,
  getReviewProjectFindReplaceIntegrityIssues,
  getReviewProjectFindReplaceIntegrityMessage,
  getReviewProjectFindReplaceVisibleRows,
  isReviewProjectFindReplaceRowChanged,
  type ReviewProjectFindReplaceIntegrityIssue,
  type ReviewProjectFindReplaceOptions,
  type ReviewProjectFindReplacePlan,
  type ReviewProjectFindReplaceRow,
} from './review-project-find-replace';

type Checkpoint = {
  rows: ReviewProjectFindReplaceRow[];
};

type ReplacementMatch = {
  rowId: number;
  range: FindReplaceMatchRange;
};

type ApplyMode = 'STAGE_FOR_REVIEW' | 'ACCEPT_AND_DECIDE';

const DEFAULT_OPTIONS: ReviewProjectFindReplaceOptions = {
  findText: '',
  replaceText: '',
  matchCase: true,
  regex: false,
  wholeWord: false,
  preserveCase: false,
};
const EMPTY_ROWS: ReviewProjectFindReplaceRow[] = [];
const STATIC_VIRTUAL_FALLBACK_LIMIT = 100;

export function ReviewProjectFindReplacePage() {
  const { projectId: projectIdParam } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const parsedProjectId = projectIdParam ? Number(projectIdParam) : NaN;
  const projectId =
    Number.isFinite(parsedProjectId) && parsedProjectId > 0 ? parsedProjectId : undefined;
  const projectQuery = useReviewProjectDetail(projectId);
  const project = projectQuery.data ?? null;
  const initialRows = useMemo(
    () => (project ? buildReviewProjectFindReplaceRows(project) : []),
    [project],
  );
  const [options, setOptions] = useState<ReviewProjectFindReplaceOptions>(DEFAULT_OPTIONS);
  const [checkpoints, setCheckpoints] = useState<Checkpoint[]>(() => [buildCheckpoint([])]);
  const [projectStateRows, setProjectStateRows] = useState<ReviewProjectFindReplaceRow[]>([]);
  const [checkpointIndex, setCheckpointIndex] = useState(0);
  const [selectedMatchIndex, setSelectedMatchIndex] = useState(0);
  const [applyMode, setApplyMode] = useState<ApplyMode>('STAGE_FOR_REVIEW');
  const [isApplyingToProject, setIsApplyingToProject] = useState(false);
  const [isResettingProjectState, setIsResettingProjectState] = useState(false);
  const [applyToProjectMessage, setApplyToProjectMessage] = useState<string | null>(null);
  const [applyToProjectError, setApplyToProjectError] = useState<string | null>(null);
  const resultsScrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!project) {
      return;
    }
    setOptions(DEFAULT_OPTIONS);
    setProjectStateRows(initialRows);
    setCheckpoints([buildCheckpoint(initialRows)]);
    setCheckpointIndex(0);
    setSelectedMatchIndex(0);
    setApplyToProjectMessage(null);
    setApplyToProjectError(null);
  }, [initialRows, project]);

  const activeCheckpoint = checkpoints[checkpointIndex] ?? checkpoints[0];
  const rows = activeCheckpoint?.rows ?? EMPTY_ROWS;
  const validationError = validateFindReplaceOptions(options);
  const plan = useMemo(() => buildReviewProjectFindReplacePlan(rows, options), [options, rows]);
  const visibleRows = useMemo(
    () => getReviewProjectFindReplaceVisibleRows(rows, options),
    [options, rows],
  );
  const replacementMatches = useMemo<ReplacementMatch[]>(() => {
    if (!options.findText || validationError) {
      return [];
    }
    return rows.flatMap((row) =>
      getFindReplaceMatchRanges(row.workingTarget, options).map((range) => ({
        rowId: row.id,
        range,
      })),
    );
  }, [options, rows, validationError]);
  const selectedMatch = replacementMatches[selectedMatchIndex] ?? null;
  const selectedMatchRowId = selectedMatch?.rowId ?? null;
  const selectedReplacement = useMemo(() => {
    if (!selectedMatch) {
      return null;
    }
    const row = rows.find((candidate) => candidate.id === selectedMatch.rowId);
    if (!row) {
      return null;
    }
    const replacement = replaceFindReplaceRange(row.workingTarget, options, selectedMatch.range);
    const integrityMessage =
      replacement.count > 0
        ? getReviewProjectFindReplaceIntegrityMessage(row.workingTarget, replacement.value)
        : null;
    return {
      row,
      replacement,
      integrityMessage,
    };
  }, [options, rows, selectedMatch]);
  const changedRows = useMemo(() => countChangedReviewProjectFindReplaceRows(rows), [rows]);
  const changedRowList = useMemo(() => rows.filter(isReviewProjectFindReplaceRowChanged), [rows]);
  const stagedRowList = useMemo(() => rows.filter((row) => row.hasStagedSuggestion), [rows]);
  const integrityIssues = useMemo(() => getReviewProjectFindReplaceIntegrityIssues(rows), [rows]);
  const integrityIssuesByRowId = useMemo(
    () => new Map(integrityIssues.map((issue) => [issue.rowId, issue])),
    [integrityIssues],
  );
  const blockedTargetsByRowId = useMemo(
    () => new Map(plan.blockedTargets.map((issue) => [issue.rowId, issue])),
    [plan.blockedTargets],
  );
  const canReplaceAll = Boolean(options.findText) && !validationError && plan.targets.length > 0;
  const replaceAllDisabledReason = getApplyReplacementDisabledReason({
    hasFindText: Boolean(options.findText),
    validationError,
    plan,
  });
  const canReplaceCurrent =
    Boolean(options.findText) &&
    !validationError &&
    selectedReplacement != null &&
    selectedReplacement.replacement.count > 0 &&
    !selectedReplacement.integrityMessage;
  const replaceCurrentDisabledReason = getReplaceCurrentDisabledReason({
    hasFindText: Boolean(options.findText),
    validationError,
    replacementCount: selectedReplacement?.replacement.count ?? 0,
    integrityMessage: selectedReplacement?.integrityMessage ?? null,
    matchCount: replacementMatches.length,
  });
  const canApplyToProject =
    changedRowList.length > 0 &&
    integrityIssues.length === 0 &&
    !isApplyingToProject &&
    !isResettingProjectState;
  const applyToProjectDisabledReason = getApplyToProjectDisabledReason({
    changedRows: changedRowList.length,
    integrityIssues,
    isApplying: isApplyingToProject || isResettingProjectState,
  });
  const canUndo = checkpointIndex > 0;
  const canRedo = checkpointIndex < checkpoints.length - 1;
  const canResetProjectState =
    !isResettingProjectState &&
    (checkpointIndex > 0 || changedRows > 0 || stagedRowList.length > 0);

  useEffect(() => {
    setSelectedMatchIndex((current) => {
      if (replacementMatches.length === 0) {
        return 0;
      }
      return Math.min(current, replacementMatches.length - 1);
    });
  }, [replacementMatches.length]);

  const estimateResultRowSize = useCallback(
    () =>
      getRowHeightPx({
        element: resultsScrollRef.current,
        cssVariable: '--review-find-replace-row-height',
        defaultRem: 8.5,
      }),
    [],
  );
  const getResultRowKey = useCallback(
    (index: number) => visibleRows[index]?.row.id ?? index,
    [visibleRows],
  );
  const {
    items: virtualRows,
    totalSize,
    measureElement,
    scrollToIndex,
  } = useVirtualRows<HTMLDivElement>({
    count: visibleRows.length,
    estimateSize: estimateResultRowSize,
    getItemKey: getResultRowKey,
    getScrollElement: () => resultsScrollRef.current,
    overscan: 6,
  });
  const { getRowRef } = useMeasuredRowRefs<number, HTMLDivElement>({ measureElement });

  useEffect(() => {
    if (selectedMatchRowId == null) {
      return;
    }
    const selectedRowIndex = visibleRows.findIndex(({ row }) => row.id === selectedMatchRowId);
    if (selectedRowIndex === -1) {
      return;
    }
    scrollToIndex(selectedRowIndex, { align: 'center' });
  }, [scrollToIndex, selectedMatchRowId, visibleRows]);

  const updateWorkingTarget = useCallback(
    (rowId: number, nextTarget: string) => {
      setCheckpoints((current) => {
        const currentBranch = current.slice(0, checkpointIndex + 1);
        return currentBranch.map((checkpoint, index) =>
          index === checkpointIndex
            ? {
                ...checkpoint,
                rows: checkpoint.rows.map((row) =>
                  row.id === rowId ? { ...row, workingTarget: nextTarget } : row,
                ),
              }
            : checkpoint,
        );
      });
      setApplyToProjectMessage(null);
      setApplyToProjectError(null);
    },
    [checkpointIndex],
  );

  const replaceCurrentMatch = () => {
    if (!canReplaceCurrent || !selectedReplacement) {
      return;
    }

    const nextRows = rows.map((row) =>
      row.id === selectedReplacement.row.id
        ? { ...row, workingTarget: selectedReplacement.replacement.value }
        : row,
    );
    const nextCheckpoint = buildCheckpoint(nextRows);
    const nextCheckpointIndex = checkpointIndex + 1;
    setCheckpoints((current) => [...current.slice(0, nextCheckpointIndex), nextCheckpoint]);
    setCheckpointIndex(nextCheckpointIndex);
    setApplyToProjectMessage(null);
    setApplyToProjectError(null);
  };

  const replaceAllMatches = () => {
    if (!canReplaceAll) {
      return;
    }

    const nextRows = applyReviewProjectFindReplacePlan(rows, plan);
    const nextCheckpoint = buildCheckpoint(nextRows);
    const nextCheckpointIndex = checkpointIndex + 1;
    setCheckpoints((current) => [...current.slice(0, nextCheckpointIndex), nextCheckpoint]);
    setCheckpointIndex(nextCheckpointIndex);
    setApplyToProjectMessage(null);
    setApplyToProjectError(null);
  };

  const applyToReviewProject = async () => {
    if (!canApplyToProject || !project) {
      return;
    }

    setIsApplyingToProject(true);
    setApplyToProjectMessage(null);
    setApplyToProjectError(null);

    try {
      const locale = project.locale?.bcp47Tag ?? 'No locale';
      const savedRowsById = new Map<number, ReviewProjectFindReplaceRow>();
      for (const row of changedRowList) {
        const savedTextUnit =
          applyMode === 'STAGE_FOR_REVIEW'
            ? await saveReviewProjectTextUnitSuggestion({
                textUnitId: row.id,
                target: row.workingTarget,
                source: 'FIND_REPLACE',
                notes: null,
                previousTarget: row.originalTarget,
                expectedCurrentTmTextUnitVariantId: row.expectedCurrentTmTextUnitVariantId,
              })
            : await saveReviewProjectTextUnitDecision({
                textUnitId: row.id,
                target: row.workingTarget,
                comment: row.targetComment,
                status: 'APPROVED',
                includedInLocalizedFile: true,
                decisionState: 'DECIDED',
                expectedCurrentTmTextUnitVariantId: row.expectedCurrentTmTextUnitVariantId,
                decisionNotes: row.existingDecisionNotes,
              });
        savedRowsById.set(row.id, buildReviewProjectFindReplaceRow(savedTextUnit, locale));
      }

      const nextRows = rows.map((row) => savedRowsById.get(row.id) ?? row);
      const nextCheckpoint = buildCheckpoint(nextRows);
      const nextCheckpointIndex = checkpointIndex + 1;
      setCheckpoints((current) => [...current.slice(0, nextCheckpointIndex), nextCheckpoint]);
      setCheckpointIndex(nextCheckpointIndex);
      setProjectStateRows(nextRows);
      setApplyToProjectMessage(formatApplySuccessMessage(applyMode, savedRowsById.size));
      void navigate(`/review-projects/${project.id}`);
    } catch (error) {
      setApplyToProjectError(
        error instanceof Error ? error.message : 'Failed to apply changes to the review project.',
      );
    } finally {
      setIsApplyingToProject(false);
    }
  };

  const resetToProjectState = async () => {
    if (!project) {
      return;
    }
    setSelectedMatchIndex(0);
    setApplyToProjectMessage(null);
    setApplyToProjectError(null);

    if (stagedRowList.length === 0) {
      setCheckpoints([buildCheckpoint(projectStateRows)]);
      setCheckpointIndex(0);
      return;
    }

    setIsResettingProjectState(true);
    try {
      const locale = project.locale?.bcp47Tag ?? 'No locale';
      const clearedRowsById = new Map<number, ReviewProjectFindReplaceRow>();
      for (const row of stagedRowList) {
        const savedTextUnit = await deleteReviewProjectTextUnitSuggestion({ textUnitId: row.id });
        clearedRowsById.set(row.id, buildReviewProjectFindReplaceRow(savedTextUnit, locale));
      }

      const nextRows = projectStateRows.map((row) => clearedRowsById.get(row.id) ?? row);
      setProjectStateRows(nextRows);
      setCheckpoints([buildCheckpoint(nextRows)]);
      setCheckpointIndex(0);
      setApplyToProjectMessage(formatClearSuggestionsMessage(clearedRowsById.size));
    } catch (error) {
      setApplyToProjectError(
        error instanceof Error
          ? error.message
          : 'Failed to clear staged suggestions from the review project.',
      );
    } finally {
      setIsResettingProjectState(false);
    }
  };

  if (projectId == null) {
    return <PageState tone="error">Missing or invalid project id.</PageState>;
  }

  if (projectQuery.isLoading) {
    return <PageState>Loading project…</PageState>;
  }

  if (projectQuery.isError) {
    const message =
      projectQuery.error instanceof Error ? projectQuery.error.message : 'Failed to load project.';
    return <PageState tone="error">{message}</PageState>;
  }

  if (!project) {
    return <PageState tone="error">Project not found.</PageState>;
  }

  const projectName = project.reviewProjectRequest?.name?.trim() || `Review project #${project.id}`;
  const localeTag = project.locale?.bcp47Tag?.trim() ?? '';
  const headerWordCount = getProjectWordCount(project);
  const headerStringCount = getProjectStringCount(project, rows.length);
  const staticFallbackRows =
    virtualRows.length === 0
      ? visibleRows.slice(0, Math.min(visibleRows.length, STATIC_VIRTUAL_FALLBACK_LIMIT))
      : [];

  return (
    <div className="review-find-replace-page">
      <header className="review-project-page__header">
        <div className="review-project-page__header-row review-find-replace-page__header-row">
          <div className="review-project-page__header-group review-project-page__header-group--left">
            <Link
              className="review-project-page__header-back-link"
              to={`/review-projects/${project.id}`}
              aria-label="Back to review project"
              title="Back to review project"
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
            </Link>
            <h1 className="review-project-page__header-name">{projectName}</h1>
            <span className="review-find-replace-page__header-context" aria-current="page">
              <span aria-hidden="true">›</span>
              <span>Find and replace</span>
            </span>
            <span className="review-project-page__header-pills">
              <Pill
                className={`review-project-page__header-pill review-project-page__header-pill--type-${project.type}`}
              >
                {REVIEW_PROJECT_TYPE_LABELS[project.type]}
              </Pill>
            </span>
            <div className="review-project-page__header-locale-row">
              {localeTag ? (
                <LocalePill
                  bcp47Tag={localeTag}
                  displayName={localeTag}
                  labelMode="tag"
                  className="review-project-page__header-locale-pill"
                />
              ) : (
                <span className="review-project-page__header-muted">No locale</span>
              )}
            </div>
          </div>
          <div className="review-project-page__header-group review-project-page__header-group--stats review-find-replace-page__header-stats">
            <span className="review-project-page__header-count-line">
              <span className="review-project-page__header-count">
                {formatIntegerCount(headerWordCount)}
              </span>
              <span className="review-project-page__header-muted">&nbsp;words</span>
              <span className="review-project-page__header-count-sep">&nbsp;·&nbsp;</span>
              <span className="review-project-page__header-count">
                {formatIntegerCount(headerStringCount)}
              </span>
              <span className="review-project-page__header-muted">&nbsp;strings</span>
            </span>
          </div>
        </div>
      </header>

      <FindReplaceBar
        findText={options.findText}
        replaceText={options.replaceText}
        matchCase={options.matchCase}
        regex={Boolean(options.regex)}
        wholeWord={Boolean(options.wholeWord)}
        preserveCase={Boolean(options.preserveCase)}
        showRegex
        showWholeWord
        showPreserveCase
        actionPrefix={
          <MatchNavigation
            matchCount={replacementMatches.length}
            selectedIndex={selectedMatchIndex}
            onPrevious={() =>
              setSelectedMatchIndex((current) =>
                replacementMatches.length === 0
                  ? 0
                  : (current - 1 + replacementMatches.length) % replacementMatches.length,
              )
            }
            onNext={() =>
              setSelectedMatchIndex((current) =>
                replacementMatches.length === 0 ? 0 : (current + 1) % replacementMatches.length,
              )
            }
          />
        }
        secondarySubmitDisabled={!canReplaceCurrent}
        secondarySubmitLabel="Replace"
        secondarySubmitTitle={replaceCurrentDisabledReason}
        submitDisabled={!canReplaceAll}
        submitLabel="Replace all"
        submitTitle={replaceAllDisabledReason}
        actionSuffix={
          <div className="review-find-replace-page__toolbar-history-actions">
            <button
              type="button"
              className="review-find-replace-page__button"
              disabled={!canUndo}
              onClick={() => setCheckpointIndex((current) => Math.max(0, current - 1))}
            >
              Undo
            </button>
            <button
              type="button"
              className="review-find-replace-page__button"
              disabled={!canRedo}
              onClick={() =>
                setCheckpointIndex((current) => Math.min(checkpoints.length - 1, current + 1))
              }
            >
              Redo
            </button>
            <button
              type="button"
              className="review-find-replace-page__button"
              disabled={!canResetProjectState}
              onClick={() => void resetToProjectState()}
            >
              {isResettingProjectState ? 'Resetting…' : 'Reset'}
            </button>
          </div>
        }
        projectActions={
          <>
            <div
              className="review-find-replace-page__apply-mode"
              role="group"
              aria-label="Apply mode"
            >
              <button
                type="button"
                className={`review-find-replace-page__apply-mode-button${
                  applyMode === 'STAGE_FOR_REVIEW' ? ' is-active' : ''
                }`}
                aria-pressed={applyMode === 'STAGE_FOR_REVIEW'}
                disabled={isApplyingToProject}
                onClick={() => setApplyMode('STAGE_FOR_REVIEW')}
              >
                Stage for review
              </button>
              <button
                type="button"
                className={`review-find-replace-page__apply-mode-button${
                  applyMode === 'ACCEPT_AND_DECIDE' ? ' is-active' : ''
                }`}
                aria-pressed={applyMode === 'ACCEPT_AND_DECIDE'}
                disabled={isApplyingToProject}
                onClick={() => setApplyMode('ACCEPT_AND_DECIDE')}
              >
                Accept + decide
              </button>
            </div>
            <button
              type="button"
              className="review-find-replace-page__button review-find-replace-page__button--primary"
              disabled={!canApplyToProject}
              title={applyToProjectDisabledReason}
              onClick={() => void applyToReviewProject()}
            >
              {isApplyingToProject
                ? 'Applying…'
                : applyMode === 'ACCEPT_AND_DECIDE'
                  ? 'Accept changes'
                  : 'Stage in project'}
            </button>
          </>
        }
        classNames={{
          root: 'review-find-replace-page__toolbar',
          fields: 'review-find-replace-page__toolbar-fields',
          field: 'review-find-replace-page__field',
          fieldHeading: 'review-find-replace-page__field-heading',
          fieldControl: 'review-find-replace-page__field-control',
          clearButton: 'review-find-replace-page__clear-button',
          historyButton: 'review-find-replace-page__history-button',
          historyPanel: 'review-find-replace-page__history-panel',
          historyOption: 'review-find-replace-page__history-option',
          options: 'review-find-replace-page__toolbar-options',
          check: 'review-find-replace-page__check',
          actions: 'review-find-replace-page__toolbar-actions',
          actionPrefix: 'review-find-replace-page__match-nav',
          projectActions: 'review-find-replace-page__toolbar-project-actions',
          button: 'review-find-replace-page__button',
        }}
        onFindTextChange={(value) => setOptions((current) => ({ ...current, findText: value }))}
        onReplaceTextChange={(value) =>
          setOptions((current) => ({ ...current, replaceText: value }))
        }
        onMatchCaseChange={(value) => setOptions((current) => ({ ...current, matchCase: value }))}
        onWholeWordChange={(value) => setOptions((current) => ({ ...current, wholeWord: value }))}
        onRegexChange={(value) => setOptions((current) => ({ ...current, regex: value }))}
        onPreserveCaseChange={(value) =>
          setOptions((current) => ({ ...current, preserveCase: value }))
        }
        onSecondarySubmit={replaceCurrentMatch}
        onSubmit={replaceAllMatches}
      />

      {validationError ? (
        <div className="review-find-replace-page__alert" role="alert">
          {validationError}
        </div>
      ) : null}
      {!validationError && plan.blockedTargets.length > 0 ? (
        <div className="review-find-replace-page__alert" role="alert">
          {formatIntegerCount(plan.blockedTargets.length)} row
          {plan.blockedTargets.length === 1 ? '' : 's'} skipped because the replacement would change
          protected placeholders, markup, or platform syntax.
        </div>
      ) : null}
      {integrityIssues.length > 0 ? (
        <div className="review-find-replace-page__alert" role="alert">
          {formatIntegerCount(integrityIssues.length)} changed row
          {integrityIssues.length === 1 ? '' : 's'} need repair before saving because protected
          placeholders, markup, or platform syntax no longer match the project target.
        </div>
      ) : null}
      <FloatingStatusMessage
        message={applyToProjectError ?? applyToProjectMessage}
        kind={applyToProjectError ? 'error' : 'success'}
      />

      <main className="review-find-replace-page__results">
        <div className="review-find-replace-page__table-head">
          <span>String</span>
          <span>Source</span>
          <span>Current target</span>
          <span>Working target</span>
        </div>
        {visibleRows.length === 0 ? (
          <div className="review-find-replace-page__empty">
            {rows.length === 0
              ? 'This review project has no text units.'
              : 'No rows match this find text in the current working copy.'}
          </div>
        ) : staticFallbackRows.length > 0 ? (
          <div className="review-find-replace-page__virtual-fallback" ref={resultsScrollRef}>
            {staticFallbackRows.map(({ row, changed, currentMatchCount }) => (
              <ResultRow
                key={row.id}
                row={row}
                changed={changed}
                currentMatchCount={currentMatchCount}
                options={options}
                blockedIssue={blockedTargetsByRowId.get(row.id) ?? null}
                integrityIssue={integrityIssuesByRowId.get(row.id) ?? null}
                isSelectedMatch={row.id === selectedMatchRowId}
                onChangeWorkingTarget={updateWorkingTarget}
              />
            ))}
          </div>
        ) : (
          <VirtualList
            scrollRef={resultsScrollRef}
            items={virtualRows}
            totalSize={totalSize}
            renderRow={(virtualRow) => {
              const visibleRow = visibleRows[virtualRow.index];
              if (!visibleRow) {
                return null;
              }

              const { row, changed, currentMatchCount } = visibleRow;
              return {
                key: row.id,
                props: {
                  'data-review-find-replace-row-id': row.id,
                  ref: getRowRef(row.id),
                },
                content: (
                  <ResultRow
                    row={row}
                    changed={changed}
                    currentMatchCount={currentMatchCount}
                    options={options}
                    blockedIssue={blockedTargetsByRowId.get(row.id) ?? null}
                    integrityIssue={integrityIssuesByRowId.get(row.id) ?? null}
                    isSelectedMatch={row.id === selectedMatchRowId}
                    onChangeWorkingTarget={updateWorkingTarget}
                  />
                ),
              };
            }}
          />
        )}
      </main>
    </div>
  );
}

function getProjectWordCount(project: ApiReviewProjectDetail): number {
  if (typeof project.wordCount === 'number' && Number.isFinite(project.wordCount)) {
    return project.wordCount;
  }

  return (project.reviewProjectTextUnits ?? []).reduce((sum, textUnit) => {
    const wordCount = textUnit.tmTextUnit?.wordCount;
    return typeof wordCount === 'number' && Number.isFinite(wordCount) ? sum + wordCount : sum;
  }, 0);
}

function getProjectStringCount(project: ApiReviewProjectDetail, rowCount: number): number {
  if (typeof project.textUnitCount === 'number' && Number.isFinite(project.textUnitCount)) {
    return project.textUnitCount;
  }

  return rowCount;
}

function formatApplySuccessMessage(applyMode: ApplyMode, rowCount: number): string {
  const formattedRows = `${formatIntegerCount(rowCount)} changed row${rowCount === 1 ? '' : 's'}`;
  return applyMode === 'ACCEPT_AND_DECIDE'
    ? `Accepted and marked ${formattedRows} decided.`
    : `Staged ${formattedRows} for review.`;
}

function formatClearSuggestionsMessage(rowCount: number): string {
  return `Cleared ${formatIntegerCount(rowCount)} staged suggestion${rowCount === 1 ? '' : 's'}.`;
}

function ResultRow({
  row,
  changed,
  currentMatchCount,
  options,
  blockedIssue,
  integrityIssue,
  isSelectedMatch,
  onChangeWorkingTarget,
}: {
  row: ReviewProjectFindReplaceRow;
  changed: boolean;
  currentMatchCount: number;
  options: ReviewProjectFindReplaceOptions;
  blockedIssue: ReviewProjectFindReplaceIntegrityIssue | null;
  integrityIssue: ReviewProjectFindReplaceIntegrityIssue | null;
  isSelectedMatch: boolean;
  onChangeWorkingTarget: (rowId: number, nextTarget: string) => void;
}) {
  return (
    <div
      className={`review-find-replace-row${changed ? ' is-changed' : ''}${
        isSelectedMatch ? ' is-selected-match' : ''
      }`}
    >
      <div className="review-find-replace-row__meta">
        <strong>{row.name}</strong>
        <span>
          #{row.textUnitId ?? row.id}
          {row.assetPath ? ` · ${row.assetPath}` : ''}
        </span>
        <span>{formatResultStatus({ changed, currentMatchCount })}</span>
      </div>
      <div className="review-find-replace-row__text">
        <strong>{row.source || 'No source'}</strong>
        {row.sourceComment ? <span>{row.sourceComment}</span> : null}
      </div>
      <div className="review-find-replace-row__target">
        {changed ? (
          <InlineReviewDiff
            current={row.originalTarget}
            working={row.workingTarget}
            mode="old"
            emptyLabel="No current target"
          />
        ) : (
          <HighlightedText value={row.originalTarget || 'No current target'} options={options} />
        )}
      </div>
      <div className="review-find-replace-row__target review-find-replace-row__target--working">
        <WorkingTargetEditor
          row={row}
          showDiff={changed}
          integrityIssue={integrityIssue}
          blockedIssue={blockedIssue}
          onChangeWorkingTarget={onChangeWorkingTarget}
        />
      </div>
    </div>
  );
}

function MatchNavigation({
  matchCount,
  selectedIndex,
  onPrevious,
  onNext,
}: {
  matchCount: number;
  selectedIndex: number;
  onPrevious: () => void;
  onNext: () => void;
}) {
  return (
    <>
      <span className="review-find-replace-page__match-count">
        {matchCount === 0
          ? '0 / 0'
          : `${formatIntegerCount(selectedIndex + 1)} / ${formatIntegerCount(matchCount)}`}
      </span>
      <button
        type="button"
        className="review-find-replace-page__icon-button"
        disabled={matchCount === 0}
        aria-label="Previous match"
        onClick={onPrevious}
      >
        ↑
      </button>
      <button
        type="button"
        className="review-find-replace-page__icon-button"
        disabled={matchCount === 0}
        aria-label="Next match"
        onClick={onNext}
      >
        ↓
      </button>
    </>
  );
}

function WorkingTargetEditor({
  row,
  showDiff,
  integrityIssue,
  blockedIssue,
  onChangeWorkingTarget,
}: {
  row: ReviewProjectFindReplaceRow;
  showDiff: boolean;
  integrityIssue: ReviewProjectFindReplaceIntegrityIssue | null;
  blockedIssue: ReviewProjectFindReplaceIntegrityIssue | null;
  onChangeWorkingTarget: (rowId: number, nextTarget: string) => void;
}) {
  const isVisibleTextEditorEnabled = useVisibleTextEditorEnabled();
  const [marksMode, setMarksMode] = useState<VisibleTextMarksMode>('auto');
  const tokenGuard = useProtectedTextTokenGuard(
    row.workingTarget,
    isVisibleTextEditorEnabled ? 'icu-html' : 'none',
  );
  const warning = integrityIssue ?? blockedIssue;
  const handleKeyDown = (event: TranslationTextEditorKeyDownEvent) => {
    if (event.key === 'Escape') {
      const target = event.currentTarget;
      if (target instanceof HTMLElement) {
        target.blur();
      }
      event.stopPropagation();
    }
  };

  return (
    <div className="review-find-replace-row__working-editor">
      {showDiff ? (
        <>
          <div className="review-find-replace-row__working-source">
            <Pill className="review-find-replace-row__source-pill">From find/replace</Pill>
          </div>
          <div className="review-find-replace-row__diff-preview">
            <InlineReviewDiff
              current={row.originalTarget}
              working={row.workingTarget}
              mode="new"
              emptyLabel="Empty working target"
            />
          </div>
        </>
      ) : null}
      <TranslationTextEditor
        assisted={isVisibleTextEditorEnabled}
        ariaLabel={`Working target ${row.name}`}
        className={`review-find-replace-row__editor${
          integrityIssue ? ' review-find-replace-row__editor--invalid' : ''
        }`}
        controlBar={
          isVisibleTextEditorEnabled
            ? {
                marksMode,
                onChangeMarksMode: setMarksMode,
                protectedTokenCount: tokenGuard.protectedTokens.length,
              }
            : undefined
        }
        lang={toHtmlLangTag(row.locale)}
        marksMode={marksMode}
        maxRows={8}
        minRows={1}
        onChange={(nextTarget) => onChangeWorkingTarget(row.id, nextTarget)}
        onKeyDown={handleKeyDown}
        placeholder="Working target translation"
        protectedDiagnostics={tokenGuard.diagnostics}
        protectedTokens={tokenGuard.protectedTokens}
        spellCheck={true}
        validateNextValue={isVisibleTextEditorEnabled ? tokenGuard.validateNextValue : undefined}
        value={row.workingTarget}
      />
      {warning ? <div className="review-find-replace-row__warning">{warning.message}</div> : null}
    </div>
  );
}

function InlineReviewDiff({
  current,
  working,
  mode,
  emptyLabel,
}: {
  current: string;
  working: string;
  mode: 'old' | 'new';
  emptyLabel: string;
}) {
  const parts = buildInlineDiffParts(current, working, mode);
  if (parts.length === 0) {
    return <span className="review-find-replace-row__target-empty">{emptyLabel}</span>;
  }
  return (
    <>
      {parts.map((part, index) =>
        part.kind === 'same' ? (
          <span key={`${part.kind}-${index}`}>{part.value}</span>
        ) : (
          <mark
            key={`${part.kind}-${index}`}
            className={`review-find-replace-row__diff-part is-${part.kind}`}
          >
            {part.value}
          </mark>
        ),
      )}
    </>
  );
}

function HighlightedText({
  value,
  options,
}: {
  value: string;
  options: ReviewProjectFindReplaceOptions;
}) {
  const parts = splitHighlightedText(value, options);
  return (
    <>
      {parts.map((part, index) =>
        part.match ? (
          <mark key={`${part.text}-${index}`}>{part.text}</mark>
        ) : (
          <span key={`${part.text}-${index}`}>{part.text}</span>
        ),
      )}
    </>
  );
}

function PageState({ children, tone }: { children: string; tone?: 'error' }) {
  return (
    <div className={`review-find-replace-page__state${tone === 'error' ? ' is-error' : ''}`}>
      {children}
    </div>
  );
}

function buildCheckpoint(rows: ReviewProjectFindReplaceRow[]): Checkpoint {
  return {
    rows,
  };
}

function formatResultStatus({
  changed,
  currentMatchCount,
}: {
  changed: boolean;
  currentMatchCount: number;
}) {
  const parts = [];
  if (currentMatchCount > 0) {
    parts.push(
      `${formatIntegerCount(currentMatchCount)} match${currentMatchCount === 1 ? '' : 'es'}`,
    );
  }
  if (changed) {
    parts.push('Changed');
  }
  return parts.join(' · ') || 'No current match';
}

function getReplaceCurrentDisabledReason({
  hasFindText,
  validationError,
  replacementCount,
  integrityMessage,
  matchCount,
}: {
  hasFindText: boolean;
  validationError?: string | null;
  replacementCount: number;
  integrityMessage: string | null;
  matchCount: number;
}) {
  if (validationError) {
    return validationError;
  }
  if (!hasFindText) {
    return 'Enter text to find in the working target.';
  }
  if (matchCount === 0) {
    return 'No matches left in the working target.';
  }
  if (integrityMessage) {
    return integrityMessage;
  }
  if (replacementCount === 0) {
    return 'The selected match already equals the replacement.';
  }
  return undefined;
}

function getApplyReplacementDisabledReason({
  hasFindText,
  validationError,
  plan,
}: {
  hasFindText: boolean;
  validationError?: string | null;
  plan: ReviewProjectFindReplacePlan;
}) {
  if (validationError) {
    return validationError;
  }
  if (!hasFindText) {
    return 'Enter text to find in the working target.';
  }
  if (plan.targets.length > 0) {
    return undefined;
  }
  if (plan.blockedTargets.length > 0) {
    return 'All matching working-copy rows are blocked by protected syntax checks.';
  }
  return 'No matches left in the working target. Undo or reset to search the original project targets again.';
}

function getApplyToProjectDisabledReason({
  changedRows,
  integrityIssues,
  isApplying,
}: {
  changedRows: number;
  integrityIssues: ReviewProjectFindReplaceIntegrityIssue[];
  isApplying: boolean;
}) {
  if (isApplying) {
    return 'Applying changes to the review project.';
  }
  if (integrityIssues.length > 0) {
    return 'Repair protected placeholders, markup, or platform syntax before applying.';
  }
  if (changedRows === 0) {
    return 'No working-copy changes to apply to the review project.';
  }
  return undefined;
}

function splitHighlightedText(
  value: string,
  options: ReviewProjectFindReplaceOptions,
): Array<{ text: string; match: boolean }> {
  if (!options.findText) {
    return [{ text: value, match: false }];
  }

  const ranges = getFindReplaceMatchRanges(value, options);
  const parts: Array<{ text: string; match: boolean }> = [];
  let lastIndex = 0;

  for (const range of ranges) {
    if (range.start > lastIndex) {
      parts.push({ text: value.slice(lastIndex, range.start), match: false });
    }
    parts.push({ text: value.slice(range.start, range.end), match: true });
    lastIndex = range.end;
  }

  if (lastIndex < value.length) {
    parts.push({ text: value.slice(lastIndex), match: false });
  }

  return parts.length > 0 ? parts : [{ text: value, match: false }];
}
