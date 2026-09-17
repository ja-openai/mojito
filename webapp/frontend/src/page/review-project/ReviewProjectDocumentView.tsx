import './review-project-document.css';

import { createElement, type ReactNode, useEffect, useMemo, useRef, useState } from 'react';

import type {
  ApiReviewProjectDocumentBlock,
  ApiReviewProjectDocuments,
  ApiReviewProjectTextUnit,
} from '../../api/review-projects';
import { ResizableMasterDetailLayout } from '../../components/ResizableMasterDetailLayout';
import { SingleSelectDropdown } from '../../components/SingleSelectDropdown';
import type { DocumentPreviewLink as PreviewLink } from './document-preview-link';
import { DocumentPreviewLink } from './DocumentPreviewLink';
import { groupEmailDocuments } from './email-document';
import { MdxMessagePreview } from './MdxMessagePreview';
import { getDecisionState } from './review-project-decision';
import { documentBlockRow } from './review-project-document';
import { buildDocumentNavigation, documentKey } from './review-project-document-navigation';
import { ReviewProjectDocumentNavigator } from './ReviewProjectDocumentNavigator';

type Props = {
  data: ApiReviewProjectDocuments | undefined;
  textUnits: ApiReviewProjectTextUnit[];
  selectedTextUnitId: number | null;
  showReviewAnnotations?: boolean;
  unsavedTextUnitIds?: number[];
  localeTag: string;
  loading: boolean;
  error: boolean;
  onRetry: () => void;
  onSelect: (rowId: number) => void;
  onNavigate?: () => boolean;
  navigationDisabled?: boolean;
  repositoryPreview?: {
    sourceLocaleTag: string;
    editing?: boolean;
    onEdit: (tmTextUnitId: number, anchor: HTMLElement) => void;
    onOpenAsset?: (assetPath: string) => Promise<void>;
  };
};

export function ReviewProjectDocumentView({
  data,
  textUnits,
  selectedTextUnitId,
  showReviewAnnotations = true,
  unsavedTextUnitIds = [],
  localeTag,
  loading,
  error,
  onRetry,
  onSelect,
  onNavigate,
  navigationDisabled = false,
  repositoryPreview,
}: Props) {
  const documentRef = useRef<HTMLDivElement>(null);
  const selectedOccurrenceRef = useRef<DocumentOccurrence | null>(null);
  const [repositoryOccurrence, setRepositoryOccurrence] = useState<DocumentOccurrence | null>(null);
  const [linkMessage, setLinkMessage] = useState<string | null>(null);
  const linkRequest = useRef<symbol | null>(null);
  const rows = useMemo(() => new Map(textUnits.map((row) => [row.id, row])), [textUnits]);
  const repositoryMode = repositoryPreview != null;
  const sourceLocale = repositoryPreview?.sourceLocaleTag === localeTag;
  const entries = useMemo(
    () =>
      data
        ? buildDocumentNavigation(
            { ...data, documents: groupEmailDocuments(data.documents) },
            textUnits,
          )
        : [],
    [data, textUnits],
  );
  const [activeKey, setActiveKey] = useState<string | null>(null);
  const activeEntry =
    entries.find((entry) => entry.key === activeKey) ??
    entries.find((entry) => !entry.isModule) ??
    entries[0];
  const visibleKey = activeEntry?.key;
  useEffect(() => {
    setLinkMessage(null);
    linkRequest.current = null;
    return () => {
      linkRequest.current = null;
    };
  }, [visibleKey, localeTag]);
  const showNavigator = !repositoryPreview && entries.length > 1;
  const nodes = useMemo(
    () => documentNodes(activeEntry?.document.blocks ?? []),
    [activeEntry?.document.blocks],
  );
  const [choiceValues, setChoiceValues] = useState<Record<string, string>>({});
  const visibleChoices = useMemo(() => {
    const values: Record<string, string> = {};
    const pending = [...nodes];
    while (pending.length) {
      const node = pending.pop()!;
      pending.push(...node.children);
      if (!node.choice || !node.choiceComplete) continue;
      const key = `${visibleKey}:${node.occurrenceId}`;
      let selected =
        node.children.find((child) => child.occurrenceId === choiceValues[key]) ?? node.children[0];
      if (
        !repositoryMode &&
        selectedTextUnitId != null &&
        !nodeReviewRows(selected, rows).has(selectedTextUnitId)
      ) {
        selected =
          node.children.find((child) => nodeReviewRows(child, rows).has(selectedTextUnitId)) ??
          selected;
      }
      values[key] = selected.occurrenceId;
    }
    return values;
  }, [choiceValues, nodes, repositoryMode, rows, selectedTextUnitId, visibleKey]);
  useEffect(() => {
    if (selectedTextUnitId == null) return;
    if (Object.entries(visibleChoices).some(([key, value]) => choiceValues[key] !== value)) {
      setChoiceValues((previous) => ({ ...previous, ...visibleChoices }));
    }
  }, [choiceValues, selectedTextUnitId, visibleChoices]);
  useEffect(() => {
    if (visibleKey && activeKey !== visibleKey) setActiveKey(visibleKey);
  }, [activeKey, visibleKey]);
  useEffect(() => {
    if (documentRef.current) documentRef.current.scrollTop = 0;
  }, [visibleKey]);
  useEffect(() => {
    if (selectedTextUnitId == null) return;
    const containsSelection = (entry: (typeof entries)[number]) =>
      entry.document.blocks.some(
        (block) =>
          (repositoryMode ? block.tmTextUnitId : documentBlockRow(block, rows)?.id) ===
          selectedTextUnitId,
      );
    const candidates = entries.filter(containsSelection);
    const previous = selectedOccurrenceRef.current;
    const next =
      candidates.find((entry) => entry.key === previous?.documentKey) ??
      candidates.find((entry) => entry.key === visibleKey) ??
      candidates.find((entry) => !entry.isModule) ??
      candidates[0];
    if (next && next.key !== visibleKey) setActiveKey(next.key);
  }, [entries, repositoryMode, rows, selectedTextUnitId, visibleKey]);
  useEffect(() => {
    if (selectedTextUnitId == null || repositoryMode) return;
    const selector = `[data-review-row-id="${selectedTextUnitId}"]`;
    const previous = selectedOccurrenceRef.current;
    const candidates = Array.from(
      documentRef.current?.querySelectorAll<HTMLElement>(selector) ?? [],
    ).filter((element) => !element.closest('[hidden]'));
    const inPreviousDocument = (element: HTMLElement) =>
      element.closest('article')?.dataset.documentKey === previous?.documentKey;
    const next =
      candidates.find(
        (element) =>
          inPreviousDocument(element) && element.dataset.occurrenceId === previous?.occurrenceId,
      ) ??
      candidates.find(
        (element) =>
          inPreviousDocument(element) &&
          previous?.moduleOccurrenceId != null &&
          element.closest<HTMLElement>('[data-module-occurrence-id]')?.dataset
            .moduleOccurrenceId === previous.moduleOccurrenceId,
      ) ??
      candidates.find(inPreviousDocument) ??
      candidates[0];
    if (next) {
      selectedOccurrenceRef.current = documentOccurrence(next);
      next.scrollIntoView?.({ block: 'nearest' });
    }
  }, [data, repositoryMode, selectedTextUnitId, visibleChoices, visibleKey]);
  const reader = (
    <div className="review-project-document__reader" ref={documentRef}>
      {linkMessage ? (
        <p role="status" className="review-project-document__hint">
          {linkMessage}
        </p>
      ) : null}
      {loading ? <p role="status">Loading document context…</p> : null}
      {error ? (
        <div role="alert" className="review-project-document__warning">
          Could not load document context.{' '}
          <button type="button" onClick={onRetry}>
            Try again
          </button>
        </div>
      ) : null}
      {data?.warnings.map((warning, index) => (
        <p className="review-project-document__warning" key={index}>
          {warning}
        </p>
      ))}
      {!loading && !error && data?.documents.length === 0 ? (
        <p>
          No retained MDX document is available for this{' '}
          {repositoryPreview ? 'repository' : 'review project'}.
        </p>
      ) : null}
      {(activeEntry ? [activeEntry.document] : []).map((document) => {
        const currentDocumentKey = documentKey(document);
        const followLink = async (link: PreviewLink) => {
          if (navigationDisabled) return;
          const request = Symbol();
          linkRequest.current = request;
          if (link.type === 'fragment') {
            setLinkMessage(null);
            if (!link.fragment) {
              if (documentRef.current) documentRef.current.scrollTop = 0;
              return;
            }
            const target = Array.from(
              documentRef.current?.querySelectorAll<HTMLElement>('[data-block-id]') ?? [],
            )
              .filter((element) => !element.closest('[hidden]'))
              .find(
                (element) =>
                  element.dataset.blockId === link.fragment ||
                  element
                    .querySelector('h1,h2,h3,h4,h5,h6')
                    ?.textContent?.toLowerCase()
                    .trim()
                    .replace(/[^\p{L}\p{N}\s-]/gu, '')
                    .replace(/\s+/g, '-') === link.fragment,
              );
            if (target) target.scrollIntoView?.({ block: 'start' });
            else setLinkMessage('This section is not available in the preview.');
            return;
          }
          if (link.type !== 'asset') return;
          if (repositoryPreview?.onOpenAsset) {
            setLinkMessage('Opening linked page…');
            try {
              await repositoryPreview.onOpenAsset(link.assetPath);
              if (request === linkRequest.current) setLinkMessage(null);
            } catch {
              if (request === linkRequest.current)
                setLinkMessage(
                  `Could not open “${link.assetPath}”. It may not be available on this branch.`,
                );
            }
          } else {
            const entry = entries.find(
              (candidate) =>
                candidate.assetPath === link.assetPath &&
                candidate.repositoryId === document.repositoryId &&
                candidate.branchName === document.branchName,
            );
            if (!entry) {
              setLinkMessage(`“${link.assetPath}” is not included in this preview.`);
            } else if (onNavigate?.() !== false) {
              selectedOccurrenceRef.current = null;
              setLinkMessage(null);
              setActiveKey(entry.key);
            }
          }
        };

        const renderNode = (node: DocumentNode): ReactNode => {
          const { block, occurrenceId, children } = node;
          if (block.type === 'email-part') {
            return (
              <section
                key={occurrenceId}
                className={`review-project-document__email-part review-project-document__email-part--${block.source.toLowerCase()}`}
                aria-label={`Email ${block.source.toLowerCase()}`}
              >
                <div className="review-project-document__email-label">{block.source}</div>
                {children.map(renderNode)}
              </section>
            );
          }
          if (node.choice) {
            if (!node.choiceComplete) {
              return (
                <section key={occurrenceId} className="review-project-document__choice">
                  <p className="review-project-document__warning">
                    Incomplete preview choice — showing all available variants.
                  </p>
                  {children.map(renderNode)}
                </section>
              );
            }
            const key = `${currentDocumentKey}:${occurrenceId}`;
            const selected = visibleChoices[key];
            return (
              <section
                key={occurrenceId}
                className="review-project-document__choice"
                aria-label="Conditional content"
              >
                <div
                  className="review-project-document__choice-control"
                  // Portal events follow the React tree too; keep picker keys out of review shortcuts.
                  onKeyDown={(event) => event.stopPropagation()}
                >
                  <span>Preview variant</span>
                  <SingleSelectDropdown<string>
                    label="Preview variant"
                    value={selected}
                    searchable={false}
                    disabled={navigationDisabled}
                    onChange={(value) => {
                      if (value == null || navigationDisabled || onNavigate?.() === false) return;
                      setChoiceValues((previous) => ({ ...previous, [key]: value }));
                    }}
                    options={children.map((child) => {
                      let title = choiceTitle(
                        child,
                        rows,
                        repositoryPreview ? (sourceLocale ? 'source' : 'target') : undefined,
                      );
                      if (!repositoryPreview) {
                        const childRows = [...nodeReviewRows(child, rows).values()];
                        const reviewed = childRows.filter(
                          (row) => getDecisionState(row) === 'DECIDED',
                        ).length;
                        title += ` · ${reviewed}/${childRows.length} reviewed`;
                      }
                      return { value: child.occurrenceId, label: title };
                    })}
                  />
                </div>
                {children.map((child) => (
                  <div key={child.occurrenceId} hidden={selected !== child.occurrenceId}>
                    {renderNode(child)}
                  </div>
                ))}
              </section>
            );
          }
          if (block.type === 'component' && block.moduleStatus != null) {
            const componentName = block.source.match(/<([A-Za-z][\w.]*)\b/)?.[1] ?? 'Module';
            return (
              <section
                className="review-project-document__module"
                key={occurrenceId}
                data-occurrence-id={occurrenceId}
                data-module-occurrence-id={occurrenceId}
                data-asset-id={block.assetId ?? document.assetId}
                aria-label={`${componentName} module${block.modulePath ? `: ${block.modulePath}` : ''}`}
              >
                <header
                  className="review-project-document__module-header"
                  title={block.modulePath ?? undefined}
                >
                  <span>{componentName}</span>
                </header>
                {block.moduleWarning || block.moduleStatus === 'UNAVAILABLE' ? (
                  <p className="review-project-document__warning">
                    {block.moduleWarning || 'Module preview is unavailable.'}
                  </p>
                ) : null}
                {children.length > 0 ? (
                  <div className="review-project-document__module-content">
                    {children.map(renderNode)}
                  </div>
                ) : null}
              </section>
            );
          }
          const row = repositoryPreview ? null : documentBlockRow(block, rows);
          const repositoryTextUnitId = repositoryPreview ? repositoryBlockTextUnitId(block) : null;
          const stale =
            block.mappingStatus === 'SOURCE_CHANGED' ||
            (!repositoryPreview && block.mappingStatus === 'MATCHED' && !row);
          const variant =
            row?.currentTmTextUnitVariant?.id != null
              ? row.currentTmTextUnitVariant
              : row?.baselineTmTextUnitVariant;
          const target = repositoryPreview
            ? repositoryTextUnitId != null && !sourceLocale
              ? block.targetContent
              : null
            : variant?.content;
          const hasTranslation = target != null && target.length > 0;
          const value = hasTranslation ? target : block.source;
          const reviewState = row ? getDecisionState(row) : null;
          const status = repositoryPreview
            ? stale
              ? 'Source changed — document context cannot be edited'
              : repositoryTextUnitId == null
                ? 'Text unit unavailable — source context'
                : !hasTranslation
                  ? 'Source fallback — untranslated'
                  : block.targetStatus === 'TRANSLATION_NEEDED'
                    ? 'Translation needed'
                    : 'Review needed'
            : stale
              ? 'Source changed — document context cannot be reviewed'
              : row
                ? reviewState === 'DECIDED'
                  ? 'Reviewed'
                  : 'Pending review'
                : block.translatable
                  ? 'Not in this review project — source context'
                  : block.type === 'component'
                    ? 'Component — source context'
                    : 'Source context';
          const showStatus = repositoryPreview
            ? stale ||
              (!sourceLocale &&
                block.translatable &&
                (repositoryTextUnitId == null ||
                  !hasTranslation ||
                  block.targetStatus === 'REVIEW_NEEDED' ||
                  block.targetStatus === 'TRANSLATION_NEEDED'))
            : showReviewAnnotations || stale || (block.translatable && !row);
          const editable = repositoryPreview
            ? !sourceLocale && repositoryTextUnitId != null
            : row != null;
          const clickable = editable && repositoryPreview?.editing !== false;
          const selected =
            editable &&
            (repositoryPreview ? repositoryTextUnitId : row?.id) === selectedTextUnitId &&
            (!repositoryPreview ||
              (repositoryOccurrence?.documentKey === currentDocumentKey &&
                repositoryOccurrence.occurrenceId === occurrenceId));
          const selectPassage = (anchor: HTMLElement) => {
            const occurrence = documentOccurrence(anchor);
            selectedOccurrenceRef.current = occurrence;
            if (repositoryPreview && repositoryTextUnitId != null) {
              setRepositoryOccurrence(occurrence);
              repositoryPreview.onEdit(repositoryTextUnitId, anchor);
            } else if (row) {
              onSelect(row.id);
            }
          };
          const assetContext =
            (block.moduleDepth ?? 0) > 0 ||
            (block.assetPath != null && block.assetPath !== document.assetPath)
              ? ` in ${block.assetPath ?? document.assetPath}`
              : '';
          return (
            <div
              className={`review-project-document__block${clickable ? ' is-reviewable' : ''}${selected ? ' is-selected' : ''}${stale ? ' is-stale' : ''}${showReviewAnnotations && reviewState ? (reviewState === 'DECIDED' ? ' is-reviewed' : ' is-pending-review') : ''}`}
              key={occurrenceId}
              data-block-id={block.id ?? undefined}
              data-occurrence-id={occurrenceId}
              data-asset-id={block.assetId ?? document.assetId}
              data-review-row-id={row?.id}
              data-tm-text-unit-id={repositoryTextUnitId ?? undefined}
              data-content-editable={repositoryPreview && editable ? 'true' : undefined}
              role={clickable ? 'button' : undefined}
              tabIndex={clickable ? 0 : undefined}
              aria-label={
                clickable
                  ? `${repositoryPreview ? 'Edit' : 'Review'} ${block.id ?? `passage at line ${block.line}`}${assetContext}`
                  : undefined
              }
              aria-pressed={clickable ? selected : undefined}
              aria-description={
                row ? (reviewState === 'DECIDED' ? 'Reviewed' : 'Pending review') : undefined
              }
              onClick={
                clickable
                  ? (event) => {
                      selectPassage(event.currentTarget);
                    }
                  : undefined
              }
              onDoubleClick={
                editable && repositoryPreview?.editing === false
                  ? (event) => selectPassage(event.currentTarget)
                  : undefined
              }
              onKeyDown={
                clickable
                  ? (event) => {
                      if (event.target !== event.currentTarget) return;
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault();
                        event.stopPropagation();
                        selectPassage(event.currentTarget);
                      }
                    }
                  : undefined
              }
            >
              <div
                dir="auto"
                lang={
                  hasTranslation
                    ? localeTag
                    : (repositoryPreview?.sourceLocaleTag ?? document.sourceLocaleTag ?? undefined)
                }
              >
                {blockContent(
                  block,
                  value,
                  hasTranslation
                    ? localeTag
                    : (repositoryPreview?.sourceLocaleTag ?? document.sourceLocaleTag),
                  (label, href, key) => (
                    <DocumentPreviewLink
                      key={key}
                      label={label}
                      href={href}
                      assetPath={document.assetPath}
                      editing={clickable}
                      disabled={navigationDisabled}
                      onFollow={followLink}
                    />
                  ),
                )}
              </div>
              {showStatus ? (
                row ? (
                  <span
                    className="review-project-document__decision-status"
                    title={status}
                    aria-hidden="true"
                  >
                    {reviewState === 'DECIDED' ? '✓' : '•'}
                  </span>
                ) : (
                  <span className="review-project-document__status">{status}</span>
                )
              ) : null}
              {row && !hasTranslation ? (
                <span className="review-project-document__status">
                  Source fallback — untranslated
                </span>
              ) : null}
              {row && unsavedTextUnitIds.includes(row.id) ? (
                <span className="review-project-document__status">Unsaved changes</span>
              ) : null}
              {block.moduleWarning ? (
                <p className="review-project-document__warning">{block.moduleWarning}</p>
              ) : null}
            </div>
          );
        };
        return (
          <article
            className={`review-project-document__page${nodes.some((node) => node.block.type === 'email-part') ? ' review-project-document__page--email' : ''}`}
            key={`${currentDocumentKey}:${document.sourceContentMd5}`}
            data-document-key={currentDocumentKey}
            aria-label={document.assetPath}
            aria-description={document.branchName ? `Branch: ${document.branchName}` : undefined}
          >
            {document.warnings.map((warning, index) => (
              <p className="review-project-document__warning" key={index}>
                {warning}
              </p>
            ))}
            {nodes.map(renderNode)}
          </article>
        );
      })}
    </div>
  );
  return (
    <div
      className="review-project-document"
      aria-label={repositoryPreview ? 'Content preview' : 'Document review'}
    >
      {showNavigator ? (
        <ResizableMasterDetailLayout
          className="review-project-document__layout"
          storageKey="mojito.review.documentNavigatorWidth"
          sidebarLabel="Pages and modules"
          detailLabel="Page preview"
          resizeLabel="Resize pages and modules"
          sidebarClassName="review-project-document__navigation-pane"
          detailClassName="review-project-document__reader-pane"
          defaultSidebarWidthPercent={24}
          minSidebarWidthPercent={16}
          maxSidebarWidthPercent={40}
          sidebar={
            <ReviewProjectDocumentNavigator
              entries={entries}
              activeKey={visibleKey ?? null}
              disabled={navigationDisabled}
              onOpen={(key) => {
                if (key === visibleKey || navigationDisabled || onNavigate?.() === false) return;
                setActiveKey(key);
              }}
            />
          }
          detail={reader}
        />
      ) : (
        reader
      )}
    </div>
  );
}

type DocumentOccurrence = {
  documentKey: string | undefined;
  occurrenceId: string | undefined;
  moduleOccurrenceId: string | undefined;
};

function documentOccurrence(element: HTMLElement): DocumentOccurrence {
  return {
    documentKey: element.closest('article')?.dataset.documentKey,
    occurrenceId: element.dataset.occurrenceId,
    moduleOccurrenceId: element.closest<HTMLElement>('[data-module-occurrence-id]')?.dataset
      .moduleOccurrenceId,
  };
}

type DocumentNode = {
  block: ApiReviewProjectDocumentBlock;
  occurrenceId: string;
  children: DocumentNode[];
  choice?: boolean;
  choiceComplete?: boolean;
};

function nodeReviewRows(node: DocumentNode, rows: ReadonlyMap<number, ApiReviewProjectTextUnit>) {
  const matched = new Map<number, ApiReviewProjectTextUnit>();
  const pending = [node];
  while (pending.length) {
    const current = pending.pop()!;
    const row = documentBlockRow(current.block, rows);
    if (row) matched.set(row.id, row);
    pending.push(...current.children);
  }
  return matched;
}

function repositoryBlockTextUnitId(block: ApiReviewProjectDocumentBlock): number | null {
  return block.translatable &&
    block.mappingStatus === 'MATCHED' &&
    block.tmTextUnitId != null &&
    Number.isSafeInteger(block.tmTextUnitId) &&
    block.tmTextUnitId > 0
    ? block.tmTextUnitId
    : null;
}

function choiceTitle(
  node: DocumentNode,
  rows: ReadonlyMap<number, ApiReviewProjectTextUnit>,
  repositoryLocale?: 'source' | 'target',
): string {
  const heading = node.children.find((child) => child.block.type === 'heading')?.block;
  if (!heading) return node.block.source.match(/<([A-Za-z][\w.]*)\b/)?.[1] ?? 'Variant';
  if (repositoryLocale) {
    const target =
      repositoryLocale === 'target' && repositoryBlockTextUnitId(heading) != null
        ? heading.targetContent
        : null;
    return (target || heading.source).replace(/[*_`]/g, '');
  }
  const row = documentBlockRow(heading, rows);
  const variant =
    row?.currentTmTextUnitVariant?.id != null
      ? row.currentTmTextUnitVariant
      : row?.baselineTmTextUnitVariant;
  return (variant?.content || heading.source).replace(/[*_`]/g, '');
}

function groupChoices(nodes: DocumentNode[]): DocumentNode[] {
  const grouped: DocumentNode[] = [];
  for (let index = 0; index < nodes.length; index++) {
    const node = nodes[index];
    if (node.block.type === 'component' && /^<PreviewChoice\s*>$/.test(node.block.source.trim())) {
      const closing = nodes.findIndex(
        (candidate, offset) =>
          offset > index &&
          candidate.block.type === 'component' &&
          /^<\/PreviewChoice\s*>$/.test(candidate.block.source.trim()),
      );
      const children = nodes.slice(index + 1, closing < 0 ? undefined : closing);
      grouped.push({
        ...node,
        choice: true,
        choiceComplete:
          closing >= 0 &&
          children.length >= 2 &&
          children.length <= 8 &&
          children.every(
            (child) =>
              child.block.type === 'component' &&
              child.block.moduleStatus != null &&
              /^<[A-Za-z][\w.]*\s*\/>$/.test(child.block.source.trim()),
          ),
        children: groupChoices(children),
      });
      if (closing < 0) break;
      index = closing;
    } else {
      grouped.push({ ...node, children: groupChoices(node.children) });
    }
  }
  return grouped;
}

// The endpoint supplies ordered, flat blocks. Only expanded module boundaries own
// deeper blocks; source heading/list depth is independent of module nesting.
function documentNodes(blocks: ApiReviewProjectDocumentBlock[]): DocumentNode[] {
  const nodes: DocumentNode[] = [];
  const parents = [{ depth: -1, children: nodes }];
  blocks.forEach((block, index) => {
    const depth = block.moduleDepth ?? 0;
    while (parents.length > 1 && parents[parents.length - 1].depth >= depth) {
      parents.pop();
    }
    const node: DocumentNode = {
      block,
      occurrenceId: block.occurrenceId ?? `${block.id ?? block.line}:${index}`,
      children: [],
    };
    parents[parents.length - 1].children.push(node);
    if (
      (block.type === 'component' || block.type === 'email-part') &&
      block.moduleStatus === 'EXPANDED'
    ) {
      parents.push({ depth, children: node.children });
    }
  });
  return groupChoices(nodes);
}

function blockContent(
  block: ApiReviewProjectDocumentBlock,
  value: string,
  locale?: string | null,
  renderLink?: (label: string, href: string, key: number) => ReactNode,
): ReactNode {
  switch (block.type) {
    case 'mf2':
      return <MdxMessagePreview value={value} args={block.previewArgs} locale={locale} />;
    case 'heading':
      return createElement(
        `h${Math.max(1, Math.min(6, block.depth || 1))}`,
        null,
        inlineMarkdown(value, renderLink),
      );
    case 'list-item':
      return (
        <div
          className="review-project-document__list-item"
          style={{ paddingInlineStart: `${Math.min(6, Math.max(0, block.depth)) * 0.75}rem` }}
        >
          <span aria-hidden="true">
            {block.marker?.trim().match(/^\d+[.)]$/) ? block.marker.trim() : '•'}
          </span>
          <span>{inlineMarkdown(value, renderLink)}</span>
        </div>
      );
    case 'blockquote':
      return <blockquote>{inlineMarkdown(value, renderLink)}</blockquote>;
    case 'code':
    case 'component':
      return (
        <pre>
          <code>{value}</code>
        </pre>
      );
    case 'thematic-break':
      return <hr />;
    default:
      return <p>{inlineMarkdown(value, renderLink)}</p>;
  }
}

// Render a small Markdown subset as React text. MDX expressions, HTML, images
// and components remain literal. Only explicitly supported links are interactive.
function inlineMarkdown(
  value: string,
  renderLink?: (label: string, href: string, key: number) => ReactNode,
): ReactNode[] {
  const pattern = /`([^`\n]+)`|\*\*([^*\n]+)\*\*|\*([^*\n]+)\*|(?<!!)\[([^\]\n]+)\]\(([^)\n]+)\)/g;
  const parts: ReactNode[] = [];
  let cursor = 0;
  for (const match of value.matchAll(pattern)) {
    const index = match.index ?? 0;
    parts.push(value.slice(cursor, index));
    parts.push(
      match[1] != null ? (
        <code key={index}>{match[1]}</code>
      ) : match[2] != null ? (
        <strong key={index}>{match[2]}</strong>
      ) : match[3] != null ? (
        <em key={index}>{match[3]}</em>
      ) : renderLink ? (
        renderLink(match[4], match[5], index)
      ) : (
        <span className="review-project-document__link" title={match[5]} key={index}>
          {match[4]}
        </span>
      ),
    );
    cursor = index + match[0].length;
  }
  parts.push(value.slice(cursor));
  return parts;
}
