import './text-unit-history-timeline.css';

import { useState } from 'react';
import { Link, type To } from 'react-router-dom';

import { formatLocalDateTime } from '../utils/dateTime';
import { JsonPayloadModal, type JsonPayloadModalItem } from './JsonPayloadModal';

export type TextUnitHistoryTimelineComment = {
  key: string;
  type: string;
  severity: string;
  content: string;
};

export type TextUnitHistoryTimelineAiTranslateAttempt = {
  key: string;
  createdDate?: string | null;
  status: string;
  translateType?: string | null;
  model?: string | null;
  completionId?: string | null;
  requestGroupId?: string | null;
  detailsUrl?: string | null;
  requestPayloadUrl?: string | null;
  responsePayloadUrl?: string | null;
  errorMessage?: string | null;
};

export type TextUnitHistoryTimelineEntry = {
  key: string;
  title?: string;
  variantId?: string;
  userName: string;
  translation: string;
  date: string;
  status: string;
  comments: TextUnitHistoryTimelineComment[];
  aiTranslateAttempts?: TextUnitHistoryTimelineAiTranslateAttempt[];
  badges?: string[];
  sourceLink?: {
    label: string;
    to: To;
    title?: string;
  } | null;
};

type TextUnitHistoryTimelineProps = {
  isLoading: boolean;
  errorMessage: string | null;
  missingLocale?: boolean;
  entries: TextUnitHistoryTimelineEntry[];
  showDeletedEntry?: boolean;
  initialDate?: string | null;
  emptyMessage?: string;
};

export function TextUnitHistoryTimeline({
  isLoading,
  errorMessage,
  missingLocale = false,
  entries,
  showDeletedEntry = false,
  initialDate,
  emptyMessage = 'No history yet.',
}: TextUnitHistoryTimelineProps) {
  if (missingLocale) {
    return (
      <div className="text-unit-history__state text-unit-history__state--warning">
        Missing locale. Open this page from the workbench row to load history.
      </div>
    );
  }

  if (isLoading) {
    return (
      <div className="text-unit-history__state">
        <span className="spinner spinner--md" aria-hidden />
        <span>Loading history…</span>
      </div>
    );
  }

  if (errorMessage) {
    return (
      <div className="text-unit-history__state text-unit-history__state--error">{errorMessage}</div>
    );
  }

  if (!showDeletedEntry && entries.length === 0 && !initialDate) {
    return <div className="text-unit-history__state">{emptyMessage}</div>;
  }

  return (
    <ol className="text-unit-history__timeline">
      {showDeletedEntry ? (
        <li className="text-unit-history__timeline-item">
          <div className="text-unit-history__timeline-dot" aria-hidden="true" />
          <div className="text-unit-history__timeline-card">
            <div className="text-unit-history__timeline-header">
              <div className="text-unit-history__timeline-summary">
                <span className="text-unit-history__timeline-title">Translation deleted</span>
                <span className="text-unit-history__timeline-summary-separator">&middot;</span>
                <span className="text-unit-history__timeline-summary-meta">-</span>
                <span className="text-unit-history__timeline-summary-separator">&middot;</span>
                <span className="text-unit-history__timeline-summary-status">Deleted</span>
              </div>
              <time className="text-unit-history__timeline-time">-</time>
            </div>
            <pre className="text-unit-history__timeline-content">{'<no current translation>'}</pre>
          </div>
        </li>
      ) : null}

      {entries.map((item) => {
        const legacyAiTranslateAttempts =
          (item.aiTranslateAttempts?.length ?? 0) > 0
            ? []
            : item.comments.flatMap((comment) => {
                const attempt = toLegacyAiTranslateAttempt(comment);
                return attempt ? [attempt] : [];
              });
        const aiTranslateAttempts = [
          ...(item.aiTranslateAttempts ?? []),
          ...legacyAiTranslateAttempts,
        ];
        const comments = item.comments.filter((comment) => !isAiTranslateComment(comment));
        const hasAiTranslateComment = item.comments.some(isAiTranslateComment);
        const userName = formatTimelineUserName(
          item.userName,
          aiTranslateAttempts.length > 0 || hasAiTranslateComment,
        );
        const badges =
          aiTranslateAttempts.length > 0 && !item.badges?.includes('AI Translate')
            ? [...(item.badges ?? []), 'AI Translate']
            : item.badges;

        return (
          <li key={item.key} className="text-unit-history__timeline-item">
            <div className="text-unit-history__timeline-dot" aria-hidden="true" />
            <div className="text-unit-history__timeline-card">
              <div className="text-unit-history__timeline-header">
                <div className="text-unit-history__timeline-summary">
                  <span className="text-unit-history__timeline-title">
                    {item.title ?? 'Translation updated'}
                    {item.variantId ? (
                      <span className="text-unit-history__timeline-title-meta">
                        #{item.variantId}
                      </span>
                    ) : null}
                  </span>
                  {badges?.length ? (
                    <span className="text-unit-history__timeline-badges">
                      {badges.map((badge) => (
                        <span
                          key={`${item.key}-${badge}`}
                          className="text-unit-history__timeline-badge"
                          title={badge}
                        >
                          {badge}
                        </span>
                      ))}
                    </span>
                  ) : null}
                  <span className="text-unit-history__timeline-summary-separator">&middot;</span>
                  <span className="text-unit-history__timeline-summary-meta">{userName}</span>
                  {item.status !== '-' ? (
                    <>
                      <span className="text-unit-history__timeline-summary-separator">
                        &middot;
                      </span>
                      <span className="text-unit-history__timeline-summary-status">
                        {item.status}
                      </span>
                    </>
                  ) : null}
                </div>
                <time className="text-unit-history__timeline-time">{item.date}</time>
              </div>
              <pre className="text-unit-history__timeline-content">{item.translation}</pre>
              {item.sourceLink ? (
                <div className="text-unit-history__timeline-source">
                  <Link to={item.sourceLink.to} title={item.sourceLink.title}>
                    {item.sourceLink.label}
                  </Link>
                </div>
              ) : null}
              {aiTranslateAttempts.length ? (
                <div className="text-unit-history__ai-list" aria-label="AI Translate lineage">
                  {aiTranslateAttempts.map((attempt) => (
                    <AiTranslateAttempt key={attempt.key} attempt={attempt} />
                  ))}
                </div>
              ) : null}
              {comments.length > 0 ? (
                <table className="text-unit-history__comment-table">
                  <thead>
                    <tr>
                      <th>type</th>
                      <th>severity</th>
                      <th>content</th>
                    </tr>
                  </thead>
                  <tbody>
                    {comments.map((comment) => (
                      <tr key={comment.key}>
                        <td>{comment.type}</td>
                        <td>{comment.severity}</td>
                        <td>{comment.content}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : null}
            </div>
          </li>
        );
      })}

      {initialDate ? (
        <li className="text-unit-history__timeline-item">
          <div className="text-unit-history__timeline-dot" aria-hidden="true" />
          <div className="text-unit-history__timeline-card">
            <div className="text-unit-history__timeline-header">
              <div className="text-unit-history__timeline-summary">
                <span className="text-unit-history__timeline-title">
                  Text unit created (untranslated)
                </span>
                <span className="text-unit-history__timeline-summary-separator">&middot;</span>
                <span className="text-unit-history__timeline-summary-meta">-</span>
                <span className="text-unit-history__timeline-summary-separator">&middot;</span>
                <span className="text-unit-history__timeline-summary-status">Untranslated</span>
              </div>
              <time className="text-unit-history__timeline-time">{initialDate}</time>
            </div>
            <pre className="text-unit-history__timeline-content">{'<no translation yet>'}</pre>
          </div>
        </li>
      ) : null}
    </ol>
  );
}

function toLegacyAiTranslateAttempt(
  comment: TextUnitHistoryTimelineComment,
): TextUnitHistoryTimelineAiTranslateAttempt | null {
  if (!isAiTranslateComment(comment)) {
    return null;
  }

  const content = comment.content.trim();
  if (!content || content === '-') {
    return null;
  }

  const jsonInfo = parseLegacyAiTranslateJson(content);
  if (jsonInfo) {
    return {
      key: `legacy-${comment.key}`,
      status: 'LEGACY',
      translateType: asString(jsonInfo.translateType) ?? asString(jsonInfo.mode) ?? null,
      completionId: asString(jsonInfo.completionId) ?? null,
      model: null,
      requestGroupId: null,
    };
  }

  const plainInfo = content.match(/^ai-translate with\s+(.+?)(?:\. confidence level:\s*(.+))?$/i);
  return {
    key: `legacy-${comment.key}`,
    status: 'LEGACY',
    translateType: plainInfo?.[1]?.trim() || content,
    model: null,
    completionId: null,
    requestGroupId: null,
  };
}

function isAiTranslateComment(comment: TextUnitHistoryTimelineComment) {
  return comment.type === 'AI_TRANSLATE';
}

function parseLegacyAiTranslateJson(content: string): Record<string, unknown> | null {
  try {
    const parsed = JSON.parse(content) as unknown;
    if (isRecord(parsed) && parsed.kind === 'ai_translate') {
      return parsed;
    }
  } catch {
    return null;
  }
  return null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function asString(value: unknown) {
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

function formatTimelineUserName(userName: string, hasAiTranslateSignal: boolean) {
  const normalized = userName.trim();
  if (!hasAiTranslateSignal) {
    return normalized || '-';
  }

  const lowerCased = normalized.toLowerCase();
  if (
    !normalized ||
    lowerCased === '-' ||
    lowerCased === 'unknown user' ||
    lowerCased === 'nobody'
  ) {
    return 'AI Translate';
  }

  return normalized;
}

function AiTranslateAttempt({ attempt }: { attempt: TextUnitHistoryTimelineAiTranslateAttempt }) {
  const jsonItems = getAiTranslateJsonItems(attempt);
  const hasLinks = jsonItems.length > 0;
  const isLegacy = attempt.status === 'LEGACY';
  const [jsonPayload, setJsonPayload] = useState<{
    items: JsonPayloadModalItem[];
    activeItemKey: string;
  } | null>(null);
  const metadata = [
    ['Started', formatOptionalDateTime(attempt.createdDate)],
    ['Type', formatMetadataValue(attempt.translateType)],
    ['Model', formatMetadataValue(attempt.model)],
    ['Completion', formatMetadataValue(attempt.completionId)],
    ['Group', formatMetadataValue(attempt.requestGroupId)],
  ].filter((entry): entry is [string, string] => entry[1] != null);

  return (
    <section className="text-unit-history__ai-attempt">
      <div className="text-unit-history__ai-header">
        <span className="text-unit-history__ai-title">AI Translate</span>
        <span
          className={`text-unit-history__ai-status text-unit-history__ai-status--${getStatusClass(
            attempt.status,
          )}`}
        >
          {formatAiTranslateStatus(attempt.status)}
        </span>
      </div>
      {metadata.length > 0 ? (
        <dl className="text-unit-history__ai-meta">
          {metadata.map(([label, value]) => (
            <div key={`${attempt.key}-${label}`} className="text-unit-history__ai-meta-item">
              <dt>{label}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
      ) : null}
      {hasLinks || isLegacy ? (
        <div className="text-unit-history__ai-actions">
          {jsonItems.map((item) => (
            <button
              key={item.key}
              type="button"
              onClick={() => setJsonPayload({ items: jsonItems, activeItemKey: item.key })}
            >
              {item.label} JSON
            </button>
          ))}
          {!hasLinks && isLegacy ? (
            <span className="text-unit-history__ai-note">
              No normalized lineage details were found for this translation.
            </span>
          ) : null}
        </div>
      ) : null}
      {attempt.errorMessage ? (
        <div className="text-unit-history__ai-error">{attempt.errorMessage}</div>
      ) : null}
      <JsonPayloadModal
        open={Boolean(jsonPayload)}
        items={jsonPayload?.items ?? []}
        activeItemKey={jsonPayload?.activeItemKey ?? null}
        onActiveItemKeyChange={(activeItemKey) =>
          setJsonPayload((current) => (current ? { ...current, activeItemKey } : current))
        }
        onClose={() => setJsonPayload(null)}
      />
    </section>
  );
}

function getAiTranslateJsonItems(
  attempt: TextUnitHistoryTimelineAiTranslateAttempt,
): JsonPayloadModalItem[] {
  const items: JsonPayloadModalItem[] = [];
  if (attempt.detailsUrl) {
    items.push({
      key: 'details',
      label: 'Details',
      title: 'AI Translate details JSON',
      url: attempt.detailsUrl,
    });
  }
  if (attempt.requestPayloadUrl) {
    items.push({
      key: 'request',
      label: 'Request',
      title: 'AI Translate request JSON',
      url: attempt.requestPayloadUrl,
    });
  }
  if (attempt.responsePayloadUrl) {
    items.push({
      key: 'response',
      label: 'Response',
      title: 'AI Translate response JSON',
      url: attempt.responsePayloadUrl,
    });
  }
  return items;
}

function formatOptionalDateTime(value?: string | null) {
  return value ? formatLocalDateTime(value, value) : null;
}

function formatMetadataValue(value?: string | null) {
  const trimmed = value?.trim();
  return trimmed ? trimmed : null;
}

function formatAiTranslateStatus(status: string) {
  const value = status.trim();
  if (!value || value === '-') {
    return '-';
  }
  return value
    .toLowerCase()
    .replace(/_/g, ' ')
    .replace(/\b\w/g, (character) => character.toUpperCase());
}

function getStatusClass(status: string) {
  switch (status) {
    case 'IMPORTED':
      return 'imported';
    case 'RESPONDED':
      return 'responded';
    case 'FAILED':
      return 'failed';
    case 'REQUESTED':
      return 'requested';
    case 'LEGACY':
      return 'legacy';
    default:
      return 'unknown';
  }
}
