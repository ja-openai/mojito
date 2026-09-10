import type { VirtualItem } from '@tanstack/react-virtual';
import type { Ref, RefObject } from 'react';

import type { ApiReviewProjectTextUnit } from '../../api/review-projects';
import { VirtualList } from '../../components/virtual/VirtualList';
import { AgentReviewDiff } from './AgentReviewDiff';

export function AgentReviewTable({
  rows,
  selectedId,
  localeTag,
  scrollRef,
  items,
  totalSize,
  measureElement,
  onSelect,
}: {
  rows: ApiReviewProjectTextUnit[];
  selectedId: number | null;
  localeTag: string;
  scrollRef: RefObject<HTMLDivElement>;
  items: VirtualItem[];
  totalSize: number;
  measureElement: Ref<HTMLDivElement>;
  onSelect: (id: number, index: number) => void;
}) {
  return (
    <div
      className="agent-review-table"
      role="table"
      aria-label="Agent review findings"
      aria-rowcount={rows.length + 1}
    >
      <div className="agent-review-table__header" role="row" aria-rowindex={1}>
        <div role="columnheader">Source</div>
        <div role="columnheader">Proposed change</div>
        <div role="columnheader">Finding</div>
      </div>
      {rows.length === 0 ? (
        <p className="agent-review-table__empty">No findings match these filters.</p>
      ) : null}
      <VirtualList
        scrollRef={scrollRef}
        items={items}
        totalSize={totalSize}
        renderRow={(item) => {
          const row = rows[item.index];
          if (!row) return null;
          const proposal = row.agentReview;
          return {
            key: row.id,
            props: {
              ref: measureElement,
              role: 'row',
              'aria-rowindex': item.index + 2,
              className: `agent-review-table__row${row.id === selectedId ? ' is-selected' : ''}`,
              onClick: () => onSelect(row.id, item.index),
            },
            content: (
              <>
                <div role="cell" className="agent-review-table__source">
                  <button
                    type="button"
                    aria-current={row.id === selectedId ? 'true' : undefined}
                    onClick={(event) => {
                      event.stopPropagation();
                      onSelect(row.id, item.index);
                    }}
                  >
                    {row.tmTextUnit?.name ?? `Text unit ${row.id}`}
                  </button>
                  <div dir="auto">{proposal?.reviewedSource ?? row.tmTextUnit?.content ?? '—'}</div>
                </div>
                <div role="cell">
                  {proposal ? (
                    <AgentReviewDiff
                      original={proposal.reviewedTarget}
                      proposed={proposal.proposedTarget}
                      localeTag={localeTag}
                      compact
                    />
                  ) : (
                    <span>No agent proposal</span>
                  )}
                </div>
                <div role="cell" className="agent-review-table__finding">
                  <p>{proposal?.rationale ?? '—'}</p>
                  {proposal?.stale ? (
                    <span className="agent-review-table__badge">Changed since review</span>
                  ) : null}
                  {proposal?.canReconsider ? (
                    <span className="agent-review-table__badge">Agent responded</span>
                  ) : row.reviewProjectTextUnitDecision?.decisionState === 'DECIDED' ? (
                    <span className="agent-review-table__badge">Reviewed</span>
                  ) : null}
                </div>
              </>
            ),
          };
        }}
      />
    </div>
  );
}
