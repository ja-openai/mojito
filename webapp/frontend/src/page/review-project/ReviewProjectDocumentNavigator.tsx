import './review-project-document-navigation.css';

import { useEffect, useId, useMemo, useRef, useState } from 'react';

import { FileTree, type FileTreeItem, FileTreeRow } from '../../components/FileTree';
import {
  buildReviewDocumentTree,
  normalizeDocumentSearch,
  type ReviewDocumentTreeNode,
  type ReviewProjectDocumentNavigationEntry,
} from './review-project-document-navigation';

type Props = {
  entries: ReviewProjectDocumentNavigationEntry[];
  activeKey: string | null;
  onOpen: (key: string) => void;
  disabled?: boolean;
};

function ancestorsOf(parents: ReadonlyMap<string, string>, key: string | null): string[] {
  const ancestors: string[] = [];
  let parent = key == null ? undefined : parents.get(key);
  while (parent !== undefined) {
    ancestors.push(parent);
    parent = parents.get(parent);
  }
  return ancestors;
}

function matchesSearch(entry: ReviewProjectDocumentNavigationEntry, query: string) {
  return normalizeDocumentSearch(query)
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .every((term) => entry.searchText.includes(term));
}

export function ReviewProjectDocumentNavigator({
  entries,
  activeKey,
  onOpen,
  disabled = false,
}: Props) {
  const contentId = useId();
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [rootExpanded, setRootExpanded] = useState(true);
  const tree = useMemo(() => buildReviewDocumentTree(entries), [entries]);
  const treeParents = useMemo(() => {
    const parents = new Map<string, string>();
    const pending = [...tree];
    while (pending.length) {
      const node = pending.pop()!;
      if ('children' in node) {
        for (const child of node.children) {
          parents.set(child.key, node.key);
          pending.push(child);
        }
      }
    }
    return parents;
  }, [tree]);
  const [expanded, setExpanded] = useState(
    () =>
      new Set([
        ...tree.map((node) => node.key),
        ...tree.flatMap((node) =>
          'children' in node
            ? node.children
                .filter((child) => 'kind' in child && child.kind === 'branch')
                .map((child) => child.key)
            : [],
        ),
        ...ancestorsOf(treeParents, activeKey),
      ]),
  );
  const [searchCollapsed, setSearchCollapsed] = useState(new Set<string>());
  const previousActiveKey = useRef<string | null>(null);
  const active = entries.find((entry) => entry.key === activeKey);
  useEffect(() => {
    if (previousActiveKey.current === activeKey) return;
    previousActiveKey.current = activeKey;
    if (activeKey == null) return;
    const ancestors = ancestorsOf(treeParents, activeKey);
    setExpanded((previous) => new Set([...previous, ...ancestors]));
    setSearchCollapsed(
      (previous) => new Set([...previous].filter((key) => !ancestors.includes(key))),
    );
    setRootExpanded(true);
    setQuery((previous) => (active && !matchesSearch(active, previous) ? '' : previous));
  }, [active, activeKey, treeParents]);
  const matches = useMemo(
    () => entries.filter((entry) => matchesSearch(entry, query)),
    [entries, query],
  );
  const matchingKeys = useMemo(() => {
    const keys = new Set<string>();
    for (const entry of matches) {
      keys.add(entry.key);
      for (const parent of ancestorsOf(treeParents, entry.key)) keys.add(parent);
    }
    return keys;
  }, [matches, treeParents]);
  const assetsWithMultipleBranches = useMemo(() => {
    const firstBranch = new Map<string, string>();
    const multipleBranches = new Set<string>();
    for (const entry of entries) {
      const assetKey = JSON.stringify([entry.repositoryId, entry.document.assetId]);
      const branchKey = JSON.stringify([entry.document.branchId ?? null, entry.branchName]);
      if (firstBranch.has(assetKey) && firstBranch.get(assetKey) !== branchKey) {
        multipleBranches.add(assetKey);
      }
      firstBranch.set(assetKey, branchKey);
    }
    return multipleBranches;
  }, [entries]);
  const parents = entries.filter((entry) => active?.parentKeys.includes(entry.key));
  const parentPages = parents.filter((entry) => !entry.isModule);
  const visibleParents = parentPages.length > 0 ? parentPages : parents;
  const openEntry = (entry: ReviewProjectDocumentNavigationEntry) => {
    onOpen(entry.key);
    setOpen(false);
  };
  const entryButton = (entry: ReviewProjectDocumentNavigationEntry, parent = false) => {
    const hasOtherBranch = assetsWithMultipleBranches.has(
      JSON.stringify([entry.repositoryId, entry.document.assetId]),
    );
    const branchLabel =
      entry.branchName ||
      (hasOtherBranch ? (entry.branchName === null ? 'Default branch' : 'Unnamed branch') : null);
    return (
      <FileTreeRow
        label={parent ? entry.title : entry.assetPath.split('/').pop() || entry.assetPath}
        ariaLabel={`Open ${parent ? 'parent ' : ''}${entry.title} (${entry.assetPath})${branchLabel ? ` on ${branchLabel}` : ''}`}
        selected={!parent && entry.key === activeKey}
        current="page"
        title={`${entry.assetPath}${branchLabel ? ` · ${branchLabel}` : ''}`}
        onSelect={() => openEntry(entry)}
        disabled={disabled}
      />
    );
  };
  const items: FileTreeItem[] = [
    {
      key: 'review-files-root',
      depth: 0,
      content: (
        <FileTreeRow
          label="/"
          ariaLabel="Review files"
          expanded={rootExpanded}
          onToggle={() => setRootExpanded((previous) => !previous)}
          toggleLabel={`${rootExpanded ? 'Collapse' : 'Expand'} review files`}
          onSelect={() => setRootExpanded((previous) => !previous)}
        />
      ),
    },
  ];
  const pending: { node: ReviewDocumentTreeNode; depth: number }[] = rootExpanded
    ? tree.map((node) => ({ node, depth: 1 })).reverse()
    : [];
  while (pending.length) {
    const { node, depth } = pending.pop()!;
    if (!matchingKeys.has(node.key)) continue;
    if ('entry' in node) {
      items.push({
        key: node.key,
        depth,
        selected: node.key === activeKey,
        focusable: !disabled,
        content: entryButton(node.entry),
      });
      continue;
    }
    const isExpanded = query.trim() ? !searchCollapsed.has(node.key) : expanded.has(node.key);
    const toggle = () => {
      const setter = query.trim() ? setSearchCollapsed : setExpanded;
      setter((previous) => {
        const next = new Set(previous);
        if (next.has(node.key)) next.delete(node.key);
        else next.add(node.key);
        return next;
      });
    };
    items.push({
      key: node.key,
      depth,
      content: (
        <FileTreeRow
          label={node.label}
          ariaLabel={node.label}
          expanded={isExpanded}
          onToggle={toggle}
          toggleLabel={`${isExpanded ? 'Collapse' : 'Expand'} ${node.label}`}
          onSelect={toggle}
        />
      ),
    });
    if (isExpanded) {
      for (let index = node.children.length - 1; index >= 0; index--) {
        pending.push({ node: node.children[index], depth: depth + 1 });
      }
    }
  }
  return (
    <div className="review-project-document-navigation" data-expanded={open}>
      <button
        type="button"
        className="review-project-document-navigation__toggle"
        aria-label="Browse pages and modules"
        aria-expanded={open}
        aria-controls={contentId}
        onClick={() => setOpen((previous) => !previous)}
      >
        <span>Browse</span>
        {active ? (
          <span className="review-project-document-navigation__active-title" dir="auto">
            {active.assetPath.split('/').pop()}
          </span>
        ) : null}
      </button>
      <div className="review-project-document-navigation__content" id={contentId}>
        <div className="review-project-document-navigation__search">
          <input
            type="search"
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
              setSearchCollapsed(new Set());
              setRootExpanded(true);
            }}
            aria-label="Search pages and modules"
            placeholder="Search…"
          />
          {query ? (
            <button type="button" onClick={() => setQuery('')} aria-label="Clear page search">
              ×
            </button>
          ) : null}
        </div>
        <nav aria-label="Review pages and modules">
          <FileTree items={items} ariaLabel="Review files tree" revealKey={activeKey} />
          {matches.length === 0 ? (
            <p role="status" className="review-project-document-navigation__empty">
              No pages or modules match your search.
            </p>
          ) : null}
        </nav>
        {active?.isModule && visibleParents.length > 0 ? (
          <section className="review-project-document-navigation__parents" aria-label="Used in">
            <h3>Used in</h3>
            <ul>
              {visibleParents.map((entry) => (
                <li key={entry.key}>{entryButton(entry, true)}</li>
              ))}
            </ul>
          </section>
        ) : null}
      </div>
    </div>
  );
}
