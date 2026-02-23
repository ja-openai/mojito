import '../../components/chip-dropdown.css';
import './review-projects-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type VirtualItem } from '@tanstack/react-virtual';
import { type MouseEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';

import type { ApiReviewProjectStatus, ApiReviewProjectType } from '../../api/review-projects';
import {
  REVIEW_PROJECT_STATUS_LABELS,
  REVIEW_PROJECT_TYPE_LABELS,
  updateReviewProjectAssignment,
} from '../../api/review-projects';
import { fetchTeamLocalePools, fetchTeamPmPool, fetchTeamTranslators } from '../../api/teams';
import { type ApiUser, fetchAllUsersAdmin } from '../../api/users';
import {
  type FilterOption,
  MultiSectionFilterChip,
} from '../../components/filters/MultiSectionFilterChip';
import { LocaleMultiSelect, type LocaleOption } from '../../components/LocaleMultiSelect';
import { LocalePill } from '../../components/LocalePill';
import { Modal } from '../../components/Modal';
import { Pill } from '../../components/Pill';
import { SearchControl } from '../../components/SearchControl';
import { SingleSelectDropdown } from '../../components/SingleSelectDropdown';
import { getRowHeightPx } from '../../components/virtual/getRowHeightPx';
import { useMeasuredRowRefs } from '../../components/virtual/useMeasuredRowRefs';
import { useVirtualRows } from '../../components/virtual/useVirtualRows';
import { VirtualList } from '../../components/virtual/VirtualList';
import {
  REVIEW_PROJECT_REQUESTS_QUERY_KEY,
  REVIEW_PROJECTS_QUERY_KEY,
} from '../../hooks/useReviewProjects';
import { getStandardDateQuickRanges } from '../../utils/dateQuickRanges';
import { useLocaleDisplayNameResolver } from '../../utils/localeDisplayNames';
import { REVIEW_PROJECTS_SESSION_QUERY_KEY } from './review-projects-session-state';

type FiltersProps = {
  localeOptions: LocaleOption[];
  selectedLocaleTags: string[];
  onLocaleChange: (value: string[]) => void;
  myLocaleTags?: string[];
  typeOptions: FilterOption<ApiReviewProjectType | 'all'>[];
  typeValue: ApiReviewProjectType | 'all';
  onTypeChange: (value: ApiReviewProjectType | 'all') => void;
  statusOptions: FilterOption<ApiReviewProjectStatus | 'all'>[];
  statusValue: ApiReviewProjectStatus | 'all';
  onStatusChange: (value: ApiReviewProjectStatus | 'all') => void;
  limitOptions: FilterOption<number>[];
  limitValue: number;
  onLimitChange: (value: number) => void;
  createdAfter: string | null;
  createdBefore: string | null;
  onChangeCreatedAfter: (value: string | null) => void;
  onChangeCreatedBefore: (value: string | null) => void;
  dueAfter: string | null;
  dueBefore: string | null;
  onChangeDueAfter: (value: string | null) => void;
  onChangeDueBefore: (value: string | null) => void;
  searchQuery: string;
  onSearchChange: (value: string) => void;
  searchField: 'name' | 'id' | 'requestId' | 'createdBy';
  onSearchFieldChange: (value: 'name' | 'id' | 'requestId' | 'createdBy') => void;
  searchType: 'contains' | 'exact' | 'ilike';
  onSearchTypeChange: (value: 'contains' | 'exact' | 'ilike') => void;
  creatorFilter: 'all' | 'mine';
  onCreatorFilterChange: (value: 'all' | 'mine') => void;
};

export type ReviewProjectRow = {
  id: number;
  name: string;
  requestId: number | null;
  requestCreatedByUsername: string | null;
  teamId: number | null;
  teamName: string | null;
  assignedPmUserId: number | null;
  assignedPmUsername: string | null;
  assignedTranslatorUserId: number | null;
  assignedTranslatorUsername: string | null;
  type: ApiReviewProjectType;
  status: ApiReviewProjectStatus;
  localeTag: string | null;
  acceptedCount: number;
  textUnitCount: number | null;
  wordCount: number | null;
  dueDate: string | null;
  closeReason?: string | null;
};

export type ReviewProjectsAdminControls = {
  enabled: boolean;
  selectedProjectIds: number[];
  onToggleProjectSelection: (projectId: number) => void;
  onSetProjectSelection?: (projectIds: number[], selected: boolean) => void;
  onSelectAllVisible: () => void;
  onClearSelection: () => void;
  onBatchStatus: (status: ApiReviewProjectStatus) => void;
  onBatchDelete: () => void;
  isSaving: boolean;
  errorMessage?: string | null;
};

type Props = {
  status: 'loading' | 'error' | 'ready';
  errorMessage?: string;
  errorOnRetry?: () => void;
  onLoadMock?: () => void;
  projects: ReviewProjectRow[];
  requestGroups?: ReviewProjectRequestGroupRow[];
  filters: FiltersProps;
  requestFilter?: { requestId: number | null; onClear: () => void };
  onRequestIdClick?: (requestId: number) => void;
  canCreate?: boolean;
  adminControls?: ReviewProjectsAdminControls;
  displayMode?: 'list' | 'requests';
  canUseRequestMode?: boolean;
  onDisplayModeChange?: (mode: 'list' | 'requests') => void;
  expandedRequestKey?: string | null;
  onExpandedRequestKeyChange?: (key: string | null) => void;
  reviewProjectsSessionKey?: string | null;
};

function buildReviewProjectDetailPath(
  projectId: number,
  reviewProjectsSessionKey?: string | null,
  options?: { requestDetails?: boolean; requestSource?: 'list' },
) {
  const params = new URLSearchParams();
  if (reviewProjectsSessionKey) {
    params.set(REVIEW_PROJECTS_SESSION_QUERY_KEY, reviewProjectsSessionKey);
  }
  if (options?.requestDetails) {
    params.set('requestDetails', '1');
  }
  if (options?.requestSource) {
    params.set('requestFrom', options.requestSource);
  }
  const query = params.toString();
  return query.length > 0
    ? `/review-projects/${projectId}?${query}`
    : `/review-projects/${projectId}`;
}

function CountsInline({ words, strings }: { words: number | null; strings: number | null }) {
  return (
    <div className="review-projects-page__count-line">
      <span className="review-projects-page__count">{formatNumber(words)}</span>
      <span className="review-projects-page__muted">&nbsp;words</span>
      <span className="review-projects-page__count-sep">&nbsp;·&nbsp;</span>
      <span className="review-projects-page__count">{formatNumber(strings)}</span>
      <span className="review-projects-page__muted">&nbsp;strings</span>
    </div>
  );
}

function formatSummaryText(resultCount: number) {
  if (resultCount === 0) {
    return 'No results';
  }
  const noun = resultCount === 1 ? 'result' : 'results';
  return `Showing ${resultCount} ${noun}`;
}

function SummaryBar({
  resultCount,
  totalWords,
  totalStrings,
  showModeToggle = false,
  displayMode = 'list',
  onDisplayModeChange,
}: {
  resultCount: number;
  totalWords: number;
  totalStrings: number;
  showModeToggle?: boolean;
  displayMode?: 'list' | 'requests';
  onDisplayModeChange?: (mode: 'list' | 'requests') => void;
}) {
  return (
    <div className="review-projects-page__summary-bar">
      <div className="review-projects-page__summary-left">{formatSummaryText(resultCount)}</div>
      <div className="review-projects-page__summary-center">
        {showModeToggle ? (
          <DisplayModeToggle mode={displayMode} onChange={(mode) => onDisplayModeChange?.(mode)} />
        ) : null}
      </div>
      <div className="review-projects-page__summary-right">
        <CountsInline words={totalWords} strings={totalStrings} />
      </div>
    </div>
  );
}

function AdminBar({
  adminControls,
  visibleCount,
}: {
  adminControls: ReviewProjectsAdminControls;
  visibleCount: number;
}) {
  const selectedCount = adminControls.selectedProjectIds.length;
  const hasSelection = selectedCount > 0;
  const disabled = adminControls.isSaving;

  return (
    <div className="review-projects-page__admin-bar" role="region" aria-label="Admin actions">
      <div className="review-projects-page__admin-left">
        <span className="review-projects-page__admin-count">
          {hasSelection ? `${selectedCount} selected` : 'No selection'}
        </span>
        <button
          type="button"
          className="review-projects-page__admin-link"
          onClick={adminControls.onSelectAllVisible}
          disabled={visibleCount === 0 || disabled}
        >
          Select all visible
        </button>
        <button
          type="button"
          className="review-projects-page__admin-link"
          onClick={adminControls.onClearSelection}
          disabled={!hasSelection || disabled}
        >
          Clear
        </button>
      </div>
      <div className="review-projects-page__admin-actions">
        <button
          type="button"
          className="review-projects-page__admin-button"
          onClick={() => adminControls.onBatchStatus('CLOSED')}
          disabled={!hasSelection || disabled}
        >
          Close
        </button>
        <button
          type="button"
          className="review-projects-page__admin-button"
          onClick={() => adminControls.onBatchStatus('OPEN')}
          disabled={!hasSelection || disabled}
        >
          Reopen
        </button>
        <button
          type="button"
          className="review-projects-page__admin-button review-projects-page__admin-button--danger"
          onClick={adminControls.onBatchDelete}
          disabled={!hasSelection || disabled}
        >
          Delete
        </button>
      </div>
      {adminControls.errorMessage ? (
        <div className="review-projects-page__admin-error">{adminControls.errorMessage}</div>
      ) : null}
    </div>
  );
}

function ContentSection({
  projects,
  rowsParentRef,
  virtualItems,
  totalSize,
  getRowRef,
  adminControls,
  onRequestIdClick,
  reviewProjectsSessionKey,
}: {
  projects: ReviewProjectRow[];
  rowsParentRef: React.RefObject<HTMLDivElement>;
  virtualItems: VirtualItem[];
  totalSize: number;
  getRowRef: (rowId: number) => (element: HTMLDivElement | null) => void;
  adminControls?: ReviewProjectsAdminControls;
  onRequestIdClick?: (requestId: number) => void;
  reviewProjectsSessionKey?: string | null;
}) {
  const isAdmin = adminControls?.enabled ?? false;
  const selectedProjectIdSet = useMemo(
    () => new Set(adminControls?.selectedProjectIds ?? []),
    [adminControls?.selectedProjectIds],
  );

  if (projects.length === 0) {
    return (
      <div className="review-projects-page__rows-frame">
        <div className="review-projects-page__rows review-projects-page__rows--empty">
          <EmptyState />
        </div>
      </div>
    );
  }

  return (
    <div className="review-projects-page__rows-frame">
      <div className="review-projects-page__rows-shell">
        <VirtualList
          scrollRef={rowsParentRef}
          items={virtualItems}
          totalSize={totalSize}
          renderRow={(virtualRow: VirtualItem) => {
            const project = projects[virtualRow.index];
            if (!project) {
              return null;
            }
            return {
              key: project.id,
              props: {
                ref: getRowRef(project.id),
              },
              content: (
                <ReviewProjectRowView
                  project={project}
                  isAdmin={isAdmin}
                  isSelected={selectedProjectIdSet.has(project.id)}
                  onToggleSelection={adminControls?.onToggleProjectSelection}
                  onRequestIdClick={onRequestIdClick}
                  reviewProjectsSessionKey={reviewProjectsSessionKey}
                />
              ),
            };
          }}
        />
      </div>
    </div>
  );
}

const formatDateTime = (value: string | null) => {
  if (!value) {
    return '—';
  }
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return value;
  }
  return parsed.toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
  });
};

const formatNumber = (value: number | null) => {
  if (value == null) {
    return '—';
  }
  return value.toLocaleString();
};

const toFiniteNonNegative = (value: unknown) => {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    return 0;
  }
  return parsed < 0 ? 0 : parsed;
};

const getAverageCount = (values: Array<number | null | undefined>) => {
  if (values.length === 0) {
    return 0;
  }
  const total = values.reduce<number>((sum, value) => sum + toFiniteNonNegative(value ?? 0), 0);
  return Math.round(total / values.length);
};

const getProgressMetrics = (acceptedValue: unknown, totalValue: unknown) => {
  const accepted = toFiniteNonNegative(acceptedValue);
  const total = toFiniteNonNegative(totalValue);
  const rawPercent = total === 0 ? 0 : (accepted / total) * 100;
  const percentValue = Math.max(0, Math.min(100, rawPercent));
  const roundedPercent = Math.round(percentValue);
  const percentWidth =
    accepted > 0 && percentValue > 0 && percentValue < 1 ? '2px' : `${percentValue}%`;
  return {
    accepted,
    total,
    percentValue,
    percentWidth,
    percentLabel: `${roundedPercent}%`,
  };
};

const getLanguageCompletionMetrics = (projects: ReviewProjectRow[]) => {
  const totalLanguages = projects.length;
  const completedLanguages = projects.reduce((sum, project) => {
    const accepted = toFiniteNonNegative(project.acceptedCount);
    const total = toFiniteNonNegative(project.textUnitCount ?? 0);
    return sum + (total === 0 || accepted >= total ? 1 : 0);
  }, 0);
  const rawPercent = totalLanguages === 0 ? 0 : (completedLanguages / totalLanguages) * 100;
  const percentValue = Math.max(0, Math.min(100, rawPercent));
  const percentWidth =
    completedLanguages > 0 && percentValue > 0 && percentValue < 1 ? '2px' : `${percentValue}%`;
  return {
    completedLanguages,
    totalLanguages,
    percentWidth,
    completionLabel: `${completedLanguages}/${totalLanguages}`,
  };
};

const TOGGLE_IGNORE_SELECTOR =
  'a,button,input,select,textarea,label,[role="button"],[role="link"],[data-no-toggle="true"]';

export type ReviewProjectRequestGroupRow = {
  key: string;
  requestId: number | null;
  name: string;
  createdByUsername: string | null;
  assignedPmUsername: string | null;
  localeTags: string[];
  acceptedCount: number;
  textUnitCount: number;
  wordCount: number;
  dueDate: string | null;
  projects: ReviewProjectRow[];
};

function getRequestGroupTypeBadge(group: ReviewProjectRequestGroupRow): {
  label: string;
  className: string;
} {
  const uniqueTypes = Array.from(new Set(group.projects.map((project) => project.type)));
  if (uniqueTypes.length === 1) {
    const type = uniqueTypes[0];
    return {
      label: REVIEW_PROJECT_TYPE_LABELS[type] ?? REVIEW_PROJECT_TYPE_LABELS.NORMAL,
      className:
        type === 'EMERGENCY'
          ? 'review-projects-page__type-pill--emergency'
          : 'review-projects-page__type-pill--default',
    };
  }
  return {
    label: 'Mixed',
    className: 'review-projects-page__type-pill--default',
  };
}

function buildRequestGroups(projects: ReviewProjectRow[]): ReviewProjectRequestGroupRow[] {
  const groups = new Map<string, ReviewProjectRequestGroupRow>();
  const dueByKey = new Map<string, number | null>();
  for (const project of projects) {
    const acceptedCount = toFiniteNonNegative(project.acceptedCount);
    const textUnitCount = toFiniteNonNegative(project.textUnitCount ?? 0);
    const wordCount = toFiniteNonNegative(project.wordCount ?? 0);
    const key =
      project.requestId != null ? `request:${project.requestId}` : `project:${project.id}`;
    const existing = groups.get(key);
    if (!existing) {
      groups.set(key, {
        key,
        requestId: project.requestId,
        name: project.name,
        createdByUsername: project.requestCreatedByUsername ?? null,
        assignedPmUsername: project.assignedPmUsername ?? null,
        localeTags: project.localeTag ? [project.localeTag] : [],
        acceptedCount,
        textUnitCount,
        wordCount,
        dueDate: project.dueDate ?? null,
        projects: [project],
      });
      dueByKey.set(key, project.dueDate ? Date.parse(project.dueDate) : null);
      continue;
    }

    existing.projects.push(project);
    existing.acceptedCount += acceptedCount;
    existing.textUnitCount += textUnitCount;
    existing.wordCount += wordCount;
    if (!existing.createdByUsername && project.requestCreatedByUsername) {
      existing.createdByUsername = project.requestCreatedByUsername;
    }
    if (project.assignedPmUsername) {
      if (!existing.assignedPmUsername) {
        existing.assignedPmUsername = project.assignedPmUsername;
      } else if (
        existing.assignedPmUsername !== 'Multiple' &&
        existing.assignedPmUsername !== project.assignedPmUsername
      ) {
        existing.assignedPmUsername = 'Multiple';
      }
    }
    if (project.localeTag && !existing.localeTags.includes(project.localeTag)) {
      existing.localeTags.push(project.localeTag);
    }
    const existingDueMs = dueByKey.get(key) ?? null;
    const nextDueMs = project.dueDate ? Date.parse(project.dueDate) : null;
    if (nextDueMs != null && !Number.isNaN(nextDueMs)) {
      if (existingDueMs == null || nextDueMs < existingDueMs) {
        dueByKey.set(key, nextDueMs);
        existing.dueDate = project.dueDate;
      }
    }
  }

  return Array.from(groups.values())
    .map((group) => ({
      ...group,
      localeTags: [...group.localeTags].sort((a, b) => a.localeCompare(b)),
      projects: [...group.projects].sort((a, b) =>
        (a.localeTag ?? '').localeCompare(b.localeTag ?? ''),
      ),
    }))
    .sort((a, b) => {
      if (a.requestId != null && b.requestId != null) {
        return b.requestId - a.requestId;
      }
      const aId = a.projects[0]?.id ?? 0;
      const bId = b.projects[0]?.id ?? 0;
      return bId - aId;
    });
}

function DisplayModeToggle({
  mode,
  onChange,
}: {
  mode: 'list' | 'requests';
  onChange: (mode: 'list' | 'requests') => void;
}) {
  return (
    <div className="review-projects-page__mode-toggle" role="group" aria-label="Display mode">
      <button
        type="button"
        className={`review-projects-page__mode-button${mode === 'requests' ? ' is-active' : ''}`}
        onClick={() => onChange('requests')}
        aria-pressed={mode === 'requests'}
      >
        By request
      </button>
      <button
        type="button"
        className={`review-projects-page__mode-button${mode === 'list' ? ' is-active' : ''}`}
        onClick={() => onChange('list')}
        aria-pressed={mode === 'list'}
      >
        List
      </button>
    </div>
  );
}

function FilterControls({ filters, canCreate }: { filters: FiltersProps; canCreate: boolean }) {
  const dateQuickRanges = getStandardDateQuickRanges();

  return (
    <div className="review-projects-page__bar">
      <LocaleMultiSelect
        options={filters.localeOptions}
        selectedTags={filters.selectedLocaleTags}
        onChange={filters.onLocaleChange}
        buttonAriaLabel="Filter by locale"
        myLocaleTags={filters.myLocaleTags}
      />
      <SearchControl
        value={filters.searchQuery}
        onChange={filters.onSearchChange}
        placeholder={
          filters.searchField === 'id'
            ? 'Search by project #id'
            : filters.searchField === 'requestId'
              ? 'Search by request #id'
              : filters.searchField === 'createdBy'
                ? 'Search by creator username'
                : 'Search by project name'
        }
        inputAriaLabel="Search projects"
        className="review-projects-page__searchcontrol"
        trailing={
          filters.searchField === 'createdBy' && filters.creatorFilter !== 'mine' ? (
            <button
              type="button"
              className="review-projects-page__search-mine"
              onClick={() => filters.onCreatorFilterChange('mine')}
              aria-label="Use my username"
              title="Use my username"
            >
              mine
            </button>
          ) : null
        }
        leading={
          <MultiSectionFilterChip
            ariaLabel="Search options"
            closeOnSelection
            align="left"
            className="review-projects-page__searchmode"
            classNames={{
              button: 'filter-chip__button',
              panel: 'filter-chip__panel',
              section: 'filter-chip__section',
              label: 'filter-chip__label',
              list: 'filter-chip__list',
              option: 'filter-chip__option',
            }}
            summary={`${
              filters.searchField === 'id'
                ? 'ID'
                : filters.searchField === 'requestId'
                  ? 'Request'
                  : filters.searchField === 'createdBy'
                    ? 'Creator'
                    : 'Name'
            } · ${
              filters.searchType === 'contains'
                ? 'Contains'
                : filters.searchType === 'exact'
                  ? 'Exact'
                  : 'iLike'
            }`}
            sections={[
              {
                kind: 'radio',
                label: 'Field',
                options: [
                  { value: 'name', label: 'Name' },
                  { value: 'id', label: 'ID' },
                  { value: 'requestId', label: 'Request ID' },
                  { value: 'createdBy', label: 'Creator' },
                ],
                value: filters.searchField,
                onChange: (value) =>
                  filters.onSearchFieldChange(value as 'name' | 'id' | 'requestId' | 'createdBy'),
              },
              {
                kind: 'radio',
                label: 'Match',
                options: [
                  { value: 'contains', label: 'Contains' },
                  { value: 'exact', label: 'Exact' },
                  { value: 'ilike', label: 'iLike' },
                ],
                value: filters.searchType,
                onChange: (value) =>
                  filters.onSearchTypeChange(value as 'contains' | 'exact' | 'ilike'),
              },
            ]}
          />
        }
      />
      <MultiSectionFilterChip
        ariaLabel="Filter by status, type, size, and date"
        align="right"
        className="review-projects-page__filter-chip"
        sections={[
          {
            kind: 'radio',
            label: 'Type',
            options: filters.typeOptions as Array<FilterOption<string | number>>,
            value: filters.typeValue as string,
            onChange: (value) => filters.onTypeChange(value as ApiReviewProjectType | 'all'),
          },
          {
            kind: 'radio',
            label: 'Status',
            options: filters.statusOptions as Array<FilterOption<string | number>>,
            value: filters.statusValue as string,
            onChange: (value) => filters.onStatusChange(value as ApiReviewProjectStatus | 'all'),
          },
          {
            kind: 'size',
            label: 'Result size limit',
            options: filters.limitOptions,
            value: filters.limitValue,
            onChange: filters.onLimitChange,
          },
          {
            kind: 'date',
            label: 'Due date',
            after: filters.dueAfter,
            before: filters.dueBefore,
            onChangeAfter: filters.onChangeDueAfter,
            onChangeBefore: filters.onChangeDueBefore,
            quickRanges: dateQuickRanges,
            onClear: () => {
              filters.onChangeDueAfter(null);
              filters.onChangeDueBefore(null);
            },
          },
          {
            kind: 'date',
            label: 'Created date',
            after: filters.createdAfter,
            before: filters.createdBefore,
            onChangeAfter: filters.onChangeCreatedAfter,
            onChangeBefore: filters.onChangeCreatedBefore,
            quickRanges: dateQuickRanges,
            onClear: () => {
              filters.onChangeCreatedAfter(null);
              filters.onChangeCreatedBefore(null);
            },
          },
        ]}
      />
      {canCreate ? (
        <Link to="/review-projects/new" className="review-projects-page__create-button">
          New Project
        </Link>
      ) : null}
    </div>
  );
}

function LoadingState() {
  return (
    <div className="review-projects-page__state">
      <div className="spinner spinner--md" aria-hidden />
      <div>Loading review projects...</div>
    </div>
  );
}

function ErrorState({ message, onRetry }: { message?: string; onRetry?: () => void }) {
  return (
    <div className="review-projects-page__state review-projects-page__state--error">
      <div>{message || 'Something went wrong while loading review projects.'}</div>
      {onRetry ? (
        <button type="button" className="review-projects-page__state-action" onClick={onRetry}>
          Try again
        </button>
      ) : null}
    </div>
  );
}

function EmptyState() {
  return (
    <div className="review-projects-page__empty">
      <div className="review-projects-page__empty-copy hint">
        No review projects match these filters. Adjust locale, type, status, limit, or search.
      </div>
    </div>
  );
}

function ReviewProjectRowView({
  project,
  isAdmin,
  isSelected,
  onToggleSelection,
  onRequestIdClick,
  reviewProjectsSessionKey,
}: {
  project: ReviewProjectRow;
  isAdmin: boolean;
  isSelected: boolean;
  onToggleSelection?: (projectId: number) => void;
  onRequestIdClick?: (requestId: number) => void;
  reviewProjectsSessionKey?: string | null;
}) {
  const { accepted, total, percentWidth, percentLabel } = getProgressMetrics(
    project.acceptedCount,
    project.textUnitCount ?? 0,
  );
  const localeTag = project.localeTag;
  const requestId = project.requestId;
  const typeClass =
    project.type === 'EMERGENCY'
      ? 'review-projects-page__type-pill--emergency'
      : 'review-projects-page__type-pill--default';

  return (
    <div className="review-projects-page__row-card">
      <div className="review-projects-page__row-grid">
        <div className="review-projects-page__project">
          <div className="review-projects-page__id-row">
            {isAdmin ? (
              <label className="review-projects-page__select">
                <input
                  type="checkbox"
                  className="review-projects-page__select-input"
                  checked={isSelected}
                  onChange={() => onToggleSelection?.(project.id)}
                  aria-label={`Select review project ${project.id}`}
                />
              </label>
            ) : null}
            <Link
              to={buildReviewProjectDetailPath(project.id, reviewProjectsSessionKey)}
              className="review-projects-page__link"
            >
              <span className="review-projects-page__project-name">{project.name}</span>
            </Link>
            {requestId != null ? (
              onRequestIdClick ? (
                <button
                  type="button"
                  className="review-projects-page__request-id review-projects-page__request-id-button"
                  onClick={() => onRequestIdClick(requestId)}
                >
                  Request #{requestId}
                </button>
              ) : (
                <span className="review-projects-page__request-id">Request #{requestId}</span>
              )
            ) : null}
          </div>
        </div>
        <div className="review-projects-page__counts">
          <CountsInline words={project.wordCount ?? null} strings={project.textUnitCount ?? null} />
        </div>
        <div className="review-projects-page__type">
          <Pill className={`review-projects-page__type-pill ${typeClass}`}>
            {REVIEW_PROJECT_TYPE_LABELS[project.type] ?? REVIEW_PROJECT_TYPE_LABELS.NORMAL}
          </Pill>
        </div>
        <div className="review-projects-page__meta">
          {project.status === 'CLOSED' ? (
            <Pill className="review-projects-page__status-pill status-closed">
              {REVIEW_PROJECT_STATUS_LABELS[project.status]}
            </Pill>
          ) : project.dueDate ? (
            <span>Due {formatDateTime(project.dueDate)}</span>
          ) : null}
        </div>
        <div className="review-projects-page__locales">
          {localeTag ? (
            <div className="review-projects-page__pill-list">
              <LocalePill
                className="review-projects-page__pill review-projects-page__pill--locale"
                bcp47Tag={localeTag}
                labelMode="tag"
              />
            </div>
          ) : (
            <span className="review-projects-page__muted">No locale</span>
          )}
        </div>
        <div className="review-projects-page__progress">
          <div
            className="review-projects-page__progress-bar"
            title={`${formatNumber(accepted)} of ${formatNumber(total)} processed`}
          >
            <div
              className="review-projects-page__progress-fill"
              style={{ width: percentWidth }}
              aria-hidden
            />
          </div>
          <div className="review-projects-page__progress-meta">
            <div className="review-projects-page__progress-percent">{percentLabel} reviewed</div>
          </div>
        </div>
      </div>
    </div>
  );
}

function SelectionCheckbox({
  checked,
  indeterminate = false,
  disabled = false,
  ariaLabel,
  onChange,
  className,
}: {
  checked: boolean;
  indeterminate?: boolean;
  disabled?: boolean;
  ariaLabel: string;
  onChange: () => void;
  className?: string;
}) {
  return (
    <label
      className={`review-projects-page__select${className ? ` ${className}` : ''}`}
      data-no-toggle="true"
    >
      <input
        ref={(element) => {
          if (element) {
            element.indeterminate = indeterminate && !checked;
          }
        }}
        type="checkbox"
        className="review-projects-page__select-input"
        checked={checked}
        disabled={disabled}
        onChange={onChange}
        aria-label={ariaLabel}
      />
    </label>
  );
}

function RequestGroupsSection({
  groups,
  expandedKey,
  onToggleExpanded,
  onFilterByRequest,
  adminControls,
  reviewProjectsSessionKey,
}: {
  groups: ReviewProjectRequestGroupRow[];
  expandedKey: string | null;
  onToggleExpanded: (key: string) => void;
  onFilterByRequest: (requestId: number) => void;
  adminControls?: ReviewProjectsAdminControls;
  reviewProjectsSessionKey?: string | null;
}) {
  const resolveLocaleDisplayName = useLocaleDisplayNameResolver();
  const isAdmin = adminControls?.enabled ?? false;
  const queryClient = useQueryClient();
  const selectedProjectIdSet = useMemo(
    () => new Set(adminControls?.selectedProjectIds ?? []),
    [adminControls?.selectedProjectIds],
  );
  const [reassignTarget, setReassignTarget] = useState<
    | {
        kind: 'pm';
        requestKey: string;
        requestName: string;
        requestId: number | null;
        projects: ReviewProjectRow[];
      }
    | {
        kind: 'translator';
        project: ReviewProjectRow;
      }
    | null
  >(null);
  const [reassignDraftUserId, setReassignDraftUserId] = useState<number | null>(null);
  const [reassignShowAllTeam, setReassignShowAllTeam] = useState(false);
  const [reassignError, setReassignError] = useState<string | null>(null);

  const activeTeamId = useMemo(() => {
    if (!reassignTarget) {
      return null;
    }
    if (reassignTarget.kind === 'translator') {
      return reassignTarget.project.teamId ?? null;
    }
    const uniqueTeamIds = Array.from(
      new Set(reassignTarget.projects.map((project) => project.teamId).filter((id) => id != null)),
    );
    return uniqueTeamIds.length === 1 ? (uniqueTeamIds[0] ?? null) : null;
  }, [reassignTarget]);

  const hasMixedTeamsForPmTarget =
    reassignTarget?.kind === 'pm'
      ? Array.from(
          new Set(
            reassignTarget.projects.map((project) => project.teamId).filter((id) => id != null),
          ),
        ).length > 1
      : false;

  useEffect(() => {
    if (!reassignTarget) {
      return;
    }
    if (reassignTarget.kind === 'translator') {
      setReassignDraftUserId(reassignTarget.project.assignedTranslatorUserId ?? null);
    } else {
      const uniquePmIds = Array.from(
        new Set(
          reassignTarget.projects
            .map((project) => project.assignedPmUserId)
            .filter((id) => id != null),
        ),
      );
      setReassignDraftUserId(uniquePmIds.length === 1 ? (uniquePmIds[0] ?? null) : null);
    }
    setReassignShowAllTeam(false);
    setReassignError(null);
  }, [reassignTarget]);

  const reassignUsersQuery = useQuery<ApiUser[]>({
    queryKey: ['users', 'admin', 'review-projects-reassign'],
    queryFn: fetchAllUsersAdmin,
    enabled: isAdmin && reassignTarget != null,
    staleTime: 30_000,
  });
  const reassignPmPoolQuery = useQuery({
    queryKey: ['team-pm-pool', activeTeamId, 'review-projects-reassign'],
    queryFn: () => fetchTeamPmPool(activeTeamId as number),
    enabled: isAdmin && reassignTarget != null && activeTeamId != null,
    staleTime: 30_000,
  });
  const reassignTranslatorsQuery = useQuery({
    queryKey: ['team-translators', activeTeamId, 'review-projects-reassign'],
    queryFn: () => fetchTeamTranslators(activeTeamId as number),
    enabled: isAdmin && reassignTarget != null && activeTeamId != null,
    staleTime: 30_000,
  });
  const reassignLocalePoolsQuery = useQuery({
    queryKey: ['team-locale-pools', activeTeamId, 'review-projects-reassign'],
    queryFn: () => fetchTeamLocalePools(activeTeamId as number),
    enabled:
      isAdmin &&
      reassignTarget != null &&
      reassignTarget.kind === 'translator' &&
      activeTeamId != null,
    staleTime: 30_000,
  });

  const reassignUsersById = useMemo(() => {
    const map = new Map<number, ApiUser>();
    (reassignUsersQuery.data ?? []).forEach((user) => {
      map.set(user.id, user);
    });
    return map;
  }, [reassignUsersQuery.data]);

  const reassignOptionIds = useMemo(() => {
    if (!reassignTarget || activeTeamId == null) {
      return [] as number[];
    }
    const pmPoolIds = (reassignPmPoolQuery.data?.userIds ?? []).filter(
      (id): id is number => id > 0,
    );
    const translatorIds = (reassignTranslatorsQuery.data?.userIds ?? []).filter(
      (id): id is number => id > 0,
    );
    const allTeamIds = Array.from(new Set([...pmPoolIds, ...translatorIds]));

    let baseIds: number[] = [];
    if (reassignTarget.kind === 'pm') {
      baseIds = pmPoolIds;
    } else {
      const localeTagKey = (reassignTarget.project.localeTag ?? '').trim().toLowerCase();
      const localePoolRow =
        reassignLocalePoolsQuery.data?.entries.find(
          (entry) => entry.localeTag.trim().toLowerCase() === localeTagKey,
        ) ?? null;
      baseIds =
        (localePoolRow?.translatorUserIds?.filter((id): id is number => id > 0) ?? []).length > 0
          ? (localePoolRow?.translatorUserIds?.filter((id): id is number => id > 0) ?? [])
          : translatorIds;
    }

    const source = reassignShowAllTeam ? allTeamIds : baseIds;
    const ids = Array.from(new Set(source));
    if (reassignDraftUserId != null && !ids.includes(reassignDraftUserId)) {
      ids.unshift(reassignDraftUserId);
    }
    return ids;
  }, [
    activeTeamId,
    reassignDraftUserId,
    reassignLocalePoolsQuery.data?.entries,
    reassignPmPoolQuery.data?.userIds,
    reassignShowAllTeam,
    reassignTarget,
    reassignTranslatorsQuery.data?.userIds,
  ]);

  const reassignOptions = useMemo(
    () =>
      reassignOptionIds.map((id) => {
        const user = reassignUsersById.get(id);
        return {
          value: id,
          label: user?.commonName
            ? `${user.commonName} (${user.username})`
            : (user?.username ?? `User #${id}`),
        };
      }),
    [reassignOptionIds, reassignUsersById],
  );

  const reassignMutation = useMutation({
    mutationFn: async () => {
      if (!reassignTarget) {
        return;
      }
      if (reassignTarget.kind === 'translator') {
        const project = reassignTarget.project;
        await updateReviewProjectAssignment(project.id, {
          teamId: project.teamId,
          assignedPmUserId: project.assignedPmUserId,
          assignedTranslatorUserId: reassignDraftUserId,
          note: null,
        });
        return;
      }

      for (const project of reassignTarget.projects) {
        if ((project.assignedPmUserId ?? null) === (reassignDraftUserId ?? null)) {
          continue;
        }
        await updateReviewProjectAssignment(project.id, {
          teamId: project.teamId,
          assignedPmUserId: reassignDraftUserId,
          assignedTranslatorUserId: project.assignedTranslatorUserId,
          note: null,
        });
      }
    },
    onSuccess: async () => {
      setReassignError(null);
      setReassignTarget(null);
      await queryClient.invalidateQueries({ queryKey: [REVIEW_PROJECTS_QUERY_KEY] });
      await queryClient.invalidateQueries({ queryKey: [REVIEW_PROJECT_REQUESTS_QUERY_KEY] });
    },
    onError: (error) => {
      setReassignError(error instanceof Error ? error.message : 'Failed to update assignment');
    },
  });

  const openPmReassign = useCallback((group: ReviewProjectRequestGroupRow) => {
    setReassignTarget({
      kind: 'pm',
      requestKey: group.key,
      requestName: group.name,
      requestId: group.requestId,
      projects: group.projects,
    });
  }, []);

  const openTranslatorReassign = useCallback((project: ReviewProjectRow) => {
    setReassignTarget({ kind: 'translator', project });
  }, []);

  const closeReassignModal = useCallback(() => {
    if (reassignMutation.isPending) {
      return;
    }
    setReassignTarget(null);
    setReassignError(null);
  }, [reassignMutation.isPending]);

  const defaultScopeLabel =
    reassignTarget?.kind === 'translator' ? 'Locale pool only' : 'PM pool only';
  const canSaveReassign =
    reassignTarget != null &&
    !reassignMutation.isPending &&
    !hasMixedTeamsForPmTarget &&
    activeTeamId != null;

  if (groups.length === 0) {
    return (
      <div className="review-projects-page__rows-frame">
        <div className="review-projects-page__rows review-projects-page__rows--empty">
          <EmptyState />
        </div>
      </div>
    );
  }

  return (
    <div className="review-projects-page__rows-frame">
      <div className="review-projects-page__rows-shell">
        <div className="review-projects-page__rows">
          {groups.map((group) => {
            const isExpanded = expandedKey === group.key;
            const groupProjectIds = group.projects.map((project) => project.id);
            const requestEditProjectId = group.projects[0]?.id ?? null;
            const selectedInGroup = groupProjectIds.filter((projectId) =>
              selectedProjectIdSet.has(projectId),
            ).length;
            const allSelectedInGroup =
              groupProjectIds.length > 0 && selectedInGroup === groupProjectIds.length;
            const partiallySelectedInGroup = selectedInGroup > 0 && !allSelectedInGroup;
            const { completedLanguages, totalLanguages, percentWidth, completionLabel } =
              getLanguageCompletionMetrics(group.projects);
            const averageWordCount = getAverageCount(
              group.projects.map((project) => project.wordCount),
            );
            const averageStringCount = getAverageCount(
              group.projects.map((project) => project.textUnitCount),
            );
            const typeBadge = getRequestGroupTypeBadge(group);
            const requestLabel =
              group.requestId != null ? `Request #${group.requestId}` : 'Request';
            const onCardToggleClick = (event: MouseEvent<HTMLElement>) => {
              const target = event.target as HTMLElement | null;
              if (target && target.closest(TOGGLE_IGNORE_SELECTOR)) {
                return;
              }
              onToggleExpanded(group.key);
            };
            return (
              <section key={group.key} className="review-projects-page__row-card">
                <div
                  className="review-projects-page__row-grid review-projects-page__row-grid--request"
                  onClick={onCardToggleClick}
                >
                  <div className="review-projects-page__project">
                    <div className="review-projects-page__id-row">
                      {isAdmin ? (
                        <SelectionCheckbox
                          className="review-projects-page__select--request-group"
                          checked={allSelectedInGroup}
                          indeterminate={partiallySelectedInGroup}
                          disabled={adminControls?.isSaving}
                          ariaLabel={`Select all projects in ${group.name}`}
                          onChange={() => {
                            const shouldSelect = !allSelectedInGroup;
                            if (adminControls?.onSetProjectSelection) {
                              adminControls.onSetProjectSelection(groupProjectIds, shouldSelect);
                              return;
                            }
                            groupProjectIds.forEach((projectId) => {
                              const isSelected = selectedProjectIdSet.has(projectId);
                              if (isSelected !== shouldSelect) {
                                adminControls?.onToggleProjectSelection(projectId);
                              }
                            });
                          }}
                        />
                      ) : null}
                      <span className="review-projects-page__request-toggle">
                        <span className="review-projects-page__project-name">{group.name}</span>
                      </span>
                      <span className="review-projects-page__request-secondary">
                        {group.requestId != null ? (
                          <button
                            type="button"
                            className="review-projects-page__request-id review-projects-page__request-id-button"
                            onClick={() => onFilterByRequest(group.requestId as number)}
                          >
                            {requestLabel}
                          </button>
                        ) : (
                          <span className="review-projects-page__request-id">{requestLabel}</span>
                        )}
                        {isAdmin && requestEditProjectId != null ? (
                          <>
                            <span className="review-projects-page__request-dot" aria-hidden="true">
                              ·
                            </span>
                            <Link
                              to={buildReviewProjectDetailPath(
                                requestEditProjectId,
                                reviewProjectsSessionKey,
                                { requestDetails: true, requestSource: 'list' },
                              )}
                              className="review-projects-page__request-link review-projects-page__link"
                            >
                              Edit
                            </Link>
                          </>
                        ) : null}
                      </span>
                    </div>
                  </div>
                  <div className="review-projects-page__counts">
                    <CountsInline words={averageWordCount} strings={averageStringCount} />
                  </div>
                  <div className="review-projects-page__type">
                    <Pill className={`review-projects-page__type-pill ${typeBadge.className}`}>
                      {typeBadge.label}
                    </Pill>
                  </div>
                  <div className="review-projects-page__meta">
                    {group.dueDate ? <span>Due {formatDateTime(group.dueDate)}</span> : null}
                    {group.createdByUsername || group.assignedPmUsername ? (
                      <span className="review-projects-page__request-meta-inline">
                        {group.createdByUsername ? (
                          <span className="review-projects-page__request-created-by">
                            by {group.createdByUsername}
                          </span>
                        ) : null}
                        {group.createdByUsername && group.assignedPmUsername ? (
                          <span className="review-projects-page__request-dot" aria-hidden="true">
                            ·
                          </span>
                        ) : null}
                        {isAdmin ? (
                          <button
                            type="button"
                            className="review-projects-page__inline-action review-projects-page__request-assignee"
                            data-no-toggle="true"
                            onClick={(event) => {
                              event.stopPropagation();
                              openPmReassign(group);
                            }}
                            disabled={reassignMutation.isPending}
                            title="Reassign PM for this request"
                          >
                            PM {group.assignedPmUsername?.trim() ? group.assignedPmUsername : '—'}
                          </button>
                        ) : (
                          <span className="review-projects-page__request-assignee">
                            PM {group.assignedPmUsername?.trim() ? group.assignedPmUsername : '—'}
                          </span>
                        )}
                      </span>
                    ) : null}
                  </div>
                  <div className="review-projects-page__locales">
                    <div className="review-projects-page__pill-list">
                      {group.localeTags.slice(0, 8).map((tag) => (
                        <LocalePill
                          key={`${group.key}-${tag}`}
                          className="review-projects-page__pill review-projects-page__pill--locale"
                          bcp47Tag={tag}
                          labelMode="tag"
                        />
                      ))}
                      {group.localeTags.length > 8 ? (
                        <span className="review-projects-page__muted">
                          +{group.localeTags.length - 8} more
                        </span>
                      ) : null}
                    </div>
                  </div>
                  <div className="review-projects-page__progress">
                    <div
                      className="review-projects-page__progress-bar"
                      title={`${completedLanguages} of ${totalLanguages} locales complete`}
                    >
                      <div
                        className="review-projects-page__progress-fill"
                        style={{ width: percentWidth }}
                        aria-hidden
                      />
                    </div>
                    <div className="review-projects-page__progress-meta">
                      <div className="review-projects-page__progress-percent">
                        {completionLabel}
                      </div>
                    </div>
                  </div>
                </div>
                {isExpanded ? (
                  <div className="review-projects-page__request-projects">
                    {group.projects.map((project) => {
                      const isSelected = selectedProjectIdSet.has(project.id);
                      const {
                        accepted: projectAccepted,
                        total: projectTotal,
                        percentWidth: projectPercentWidth,
                        percentLabel: projectPercentLabel,
                      } = getProgressMetrics(project.acceptedCount, project.textUnitCount ?? 0);
                      return (
                        <div key={project.id} className="review-projects-page__request-project-row">
                          {isAdmin ? (
                            <SelectionCheckbox
                              className="review-projects-page__select--request-row"
                              checked={isSelected}
                              disabled={adminControls?.isSaving}
                              ariaLabel={`Select review project ${project.id}`}
                              onChange={() => adminControls?.onToggleProjectSelection(project.id)}
                            />
                          ) : null}
                          <Link
                            to={buildReviewProjectDetailPath(project.id, reviewProjectsSessionKey)}
                            className="review-projects-page__request-project-link review-projects-page__link"
                          >
                            <div className="review-projects-page__request-project-locale">
                              {project.localeTag ? (
                                <>
                                  <LocalePill
                                    className="review-projects-page__pill review-projects-page__pill--locale"
                                    bcp47Tag={project.localeTag}
                                    labelMode="tag"
                                  />
                                  <span className="review-projects-page__request-project-locale-name">
                                    {resolveLocaleDisplayName(project.localeTag) ||
                                      project.localeTag}
                                  </span>
                                </>
                              ) : (
                                <span className="review-projects-page__muted">No locale</span>
                              )}
                            </div>
                            {isAdmin ? (
                              <span
                                role="button"
                                tabIndex={0}
                                className="review-projects-page__inline-action review-projects-page__request-project-translator"
                                data-no-toggle="true"
                                onClick={(event) => {
                                  event.preventDefault();
                                  event.stopPropagation();
                                  if (!reassignMutation.isPending) {
                                    openTranslatorReassign(project);
                                  }
                                }}
                                onKeyDown={(event) => {
                                  if (event.key !== 'Enter' && event.key !== ' ') {
                                    return;
                                  }
                                  event.preventDefault();
                                  event.stopPropagation();
                                  if (!reassignMutation.isPending) {
                                    openTranslatorReassign(project);
                                  }
                                }}
                                aria-disabled={reassignMutation.isPending}
                                title="Reassign translator"
                              >
                                {project.assignedTranslatorUsername?.trim()
                                  ? project.assignedTranslatorUsername
                                  : '—'}
                              </span>
                            ) : (
                              <div className="review-projects-page__request-project-translator">
                                {project.assignedTranslatorUsername?.trim()
                                  ? project.assignedTranslatorUsername
                                  : '—'}
                              </div>
                            )}
                            <div className="review-projects-page__request-project-counts">
                              <CountsInline
                                words={project.wordCount ?? null}
                                strings={project.textUnitCount ?? null}
                              />
                            </div>
                            <div className="review-projects-page__request-project-progress">
                              <div className="review-projects-page__request-project-meta">
                                {project.status === 'CLOSED' ? (
                                  <Pill className="review-projects-page__status-pill status-closed">
                                    {REVIEW_PROJECT_STATUS_LABELS[project.status]}
                                  </Pill>
                                ) : null}
                              </div>
                              <div
                                className="review-projects-page__progress-bar"
                                title={`${formatNumber(projectAccepted)} of ${formatNumber(projectTotal)} processed`}
                              >
                                <div
                                  className="review-projects-page__progress-fill"
                                  style={{ width: projectPercentWidth }}
                                  aria-hidden
                                />
                              </div>
                              <span className="review-projects-page__progress-percent">
                                {projectPercentLabel}
                              </span>
                            </div>
                          </Link>
                        </div>
                      );
                    })}
                  </div>
                ) : null}
              </section>
            );
          })}
        </div>
      </div>
      <Modal
        open={reassignTarget != null}
        size="md"
        className="review-projects-page__reassign-dialog"
        ariaLabel={
          reassignTarget?.kind === 'pm' ? 'Reassign request PM' : 'Reassign project translator'
        }
        onClose={closeReassignModal}
        closeOnBackdrop={!reassignMutation.isPending}
      >
        {reassignTarget ? (
          <>
            <div className="modal__title">
              {reassignTarget.kind === 'pm' ? 'Reassign PM' : 'Reassign translator'}
            </div>
            <div className="modal__body review-projects-page__reassign-modal">
              <div className="review-projects-page__reassign-modal-line">
                {reassignTarget.kind === 'pm' ? (
                  <>
                    <span className="review-projects-page__reassign-modal-label">Request</span>
                    <span className="review-projects-page__reassign-modal-value">
                      {reassignTarget.requestName}
                      {reassignTarget.requestId != null ? ` (#${reassignTarget.requestId})` : ''}
                    </span>
                  </>
                ) : (
                  <>
                    <span className="review-projects-page__reassign-modal-label">Project</span>
                    <span className="review-projects-page__reassign-modal-value">
                      #{reassignTarget.project.id}
                      {reassignTarget.project.localeTag
                        ? ` · ${reassignTarget.project.localeTag}`
                        : ''}
                    </span>
                  </>
                )}
              </div>
              <div className="review-projects-page__reassign-modal-line">
                <span className="review-projects-page__reassign-modal-label">Team</span>
                <span className="review-projects-page__reassign-modal-value">
                  {activeTeamId != null
                    ? reassignTarget.kind === 'pm'
                      ? (reassignTarget.projects[0]?.teamName ?? `#${activeTeamId}`)
                      : (reassignTarget.project.teamName ?? `#${activeTeamId}`)
                    : hasMixedTeamsForPmTarget
                      ? 'Mixed teams'
                      : '—'}
                </span>
              </div>

              {hasMixedTeamsForPmTarget ? (
                <div className="review-projects-page__reassign-modal-error">
                  PM reassignment is disabled for mixed-team requests. Reassign per project for now.
                </div>
              ) : activeTeamId == null ? (
                <div className="review-projects-page__reassign-modal-error">
                  No team is assigned.
                </div>
              ) : (
                <>
                  <div className="review-projects-page__reassign-modal-field">
                    <SingleSelectDropdown<number>
                      label={reassignTarget.kind === 'pm' ? 'Project manager' : 'Translator'}
                      options={reassignOptions}
                      value={reassignDraftUserId}
                      onChange={(next) => {
                        setReassignDraftUserId(next);
                        setReassignError(null);
                      }}
                      placeholder={reassignTarget.kind === 'pm' ? 'Select PM' : 'Select translator'}
                      noneLabel={reassignTarget.kind === 'pm' ? 'No PM' : 'No translator'}
                      buttonAriaLabel={
                        reassignTarget.kind === 'pm'
                          ? 'Select project manager'
                          : 'Select translator'
                      }
                      footerAction={{
                        label: reassignShowAllTeam ? defaultScopeLabel : 'Show all team',
                        onClick: () => setReassignShowAllTeam((value) => !value),
                        disabled: reassignMutation.isPending,
                      }}
                    />
                  </div>
                  {reassignTarget.kind === 'pm' ? (
                    <div className="review-projects-page__reassign-modal-help">
                      Applies to all locale projects in this request.
                    </div>
                  ) : null}
                </>
              )}
              {reassignError ? (
                <div className="review-projects-page__reassign-modal-error">{reassignError}</div>
              ) : null}
            </div>
            <div className="modal__actions">
              <button
                type="button"
                className="modal__button"
                onClick={closeReassignModal}
                disabled={reassignMutation.isPending}
              >
                Cancel
              </button>
              <button
                type="button"
                className="modal__button modal__button--primary"
                onClick={() => {
                  if (!canSaveReassign) {
                    return;
                  }
                  void reassignMutation.mutateAsync();
                }}
                disabled={!canSaveReassign}
              >
                {reassignMutation.isPending ? 'Saving…' : 'Save'}
              </button>
            </div>
          </>
        ) : null}
      </Modal>
    </div>
  );
}

export function ReviewProjectsPageView({
  status,
  errorMessage,
  errorOnRetry,
  projects,
  requestGroups,
  filters,
  requestFilter,
  onRequestIdClick,
  canCreate = true,
  adminControls,
  displayMode = 'list',
  canUseRequestMode = false,
  onDisplayModeChange,
  expandedRequestKey,
  onExpandedRequestKeyChange,
  reviewProjectsSessionKey,
}: Props) {
  const effectiveDisplayMode = canUseRequestMode ? displayMode : 'list';
  const scrollElementRef = useRef<HTMLDivElement>(null);
  const getItemKey = useCallback((index: number) => projects[index]?.id ?? index, [projects]);
  const groupedRequestRows = useMemo(
    () => requestGroups ?? buildRequestGroups(projects),
    [projects, requestGroups],
  );
  const [localExpandedRequestKey, setLocalExpandedRequestKey] = useState<string | null>(null);
  const effectiveExpandedRequestKey =
    expandedRequestKey !== undefined ? expandedRequestKey : localExpandedRequestKey;
  const setExpandedRequestKey = onExpandedRequestKeyChange ?? setLocalExpandedRequestKey;
  const hasResults =
    effectiveDisplayMode === 'requests' ? groupedRequestRows.length > 0 : projects.length > 0;

  const estimateSize = useCallback(
    () =>
      getRowHeightPx({
        element: scrollElementRef.current,
        cssVariable: '--review-projects-row-height',
        defaultRem: 7.5,
      }),
    [],
  );

  const {
    items: virtualItems,
    totalSize,
    measureElement,
  } = useVirtualRows<HTMLDivElement>({
    count: projects.length,
    getItemKey,
    estimateSize,
    getScrollElement: () => scrollElementRef.current,
    overscan: 8,
  });

  const { getRowRef } = useMeasuredRowRefs<number, HTMLDivElement>({ measureElement });

  const totalWords = useMemo(
    () => getAverageCount(projects.map((project) => project.wordCount)),
    [projects],
  );

  const totalStrings = useMemo(
    () => getAverageCount(projects.map((project) => project.textUnitCount)),
    [projects],
  );

  useEffect(() => {
    if (requestFilter?.requestId == null) {
      return;
    }
    const requestKey = `request:${requestFilter.requestId}`;
    setExpandedRequestKey(requestKey);
  }, [requestFilter?.requestId, setExpandedRequestKey]);

  const handleToggleRequestGroup = useCallback(
    (key: string) => {
      setExpandedRequestKey(effectiveExpandedRequestKey === key ? null : key);
    },
    [effectiveExpandedRequestKey, setExpandedRequestKey],
  );

  const handleFilterByRequest = useCallback(
    (requestId: number) => {
      onRequestIdClick?.(requestId);
    },
    [onRequestIdClick],
  );

  if (status === 'loading') {
    return <LoadingState />;
  }

  if (status === 'error') {
    return <ErrorState message={errorMessage} onRetry={errorOnRetry} />;
  }

  return (
    <div className="review-projects-page">
      {requestFilter && requestFilter.requestId !== null ? (
        <div className="review-projects-page__notice">
          <span className="review-projects-page__notice-text">
            Showing projects from request #{requestFilter.requestId}
          </span>
          <button
            type="button"
            className="review-projects-page__notice-clear"
            onClick={requestFilter.onClear}
          >
            Clear
          </button>
        </div>
      ) : null}
      <FilterControls filters={filters} canCreate={canCreate} />
      {hasResults ? (
        <SummaryBar
          resultCount={
            effectiveDisplayMode === 'requests' ? groupedRequestRows.length : projects.length
          }
          totalWords={totalWords}
          totalStrings={totalStrings}
          showModeToggle={canUseRequestMode}
          displayMode={effectiveDisplayMode}
          onDisplayModeChange={onDisplayModeChange}
        />
      ) : null}
      {hasResults && adminControls && adminControls.enabled ? (
        <AdminBar adminControls={adminControls} visibleCount={projects.length} />
      ) : null}
      {effectiveDisplayMode === 'requests' ? (
        <RequestGroupsSection
          groups={groupedRequestRows}
          expandedKey={effectiveExpandedRequestKey}
          onToggleExpanded={handleToggleRequestGroup}
          onFilterByRequest={handleFilterByRequest}
          adminControls={adminControls}
          reviewProjectsSessionKey={reviewProjectsSessionKey}
        />
      ) : (
        <ContentSection
          projects={projects}
          rowsParentRef={scrollElementRef}
          virtualItems={virtualItems}
          totalSize={totalSize}
          getRowRef={getRowRef}
          adminControls={adminControls}
          onRequestIdClick={onRequestIdClick}
          reviewProjectsSessionKey={reviewProjectsSessionKey}
        />
      )}
    </div>
  );
}
