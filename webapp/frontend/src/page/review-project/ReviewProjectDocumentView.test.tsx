import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import type {
  ApiReviewProjectDocumentBlock,
  ApiReviewProjectDocuments,
  ApiReviewProjectTextUnit,
} from '../../api/review-projects';
import type * as FileTreeModule from '../../components/FileTree';
import { documentReviewRows } from './review-project-document';
import { ReviewProjectDocumentView } from './ReviewProjectDocumentView';

// Keep document-navigation behavior independent of virtual-list viewport measurement.
vi.mock('../../components/FileTree', async (importOriginal) => {
  const actual = await importOriginal<typeof FileTreeModule>();
  return {
    ...actual,
    FileTree: ({
      items,
      ariaLabel,
    }: {
      items: FileTreeModule.FileTreeItem[];
      ariaLabel: string;
    }) => (
      <div role="list" aria-label={ariaLabel}>
        {items.map((item) => (
          <div role="listitem" key={item.key}>
            {item.content}
          </div>
        ))}
      </div>
    ),
  };
});

const row: ApiReviewProjectTextUnit = {
  id: 11,
  tmTextUnit: { id: 21, name: 'intro', content: 'Welcome **everyone**' },
  baselineTmTextUnitVariant: { id: 31, content: 'Bienvenue **à tous**' },
  currentTmTextUnitVariant: null,
};

const heading: ApiReviewProjectDocumentBlock = {
  id: 'intro',
  type: 'heading',
  depth: 1,
  source: 'Welcome **everyone**',
  line: 1,
  translatable: true,
  reviewProjectTextUnitId: 11,
  tmTextUnitId: 21,
  mappingStatus: 'MATCHED',
};

const moduleBlock: ApiReviewProjectDocumentBlock = {
  id: null,
  type: 'component',
  depth: 0,
  source: '<Intro />',
  line: 3,
  translatable: false,
  reviewProjectTextUnitId: null,
  tmTextUnitId: null,
  mappingStatus: 'CONTEXT',
  assetId: 1,
  assetPath: 'guide.mdx',
  moduleDepth: 0,
  occurrenceId: 'guide/intro-module',
  moduleStatus: 'EXPANDED',
  modulePath: 'shared/intro.mdx',
};

const includedHeading: ApiReviewProjectDocumentBlock = {
  ...heading,
  assetId: 2,
  assetPath: 'shared/intro.mdx',
  moduleDepth: 1,
  occurrenceId: 'guide/intro-module/intro',
};

const documentData = (blocks: ApiReviewProjectDocumentBlock[]): ApiReviewProjectDocuments => ({
  documents: [
    {
      assetId: 1,
      repositoryId: 2,
      assetPath: 'guide.mdx',
      branchName: 'master',
      sourceContentMd5: 'revision-a',
      blocks,
      warnings: [],
    },
  ],
  warnings: [],
});

function renderDocument(blocks = [heading], textUnits = [row]) {
  const onSelect = vi.fn();
  const props = {
    data: documentData(blocks),
    textUnits,
    selectedTextUnitId: null,
    localeTag: 'fr',
    loading: false,
    error: false,
    onRetry: vi.fn(),
    onSelect,
  };
  return { ...render(<ReviewProjectDocumentView {...props} />), props, onSelect };
}

it('resizes and restores the navigator width without changing its page or search', () => {
  const storageKey = 'mojito.review.documentNavigatorWidth';
  window.localStorage.removeItem(storageKey);
  const data = documentData([heading]);
  data.documents.push({ ...data.documents[0], branchName: 'release' });
  const props = {
    data,
    textUnits: [row],
    selectedTextUnitId: null,
    localeTag: 'fr',
    loading: false,
    error: false,
    onRetry: vi.fn(),
    onSelect: vi.fn(),
  };
  try {
    const { unmount } = render(<ReviewProjectDocumentView {...props} />);
    fireEvent.click(screen.getByRole('button', { name: /^Open .* \(guide\.mdx\) on release$/ }));
    const page = screen.getByRole('article', { name: 'guide.mdx' });
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search pages and modules' }), {
      target: { value: 'guide' },
    });
    const handle = screen.getByRole('separator', { name: 'Resize pages and modules' });
    expect(handle).toHaveAttribute('aria-valuenow', '24');
    fireEvent.keyDown(handle, { key: 'ArrowRight' });

    expect(handle).toHaveAttribute('aria-valuenow', '26');
    expect(screen.getByRole('article', { name: 'guide.mdx' })).toBe(page);
    expect(page).toHaveAccessibleDescription('Branch: release');
    expect(screen.getByRole('searchbox', { name: 'Search pages and modules' })).toHaveValue(
      'guide',
    );

    unmount();
    render(<ReviewProjectDocumentView {...props} />);
    expect(screen.getByRole('separator', { name: 'Resize pages and modules' })).toHaveAttribute(
      'aria-valuenow',
      '26',
    );
  } finally {
    window.localStorage.removeItem(storageKey);
  }
});

describe('document preview links', () => {
  function linkProps(href = 'next.html') {
    const source = `Read the [next page](${href}).`;
    const block = { ...heading, type: 'paragraph', source, targetContent: source };
    const data = documentData([block]);
    data.documents.push({
      ...data.documents[0],
      assetId: 2,
      assetPath: 'next.mdx',
      blocks: [
        {
          ...heading,
          id: 'next-title',
          source: 'Next page title',
          translatable: false,
          mappingStatus: 'CONTEXT',
          tmTextUnitId: null,
          reviewProjectTextUnitId: null,
        },
      ],
    });
    return {
      data,
      textUnits: [
        {
          ...row,
          tmTextUnit: { ...row.tmTextUnit!, content: source },
          baselineTmTextUnitVariant: { id: 31, content: source },
        },
      ],
      selectedTextUnitId: null,
      localeTag: 'fr',
      loading: false,
      error: false,
      onRetry: vi.fn(),
      onSelect: vi.fn(),
      onNavigate: vi.fn(() => true),
    };
  }

  it('keeps ordinary mouse clicks on links as passage editing in review mode', () => {
    const props = linkProps();
    render(<ReviewProjectDocumentView {...props} />);

    fireEvent.click(screen.getByRole('link', { name: 'next page' }), { detail: 1 });

    expect(props.onSelect).toHaveBeenCalledWith(11);
    expect(props.onNavigate).not.toHaveBeenCalled();
    expect(screen.getByRole('article', { name: 'guide.mdx' })).toBeVisible();
  });

  it.each(['metaKey', 'ctrlKey', 'altKey'])(
    'follows a linked review page with %s without selecting its passage',
    (modifier) => {
      const props = linkProps();
      render(<ReviewProjectDocumentView {...props} />);

      fireEvent.click(screen.getByRole('link', { name: 'next page' }), {
        detail: 1,
        [modifier]: true,
      });

      expect(props.onSelect).not.toHaveBeenCalled();
      expect(props.onNavigate).toHaveBeenCalledOnce();
      expect(screen.getByRole('article', { name: 'next.mdx' })).toBeVisible();
      expect(screen.getByRole('heading', { name: 'Next page title' })).toBeVisible();
    },
  );

  it('lets Enter on a focused link follow it without invoking passage selection', () => {
    const props = linkProps();
    render(<ReviewProjectDocumentView {...props} />);
    const link = screen.getByRole('link', { name: 'next page' });
    link.focus();

    fireEvent.keyDown(link, { key: 'Enter' });

    expect(props.onSelect).not.toHaveBeenCalled();
    expect(props.onNavigate).toHaveBeenCalledOnce();
    expect(screen.getByRole('article', { name: 'next.mdx' })).toBeVisible();
  });

  it('keeps an ordinary Content link click editable while editing translations', () => {
    const props = linkProps();
    const onEdit = vi.fn();
    const onOpenAsset = vi.fn(() => Promise.resolve());
    render(
      <ReviewProjectDocumentView
        {...props}
        repositoryPreview={{ sourceLocaleTag: 'en', editing: true, onEdit, onOpenAsset }}
      />,
    );

    fireEvent.click(screen.getByRole('link', { name: 'next page' }), { detail: 1 });

    expect(onEdit).toHaveBeenCalledWith(21, expect.any(HTMLElement));
    expect(onOpenAsset).not.toHaveBeenCalled();
  });

  it.each(['metaKey', 'ctrlKey', 'altKey'])(
    'opens the repository asset with %s without opening its inline editor',
    async (modifier) => {
      const props = linkProps();
      const onEdit = vi.fn();
      const onOpenAsset = vi.fn(() => Promise.resolve());
      render(
        <ReviewProjectDocumentView
          {...props}
          repositoryPreview={{ sourceLocaleTag: 'en', editing: true, onEdit, onOpenAsset }}
        />,
      );

      fireEvent.click(screen.getByRole('link', { name: 'next page' }), {
        detail: 1,
        [modifier]: true,
      });

      expect(onOpenAsset).toHaveBeenCalledWith('next.mdx');
      expect(onEdit).not.toHaveBeenCalled();
      await waitFor(() =>
        expect(screen.queryByText('Opening linked page…')).not.toBeInTheDocument(),
      );
    },
  );

  it('follows an ordinary click in Content reading mode', async () => {
    const props = linkProps();
    const onEdit = vi.fn();
    const onOpenAsset = vi.fn(() => Promise.resolve());
    render(
      <ReviewProjectDocumentView
        {...props}
        repositoryPreview={{ sourceLocaleTag: 'en', editing: false, onEdit, onOpenAsset }}
      />,
    );

    fireEvent.click(screen.getByRole('link', { name: 'next page' }), { detail: 1 });

    expect(onOpenAsset).toHaveBeenCalledWith('next.mdx');
    expect(onEdit).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.queryByText('Opening linked page…')).not.toBeInTheDocument());
  });

  it('uses the containing page for links inside an included module', async () => {
    const props = linkProps();
    props.data.documents[0].assetPath = 'pages/guide.mdx';
    props.data.documents[0].blocks = [
      { ...props.data.documents[0].blocks[0], assetPath: 'modules/shared.mdx', moduleDepth: 1 },
    ];
    const onOpenAsset = vi.fn(() => Promise.resolve());
    render(
      <ReviewProjectDocumentView
        {...props}
        repositoryPreview={{ sourceLocaleTag: 'en', editing: false, onEdit: vi.fn(), onOpenAsset }}
      />,
    );

    fireEvent.click(screen.getByRole('link', { name: 'next page' }), { detail: 1 });

    expect(onOpenAsset).toHaveBeenCalledWith('pages/next.mdx');
    await waitFor(() => expect(screen.queryByText('Opening linked page…')).not.toBeInTheDocument());
  });

  it('only opens a review document in the current repository and branch', () => {
    const props = linkProps();
    const target = props.data.documents[1];
    props.data.documents.splice(
      1,
      0,
      {
        ...target,
        assetId: 3,
        repositoryId: 9,
        blocks: [{ ...target.blocks[0], source: 'Wrong repository' }],
      },
      {
        ...target,
        assetId: 4,
        branchName: 'release',
        blocks: [{ ...target.blocks[0], source: 'Wrong branch' }],
      },
    );
    render(<ReviewProjectDocumentView {...props} />);

    fireEvent.click(screen.getByRole('link', { name: 'next page' }), { detail: 1, metaKey: true });

    expect(screen.getByRole('heading', { name: 'Next page title' })).toBeVisible();
    expect(screen.queryByRole('heading', { name: 'Wrong repository' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Wrong branch' })).not.toBeInTheDocument();
  });

  it('keeps the current page when the navigation guard refuses a link', () => {
    const props = linkProps();
    props.onNavigate.mockReturnValue(false);
    render(<ReviewProjectDocumentView {...props} />);

    fireEvent.click(screen.getByRole('link', { name: 'next page' }), { detail: 1, altKey: true });

    expect(props.onNavigate).toHaveBeenCalledOnce();
    expect(props.onSelect).not.toHaveBeenCalled();
    expect(screen.getByRole('article', { name: 'guide.mdx' })).toBeVisible();
  });

  it('does not follow a link when navigation is disabled', () => {
    const props = linkProps();
    render(<ReviewProjectDocumentView {...props} navigationDisabled />);

    fireEvent.click(screen.getByRole('link', { name: 'next page' }), { detail: 1, ctrlKey: true });

    expect(props.onNavigate).not.toHaveBeenCalled();
    expect(props.onSelect).not.toHaveBeenCalled();
    expect(screen.getByRole('article', { name: 'guide.mdx' })).toBeVisible();
  });

  it('reports a missing review target instead of navigating to a different branch', () => {
    const props = linkProps();
    props.data.documents[1].branchName = 'release';
    render(<ReviewProjectDocumentView {...props} />);

    fireEvent.click(screen.getByRole('link', { name: 'next page' }), { detail: 1, metaKey: true });

    expect(screen.getByRole('status')).toHaveTextContent(
      '“next.mdx” is not included in this preview.',
    );
    expect(props.onNavigate).not.toHaveBeenCalled();
    expect(screen.getByRole('article', { name: 'guide.mdx' })).toBeVisible();
  });

  it('reports repository lookup failures without selecting the link passage', async () => {
    const props = linkProps();
    const onEdit = vi.fn();
    const onOpenAsset = vi.fn().mockRejectedValue(new Error('Asset unavailable'));
    render(
      <ReviewProjectDocumentView
        {...props}
        repositoryPreview={{ sourceLocaleTag: 'en', editing: true, onEdit, onOpenAsset }}
      />,
    );

    fireEvent.click(screen.getByRole('link', { name: 'next page' }), { detail: 1, metaKey: true });

    await waitFor(() =>
      expect(screen.getByRole('status')).toHaveTextContent(
        'Could not open “next.mdx”. It may not be available on this branch.',
      ),
    );
    expect(onEdit).not.toHaveBeenCalled();
    expect(screen.getByRole('article', { name: 'guide.mdx' })).toBeVisible();
  });

  it('renders safe external links with a separate protected browsing context', () => {
    const props = linkProps('https://example.com/guide');
    render(<ReviewProjectDocumentView {...props} />);
    const link = screen.getByRole('link', { name: 'next page' });

    expect(link).toHaveAttribute('href', 'https://example.com/guide');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    fireEvent.click(link, { detail: 1 });
    expect(props.onSelect).toHaveBeenCalledWith(11);
    expect(props.onNavigate).not.toHaveBeenCalled();
  });

  it('opens external Option/Alt-clicks without invoking the browser download gesture', () => {
    const props = linkProps('https://example.com/guide');
    const open = vi.spyOn(window, 'open').mockReturnValue(null);
    try {
      render(<ReviewProjectDocumentView {...props} />);

      const notPrevented = fireEvent.click(screen.getByRole('link', { name: 'next page' }), {
        detail: 1,
        altKey: true,
      });

      expect(notPrevented).toBe(false);
      expect(open).toHaveBeenCalledWith(
        'https://example.com/guide',
        '_blank',
        'noopener,noreferrer',
      );
      expect(props.onSelect).not.toHaveBeenCalled();
      expect(props.onNavigate).not.toHaveBeenCalled();
    } finally {
      open.mockRestore();
    }
  });

  it.each(['javascript:alert(1)', 'data:text/html,hello', 'guide%2fnext.html'])(
    'keeps unsupported link %s inert',
    (href) => {
      const props = linkProps(href);
      const { container } = render(<ReviewProjectDocumentView {...props} />);

      expect(screen.queryByRole('link', { name: 'next page' })).not.toBeInTheDocument();
      expect(container.querySelector('a')).toBeNull();
      expect(screen.getByText('next page')).toBeVisible();
    },
  );
});

describe('MF2 document passage', () => {
  const messageBlock: ApiReviewProjectDocumentBlock = {
    ...heading,
    id: 'calendar.date',
    type: 'mf2',
    source: 'On {$date :date dateStyle=long timeZone=UTC}.',
    assetId: 9,
    assetPath: 'messages.mf2.json',
    previewArgs: { date: '2026-09-16T12:00:00Z' },
  };
  const messageRow = {
    ...row,
    tmTextUnit: { ...row.tmTextUnit!, name: messageBlock.id, content: messageBlock.source },
  };

  it('formats the saved catalog target and opens its review row', () => {
    const { onSelect } = renderDocument(
      [messageBlock],
      [
        {
          ...messageRow,
          baselineTmTextUnitVariant: {
            id: 31,
            content: 'Le {$date :date dateStyle=long timeZone=UTC}.',
          },
        },
      ],
    );
    const passage = screen.getByRole('button', {
      name: 'Review calendar.date in messages.mf2.json',
    });
    expect(passage).toHaveTextContent('Le 16 septembre 2026.');
    fireEvent.click(passage);
    expect(onSelect).toHaveBeenCalledWith(row.id);
  });

  it('uses the source locale for fallback and keeps invalid targets editable', () => {
    const { props, rerender, onSelect } = renderDocument([messageBlock], []);
    const data = documentData([messageBlock]);
    data.documents[0].sourceLocaleTag = 'en';
    rerender(<ReviewProjectDocumentView {...props} data={data} />);
    expect(screen.getByText('On September 16, 2026.')).toBeInTheDocument();
    rerender(
      <ReviewProjectDocumentView
        {...props}
        data={data}
        textUnits={[
          {
            ...messageRow,
            baselineTmTextUnitVariant: { id: 31, content: '{$broken' },
          },
        ]}
      />,
    );
    const passage = screen.getByRole('button', {
      name: 'Review calendar.date in messages.mf2.json',
    });
    expect(passage).toHaveTextContent('Message preview unavailable');
    fireEvent.click(passage);
    expect(onSelect).toHaveBeenCalledWith(row.id);
  });
});

function previewChoiceFixture() {
  const textUnits: ApiReviewProjectTextUnit[] = [
    {
      ...row,
      id: 41,
      tmTextUnit: { id: 51, name: 'detailed.title', content: 'Detailed version' },
      baselineTmTextUnitVariant: { id: 61, content: 'Version détaillée' },
    },
    {
      ...row,
      id: 42,
      tmTextUnit: { id: 52, name: 'brief.title', content: 'Brief version' },
      baselineTmTextUnitVariant: { id: 62, content: 'Version courte' },
    },
    {
      ...row,
      id: 43,
      tmTextUnit: { id: 53, name: 'shared.advice', content: 'Shared advice' },
      baselineTmTextUnitVariant: { id: 63, content: 'Un conseil commun' },
    },
  ];
  const choice: ApiReviewProjectDocumentBlock = {
    ...moduleBlock,
    source: '<PreviewChoice>',
    moduleStatus: null,
    modulePath: null,
    occurrenceId: 'guide/choice',
  };
  const module = (
    name: string,
    path: string,
    occurrenceId: string,
    moduleDepth = 0,
  ): ApiReviewProjectDocumentBlock => ({
    ...moduleBlock,
    source: `<${name} />`,
    modulePath: path,
    occurrenceId,
    moduleDepth,
  });
  const passage = (
    textUnit: ApiReviewProjectTextUnit,
    assetId: number,
    assetPath: string,
    occurrenceId: string,
    moduleDepth: number,
    type = 'heading',
  ): ApiReviewProjectDocumentBlock => ({
    ...heading,
    id: String(textUnit.tmTextUnit!.name),
    type,
    source: textUnit.tmTextUnit!.content!,
    reviewProjectTextUnitId: textUnit.id,
    tmTextUnitId: textUnit.tmTextUnit!.id,
    assetId,
    assetPath,
    occurrenceId,
    moduleDepth,
  });
  const blocks = [
    choice,
    module('Detailed', 'variants/detailed.mdx', 'guide/choice/detailed'),
    passage(textUnits[0], 4, 'variants/detailed.mdx', 'guide/choice/detailed/title', 1),
    module('Advice', 'shared/advice.mdx', 'guide/choice/detailed/advice-1', 1),
    passage(
      textUnits[2],
      6,
      'shared/advice.mdx',
      'guide/choice/detailed/advice-1/text',
      2,
      'paragraph',
    ),
    module('Advice', 'shared/advice.mdx', 'guide/choice/detailed/advice-2', 1),
    passage(
      textUnits[2],
      6,
      'shared/advice.mdx',
      'guide/choice/detailed/advice-2/text',
      2,
      'paragraph',
    ),
    module('Brief', 'variants/brief.mdx', 'guide/choice/brief'),
    passage(textUnits[1], 5, 'variants/brief.mdx', 'guide/choice/brief/title', 1),
    module('Advice', 'shared/advice.mdx', 'guide/choice/brief/advice', 1),
    passage(textUnits[2], 6, 'shared/advice.mdx', 'guide/choice/brief/advice/text', 2, 'paragraph'),
    { ...choice, source: '</PreviewChoice>', occurrenceId: 'guide/choice/end' },
  ];
  return { blocks, textUnits };
}

function renderPreviewChoice() {
  const fixture = previewChoiceFixture();
  const props = {
    data: documentData(fixture.blocks),
    textUnits: fixture.textUnits,
    selectedTextUnitId: null as number | null,
    localeTag: 'fr',
    loading: false,
    error: false,
    onRetry: vi.fn(),
    onSelect: vi.fn(),
    onNavigate: vi.fn(() => true),
  };
  return { ...render(<ReviewProjectDocumentView {...props} />), ...fixture, props };
}

const repositoryHeading: ApiReviewProjectDocumentBlock = {
  ...heading,
  reviewProjectTextUnitId: null,
  targetContent: 'Titre enregistré',
  tmTextUnitVariantId: 71,
  targetStatus: 'APPROVED',
};

function renderRepositoryDocument(
  blocks = [repositoryHeading],
  localeTag = 'fr',
  editing?: boolean,
) {
  const props = {
    data: documentData(blocks),
    textUnits: [],
    selectedTextUnitId: null,
    localeTag,
    loading: false,
    error: false,
    showReviewAnnotations: true,
    unsavedTextUnitIds: [21],
    onRetry: vi.fn(),
    onSelect: vi.fn(),
    repositoryPreview: { sourceLocaleTag: 'en', editing, onEdit: vi.fn() },
  };
  return { ...render(<ReviewProjectDocumentView {...props} />), props };
}

function repositoryChoiceBlocks() {
  const { blocks, textUnits } = previewChoiceFixture();
  return blocks.map((block): ApiReviewProjectDocumentBlock => {
    const textUnit = textUnits.find((candidate) => candidate.tmTextUnit?.id === block.tmTextUnitId);
    return {
      ...block,
      reviewProjectTextUnitId: null,
      targetContent: textUnit?.baselineTmTextUnitVariant?.content,
      tmTextUnitVariantId: textUnit?.baselineTmTextUnitVariant?.id,
      targetStatus: textUnit ? 'APPROVED' : null,
    };
  });
}

describe('ReviewProjectDocumentView', () => {
  it('renders a repository translation and edits its real text unit without review project rows', () => {
    const { props } = renderRepositoryDocument();
    expect(screen.getByRole('heading')).toHaveTextContent('Titre enregistré');
    const passage = screen.getByRole('button', { name: 'Edit intro' });
    expect(passage).toHaveAttribute('data-tm-text-unit-id', '21');
    expect(passage).not.toHaveAttribute('data-review-row-id');
    expect(within(passage).getByRole('heading').parentElement).toHaveAttribute('lang', 'fr');

    fireEvent.click(passage);
    fireEvent.keyDown(passage, { key: ' ' });

    expect(props.repositoryPreview.onEdit.mock.calls).toEqual([
      [21, passage],
      [21, passage],
    ]);
    expect(props.onSelect).not.toHaveBeenCalled();
    expect(screen.queryByText('Pending review')).not.toBeInTheDocument();
    expect(screen.queryByTitle('Pending review')).not.toBeInTheDocument();
    expect(screen.queryByTitle('Reviewed')).not.toBeInTheDocument();
    expect(screen.queryByText('Unsaved changes')).not.toBeInTheDocument();
    expect(screen.queryByText(/review project/i)).not.toBeInTheDocument();
  });

  it('keeps reading mode out of the tab order and supports an anchored double-click shortcut', () => {
    const { props, container } = renderRepositoryDocument([repositoryHeading], 'fr', false);
    const passage = container.querySelector<HTMLElement>('[data-block-id="intro"]')!;
    expect(passage).not.toHaveAttribute('role');
    expect(passage).not.toHaveAttribute('tabindex');
    expect(passage).toHaveAttribute('data-content-editable', 'true');
    expect(passage).not.toHaveClass('is-reviewable');
    fireEvent.click(passage);
    fireEvent.keyDown(passage, { key: 'Enter' });
    expect(props.repositoryPreview.onEdit).not.toHaveBeenCalled();
    fireEvent.doubleClick(passage);
    expect(props.repositoryPreview.onEdit).toHaveBeenCalledExactlyOnceWith(21, passage);
  });

  it('makes edit mode keyboard accessible and anchors the exact repeated occurrence without scrolling', () => {
    const blocks = [
      { ...repositoryHeading, occurrenceId: 'first/intro' },
      { ...repositoryHeading, occurrenceId: 'second/intro' },
    ];
    const { props, rerender } = renderRepositoryDocument(blocks, 'fr', true);
    const [first, second] = screen.getAllByRole('button', { name: 'Edit intro' });
    const scrollFirst = vi.fn();
    const scrollSecond = vi.fn();
    Object.defineProperty(first, 'scrollIntoView', { value: scrollFirst });
    Object.defineProperty(second, 'scrollIntoView', { value: scrollSecond });
    fireEvent.keyDown(second, { key: 'Enter' });
    expect(props.repositoryPreview.onEdit).toHaveBeenCalledExactlyOnceWith(21, second);
    rerender(<ReviewProjectDocumentView {...props} selectedTextUnitId={21} />);
    expect(first).toHaveAttribute('aria-pressed', 'false');
    expect(second).toHaveAttribute('aria-pressed', 'true');

    rerender(
      <ReviewProjectDocumentView
        {...props}
        data={documentData(blocks.map((block) => ({ ...block, targetContent: 'Nouveau titre' })))}
        selectedTextUnitId={21}
      />,
    );
    expect(second).toHaveAttribute('aria-pressed', 'true');
    expect(scrollFirst).not.toHaveBeenCalled();
    expect(scrollSecond).not.toHaveBeenCalled();

    fireEvent.click(first);
    expect(first).toHaveAttribute('aria-pressed', 'true');
    expect(second).toHaveAttribute('aria-pressed', 'false');
  });

  it.each([
    {
      targetContent: null,
      targetStatus: 'TRANSLATION_NEEDED' as const,
      status: 'Source fallback — untranslated',
    },
    { targetContent: 'À réviser', targetStatus: 'REVIEW_NEEDED' as const, status: 'Review needed' },
    {
      targetContent: 'À traduire',
      targetStatus: 'TRANSLATION_NEEDED' as const,
      status: 'Translation needed',
    },
  ])(
    'shows repository translation status without a project decision: $status',
    ({ targetContent, targetStatus, status }) => {
      const { props } = renderRepositoryDocument([
        { ...repositoryHeading, targetContent, targetStatus },
      ]);
      expect(screen.getByText(status)).toBeVisible();
      expect(screen.getByRole('heading')).toHaveTextContent(targetContent ?? 'Welcome everyone');
      const passage = screen.getByRole('button', { name: 'Edit intro' });
      fireEvent.click(passage);
      expect(props.repositoryPreview.onEdit).toHaveBeenCalledWith(21, passage);
      expect(props.onSelect).not.toHaveBeenCalled();
    },
  );

  it.each([
    { mappingStatus: 'SOURCE_CHANGED' as const, tmTextUnitId: 21 },
    { mappingStatus: 'NOT_FOUND' as const, tmTextUnitId: null },
    { mappingStatus: 'CONTEXT' as const, tmTextUnitId: 21 },
    { mappingStatus: 'MATCHED' as const, tmTextUnitId: null },
    { mappingStatus: 'MATCHED' as const, tmTextUnitId: 0 },
  ])('keeps an invalid repository mapping read-only: %j', (mapping) => {
    const { props, container } = renderRepositoryDocument([{ ...repositoryHeading, ...mapping }]);
    expect(screen.getByRole('heading')).toHaveTextContent('Welcome everyone');
    expect(screen.queryByText('Titre enregistré')).not.toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    fireEvent.click(container.querySelector('[data-block-id="intro"]')!);
    fireEvent.doubleClick(container.querySelector('[data-block-id="intro"]')!);
    expect(container.querySelector('[data-content-editable]')).not.toBeInTheDocument();
    expect(props.repositoryPreview.onEdit).not.toHaveBeenCalled();
    expect(props.onSelect).not.toHaveBeenCalled();
    expect(screen.queryByText(/review project/i)).not.toBeInTheDocument();
  });

  it('renders the source locale read-only without counting source as an untranslated target', () => {
    const { props } = renderRepositoryDocument([repositoryHeading], 'en');
    expect(screen.getByRole('heading')).toHaveTextContent('Welcome everyone');
    expect(screen.getByRole('heading').parentElement).toHaveAttribute('lang', 'en');
    expect(screen.queryByText('Titre enregistré')).not.toBeInTheDocument();
    expect(screen.queryByText('Source fallback — untranslated')).not.toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(props.repositoryPreview.onEdit).not.toHaveBeenCalled();
  });

  it('keeps repository module variants selectable with saved targets and no review counts', () => {
    const { props } = renderRepositoryDocument(repositoryChoiceBlocks());
    const picker = screen.getByRole('button', { name: 'Preview variant' });
    fireEvent.click(picker);
    const menu = screen.getByRole('menu');
    expect(
      within(menu)
        .getAllByRole('button')
        .map((option) => option.textContent),
    ).toEqual(['Version détaillée', 'Version courte']);
    fireEvent.click(within(menu).getByRole('button', { name: 'Version courte' }));

    expect(screen.getByRole('heading', { name: 'Version courte' })).toBeVisible();
    const passage = screen.getByRole('button', { name: 'Edit shared.advice in shared/advice.mdx' });
    fireEvent.click(passage);
    expect(props.repositoryPreview.onEdit).toHaveBeenCalledWith(53, passage);
    expect(props.onSelect).not.toHaveBeenCalled();
    expect(screen.queryByText(/reviewed|remain in review/i)).not.toBeInTheDocument();
  });

  it('uses source titles and read-only passages for repository module choices in the source locale', () => {
    renderRepositoryDocument(repositoryChoiceBlocks(), 'en');
    const picker = screen.getByRole('button', { name: 'Preview variant' });
    fireEvent.click(picker);
    const menu = screen.getByRole('menu');
    expect(
      within(menu)
        .getAllByRole('button')
        .map((option) => option.textContent),
    ).toEqual(['Detailed version', 'Brief version']);
    fireEvent.click(within(menu).getByRole('button', { name: 'Brief version' }));

    expect(screen.getByRole('heading', { name: 'Brief version' })).toBeVisible();
    expect(screen.getAllByRole('button')).toEqual([picker]);
    expect(screen.queryByText('Source fallback — untranslated')).not.toBeInTheDocument();
  });

  it('switches preview variants without selecting or deciding their hidden passages', () => {
    const { props } = renderPreviewChoice();
    const picker = screen.getByRole('button', { name: 'Preview variant' });
    expect(picker).toHaveTextContent('Version détaillée · 0/2 reviewed');
    fireEvent.click(picker);
    const menu = screen.getByRole('menu');
    const detailed = within(menu).getByRole('button', {
      name: /Version détaillée.*0\/2 reviewed/,
    });
    const brief = within(menu).getByRole('button', {
      name: /Version courte.*0\/2 reviewed/,
    });
    expect(screen.getByRole('heading', { name: 'Version détaillée' })).toBeVisible();
    expect(screen.queryByRole('heading', { name: 'Version courte' })).not.toBeInTheDocument();
    expect(screen.getByText('Version courte', { selector: 'h1' })).not.toBeVisible();

    fireEvent.click(brief);

    expect(props.onNavigate).toHaveBeenCalledOnce();
    expect(props.onSelect).not.toHaveBeenCalled();
    expect(picker).toHaveTextContent('Version courte · 0/2 reviewed');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Version courte' })).toBeVisible();
    expect(screen.queryByRole('heading', { name: 'Version détaillée' })).not.toBeInTheDocument();
    expect(screen.getByText('Version détaillée', { selector: 'h1' })).not.toBeVisible();
    expect(detailed).toHaveTextContent('0/2 reviewed');
    expect(brief).toHaveTextContent('0/2 reviewed');
    expect(documentReviewRows(props.data, props.textUnits)).toEqual([
      props.textUnits[0],
      props.textUnits[2],
      props.textUnits[1],
    ]);
  });

  it('keeps review shortcuts out of the variant picker and its portal', () => {
    const { props } = renderPreviewChoice();
    const handleReviewShortcut = vi.fn();
    window.addEventListener('keydown', handleReviewShortcut);
    try {
      const picker = screen.getByRole('button', { name: 'Preview variant' });
      fireEvent.click(picker);
      const menu = screen.getByRole('menu');
      const brief = within(menu).getByRole('button', { name: /Version courte/ });
      expect(picker.parentElement).not.toContainElement(menu);

      for (const target of [picker, brief]) {
        target.focus();
        for (const key of ['a', 'p', 'e', 'j', 'k', 'ArrowUp', 'ArrowDown', 'Enter', 'Tab']) {
          expect(fireEvent.keyDown(target, { key })).toBe(true);
        }
      }

      expect(handleReviewShortcut).not.toHaveBeenCalled();
      expect(props.onSelect).not.toHaveBeenCalled();
      expect(props.onNavigate).not.toHaveBeenCalled();
      fireEvent.click(brief);
      expect(picker).toHaveTextContent('Version courte');
      expect(props.onNavigate).toHaveBeenCalledOnce();

      fireEvent.keyDown(screen.getByRole('heading', { name: 'Version courte' }), { key: 'a' });
      expect(handleReviewShortcut).toHaveBeenCalledOnce();
    } finally {
      window.removeEventListener('keydown', handleReviewShortcut);
    }
  });

  it('keeps the current preview variant when the navigation guard refuses a switch', () => {
    const { props, rerender } = renderPreviewChoice();
    props.onNavigate.mockReturnValue(false);
    rerender(<ReviewProjectDocumentView {...props} />);
    const picker = screen.getByRole('button', { name: 'Preview variant' });
    fireEvent.click(picker);
    const brief = within(screen.getByRole('menu')).getByRole('button', {
      name: /Version courte/,
    });

    fireEvent.click(brief);

    expect(props.onNavigate).toHaveBeenCalledOnce();
    expect(props.onSelect).not.toHaveBeenCalled();
    expect(screen.getByRole('heading', { name: 'Version détaillée' })).toBeVisible();
    expect(screen.queryByRole('heading', { name: 'Version courte' })).not.toBeInTheDocument();
    expect(picker).toHaveTextContent('Version détaillée');
  });

  it('reveals an externally selected hidden passage and retains that variant after closing editing', () => {
    const { props, rerender } = renderPreviewChoice();
    rerender(<ReviewProjectDocumentView {...props} selectedTextUnitId={42} />);
    const picker = screen.getByRole('button', { name: 'Preview variant' });
    expect(picker).toHaveTextContent('Version courte');
    expect(
      screen.getByRole('button', { name: 'Review brief.title in variants/brief.mdx' }),
    ).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByRole('heading', { name: 'Version détaillée' })).not.toBeInTheDocument();

    rerender(<ReviewProjectDocumentView {...props} selectedTextUnitId={null} />);

    expect(picker).toHaveTextContent('Version courte');
    expect(screen.getByRole('heading', { name: 'Version courte' })).toBeVisible();
    expect(props.onNavigate).not.toHaveBeenCalled();
    expect(props.onSelect).not.toHaveBeenCalled();
  });

  it('counts each shared review row once per variant without deciding the other variant', () => {
    const { props, rerender } = renderPreviewChoice();
    const decide = (textUnit: ApiReviewProjectTextUnit): ApiReviewProjectTextUnit => ({
      ...textUnit,
      reviewProjectTextUnitDecision: { decisionState: 'DECIDED' },
    });
    const firstReviewed = props.textUnits.map((textUnit) =>
      textUnit.id === 41 ? decide(textUnit) : textUnit,
    );
    rerender(<ReviewProjectDocumentView {...props} textUnits={firstReviewed} />);
    const picker = screen.getByRole('button', { name: 'Preview variant' });
    fireEvent.click(picker);
    const menu = screen.getByRole('menu');
    expect(
      within(menu).getByRole('button', { name: /Version détaillée.*1\/2 reviewed/ }),
    ).toBeInTheDocument();
    expect(
      within(menu).getByRole('button', { name: /Version courte.*0\/2 reviewed/ }),
    ).toBeInTheDocument();

    const sharedReviewed = firstReviewed.map((textUnit) =>
      textUnit.id === 43 ? decide(textUnit) : textUnit,
    );
    rerender(<ReviewProjectDocumentView {...props} textUnits={sharedReviewed} />);

    expect(
      within(menu).getByRole('button', { name: /Version détaillée.*2\/2 reviewed/ }),
    ).toBeInTheDocument();
    expect(
      within(menu).getByRole('button', { name: /Version courte.*1\/2 reviewed/ }),
    ).toBeInTheDocument();
    expect(documentReviewRows(props.data, sharedReviewed)).toEqual([
      sharedReviewed[0],
      sharedReviewed[2],
      sharedReviewed[1],
    ]);
    expect(props.onSelect).not.toHaveBeenCalled();
  });

  it.each(['missing closing tag', 'direct prose'])(
    'keeps all variants visible with a warning for a choice with %s',
    (malformation) => {
      const { blocks, textUnits } = previewChoiceFixture();
      const malformed =
        malformation === 'missing closing tag'
          ? blocks.slice(0, -1)
          : [
              blocks[0],
              {
                ...heading,
                id: 'unexpected',
                type: 'paragraph',
                source: 'Unexpected direct prose',
                reviewProjectTextUnitId: null,
                tmTextUnitId: null,
                mappingStatus: 'NOT_IN_PROJECT' as const,
                moduleDepth: 0,
                occurrenceId: 'guide/choice/unexpected',
              },
              ...blocks.slice(1),
            ];
      renderDocument(malformed, textUnits);

      expect(screen.queryByRole('button', { name: 'Preview variant' })).not.toBeInTheDocument();
      expect(
        screen.getByText('Incomplete preview choice — showing all available variants.'),
      ).toBeVisible();
      expect(screen.getByRole('heading', { name: 'Version détaillée' })).toBeVisible();
      expect(screen.getByRole('heading', { name: 'Version courte' })).toBeVisible();
      expect(
        screen.getByRole('button', { name: 'Review detailed.title in variants/detailed.mdx' }),
      ).toBeVisible();
      expect(
        screen.getByRole('button', { name: 'Review brief.title in variants/brief.mdx' }),
      ).toBeVisible();
    },
  );

  it('groups nested modules in page order with their original asset and review row', () => {
    const tipRow: ApiReviewProjectTextUnit = {
      ...row,
      id: 12,
      tmTextUnit: { id: 22, name: 'tip', content: 'A useful tip' },
      baselineTmTextUnitVariant: { id: 32, content: 'Un conseil utile' },
    };
    const blocks = [
      moduleBlock,
      includedHeading,
      {
        ...moduleBlock,
        source: '<Tip />',
        assetId: 2,
        assetPath: 'shared/intro.mdx',
        moduleDepth: 1,
        occurrenceId: 'guide/intro-module/tip-module',
        modulePath: 'shared/tip.mdx',
      },
      {
        ...includedHeading,
        id: 'tip',
        type: 'paragraph',
        source: 'A useful tip',
        reviewProjectTextUnitId: 12,
        tmTextUnitId: 22,
        assetId: 3,
        assetPath: 'shared/tip.mdx',
        moduleDepth: 2,
        occurrenceId: 'guide/intro-module/tip-module/tip',
      },
      {
        ...heading,
        id: 'after',
        source: 'After the module',
        mappingStatus: 'NOT_IN_PROJECT' as const,
        reviewProjectTextUnitId: null,
        tmTextUnitId: null,
      },
    ];
    const { container, onSelect } = renderDocument(blocks, [row, tipRow]);
    const intro = screen.getByRole('region', { name: 'Intro module: shared/intro.mdx' });
    const tip = within(intro).getByRole('region', { name: 'Tip module: shared/tip.mdx' });
    expect(within(intro).getByRole('heading')).toHaveTextContent('Bienvenue à tous');
    expect(within(tip).getByText('Un conseil utile')).toBeInTheDocument();
    expect(within(intro).queryByText('After the module')).not.toBeInTheDocument();
    expect(
      Array.from(container.querySelectorAll('[data-block-id]'), (block) =>
        block.getAttribute('data-block-id'),
      ),
    ).toEqual(['intro', 'tip', 'after']);
    expect(container.querySelector('pre')).not.toBeInTheDocument();
    const tipPassage = screen.getByRole('button', { name: 'Review tip in shared/tip.mdx' });
    expect(tipPassage).toHaveAttribute('data-asset-id', '3');
    fireEvent.click(tipPassage);
    expect(onSelect).toHaveBeenCalledWith(tipRow.id);
    expect(documentReviewRows(documentData(blocks), [tipRow, row])).toEqual([row, tipRow]);
  });

  it.each([
    'Module cycle detected: guide.mdx',
    'Module nesting limit reached',
    'Module source is unavailable: shared/missing.mdx',
    'Module props are not supported',
  ])('keeps unavailable module warnings visible without review annotations: %s', (warning) => {
    renderDocument([{ ...moduleBlock, moduleStatus: 'UNAVAILABLE', moduleWarning: warning }]);
    expect(
      screen.getByRole('region', { name: 'Intro module: shared/intro.mdx' }),
    ).toHaveTextContent(warning);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(screen.queryByText('<Intro />')).not.toBeInTheDocument();
  });

  it('keeps included source fallback and nonproject context visible without making context editable', () => {
    const outsideProject = {
      ...includedHeading,
      id: 'outside',
      type: 'paragraph',
      source: 'Additional shared context',
      occurrenceId: 'guide/intro-module/outside',
      reviewProjectTextUnitId: null,
      tmTextUnitId: null,
      mappingStatus: 'NOT_IN_PROJECT' as const,
    };
    renderDocument(
      [moduleBlock, includedHeading, outsideProject],
      [{ ...row, baselineTmTextUnitVariant: null }],
    );
    expect(screen.getByRole('heading')).toHaveTextContent('Welcome everyone');
    expect(screen.getByText('Source fallback — untranslated')).toBeInTheDocument();
    expect(screen.getByText('Additional shared context')).toBeInTheDocument();
    expect(screen.getByText('Not in this review project — source context')).toBeInTheDocument();
    expect(screen.getAllByRole('button')).toHaveLength(1);
    expect(
      screen.getByRole('button', { name: 'Review intro in shared/intro.mdx' }),
    ).toBeInTheDocument();
  });

  it.each([
    { id: 21, name: 'intro', content: 'Changed included source' },
    { id: 99, name: 'intro', content: 'Welcome **everyone**' },
  ])('rejects included passages when the current row source identity differs: %j', (tmTextUnit) => {
    const changedRow = { ...row, tmTextUnit };
    const blocks = [moduleBlock, includedHeading];
    renderDocument(blocks, [changedRow]);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(screen.getByRole('heading')).toHaveTextContent('Welcome everyone');
    expect(
      screen.getByText('Source changed — document context cannot be reviewed'),
    ).toBeInTheDocument();
    expect(documentReviewRows(documentData(blocks), [changedRow])).toEqual([]);
  });

  it('retains the second module occurrence through saved updates, document replacement and Resume', () => {
    const blocks = [
      moduleBlock,
      includedHeading,
      { ...moduleBlock, occurrenceId: 'guide/second-intro-module' },
      { ...includedHeading, occurrenceId: 'guide/second-intro-module/intro' },
    ];
    const originalScroll = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollIntoView');
    const scrolledOccurrences: (string | undefined)[] = [];
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value(this: HTMLElement) {
        scrolledOccurrences.push(this.dataset.occurrenceId);
      },
    });
    try {
      const { props, rerender, onSelect } = renderDocument(blocks);
      fireEvent.click(
        screen.getAllByRole('button', { name: 'Review intro in shared/intro.mdx' })[1],
      );
      expect(onSelect).toHaveBeenCalledWith(row.id);
      rerender(<ReviewProjectDocumentView {...props} selectedTextUnitId={row.id} />);
      const savedRows = [
        { ...row, currentTmTextUnitVariant: { id: 33, content: 'Titre enregistré' } },
      ];
      const refreshedData = documentData(blocks);
      refreshedData.documents[0].sourceContentMd5 = 'revision-b';
      rerender(
        <ReviewProjectDocumentView
          {...props}
          data={refreshedData}
          textUnits={savedRows}
          selectedTextUnitId={row.id}
        />,
      );
      expect(screen.getAllByRole('heading').map((heading) => heading.textContent)).toEqual([
        'Titre enregistré',
        'Titre enregistré',
      ]);
      rerender(
        <ReviewProjectDocumentView
          {...props}
          data={refreshedData}
          textUnits={savedRows}
          selectedTextUnitId={null}
        />,
      );
      expect(scrolledOccurrences).toHaveLength(2);
      rerender(
        <ReviewProjectDocumentView
          {...props}
          data={refreshedData}
          textUnits={savedRows}
          selectedTextUnitId={row.id}
        />,
      );
      expect(scrolledOccurrences).toEqual(Array(3).fill('guide/second-intro-module/intro'));
      expect(documentReviewRows(refreshedData, savedRows)).toEqual(savedRows);
    } finally {
      if (originalScroll) {
        Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', originalScroll);
      } else {
        Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView');
      }
    }
  });

  it('keeps the clicked occurrence when the same row appears in multiple branch documents', () => {
    const data = documentData([heading]);
    data.documents.push({ ...data.documents[0], branchName: 'release' });
    const props = {
      data,
      textUnits: [row],
      selectedTextUnitId: null as number | null,
      localeTag: 'fr',
      loading: false,
      error: false,
      onRetry: vi.fn(),
      onSelect: vi.fn(),
    };
    const { rerender } = render(<ReviewProjectDocumentView {...props} />);
    const first = screen.getByRole('button', { name: 'Review intro' });
    const scrollFirst = vi.fn();
    const scrollSecond = vi.fn();
    Object.defineProperty(first, 'scrollIntoView', { value: scrollFirst });
    const releasePage = screen.getByRole('button', {
      name: /^Open .* \(guide\.mdx\) on release$/,
    });
    fireEvent.click(releasePage);
    const second = screen.getByRole('button', { name: 'Review intro' });
    expect(first).not.toBeInTheDocument();
    Object.defineProperty(second, 'scrollIntoView', { value: scrollSecond });
    fireEvent.click(second);
    rerender(<ReviewProjectDocumentView {...props} selectedTextUnitId={row.id} />);
    expect(scrollSecond).toHaveBeenCalledOnce();
    expect(scrollFirst).not.toHaveBeenCalled();

    rerender(<ReviewProjectDocumentView {...props} selectedTextUnitId={null} />);
    rerender(<ReviewProjectDocumentView {...props} selectedTextUnitId={row.id} />);
    expect(releasePage).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('article')).toHaveAttribute(
      'data-document-key',
      JSON.stringify([2, 1, null, 'release']),
    );
    expect(scrollSecond).toHaveBeenCalledTimes(2);
    expect(scrollFirst).not.toHaveBeenCalled();
  });

  it('renders saved translations in order and selects the mapped review row', () => {
    const { onSelect, container } = renderDocument();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Bienvenue à tous');
    expect(container.querySelector('strong')).toHaveTextContent('à tous');
    fireEvent.click(screen.getByRole('button', { name: 'Review intro' }));
    expect(onSelect).toHaveBeenCalledWith(11);
    fireEvent.keyDown(screen.getByRole('button', { name: 'Review intro' }), { key: 'Enter' });
    expect(onSelect).toHaveBeenCalledTimes(2);
  });

  it('uses quiet, accessible status cues for pending and reviewed passages, including legacy decisions', () => {
    const { props, rerender } = renderDocument();
    const passage = screen.getByRole('button', { name: 'Review intro' });
    expect(passage).toHaveClass('is-pending-review');
    expect(passage).toHaveAccessibleDescription('Pending review');
    expect(within(passage).queryByText('Pending review')).not.toBeInTheDocument();
    const pendingCue = within(passage).getByTitle('Pending review');
    expect(pendingCue).toHaveTextContent('•');
    expect(pendingCue).toBeVisible();
    expect(pendingCue).toHaveAttribute('aria-hidden', 'true');

    const reviewedRow: ApiReviewProjectTextUnit = {
      ...row,
      reviewProjectTextUnitDecision: { decisionTmTextUnitVariant: { id: 31 } },
    };
    rerender(<ReviewProjectDocumentView {...props} textUnits={[reviewedRow]} />);
    expect(passage).not.toHaveClass('is-pending-review');
    expect(passage).toHaveClass('is-reviewed');
    expect(passage).toHaveAccessibleDescription('Reviewed');
    expect(within(passage).queryByText('Reviewed')).not.toBeInTheDocument();
    const reviewedCue = within(passage).getByTitle('Reviewed');
    expect(reviewedCue).toHaveTextContent('✓');
    expect(reviewedCue).toBeVisible();
    expect(reviewedCue).toHaveAttribute('aria-hidden', 'true');

    rerender(
      <ReviewProjectDocumentView
        {...props}
        textUnits={[
          {
            ...reviewedRow,
            reviewProjectTextUnitDecision: {
              ...reviewedRow.reviewProjectTextUnitDecision,
              decisionState: 'PENDING',
            },
          },
        ]}
      />,
    );
    expect(passage).toHaveClass('is-pending-review');
    expect(within(passage).getByTitle('Pending review')).toHaveTextContent('•');

    rerender(<ReviewProjectDocumentView {...props} showReviewAnnotations={false} />);
    expect(passage).not.toHaveClass('is-pending-review');
    expect(within(passage).queryByTitle('Pending review')).not.toBeInTheDocument();
    expect(passage).toHaveAccessibleDescription('Pending review');
  });

  it('uses fresh saved row values and labels untranslated source fallback', () => {
    const { props, rerender } = renderDocument();
    rerender(
      <ReviewProjectDocumentView
        {...props}
        textUnits={[
          {
            ...row,
            currentTmTextUnitVariant: { id: 32, content: 'Nouveau titre' },
          },
        ]}
      />,
    );
    expect(screen.getByRole('heading')).toHaveTextContent('Nouveau titre');
    rerender(
      <ReviewProjectDocumentView
        {...props}
        textUnits={[
          {
            ...row,
            baselineTmTextUnitVariant: null,
          },
        ]}
      />,
    );
    expect(screen.getByRole('heading')).toHaveTextContent('Welcome everyone');
    expect(screen.getByText('Source fallback — untranslated')).toBeInTheDocument();
    rerender(
      <ReviewProjectDocumentView
        {...props}
        textUnits={[{ ...row, baselineTmTextUnitVariant: null }]}
        showReviewAnnotations={false}
      />,
    );
    expect(screen.getByText('Source fallback — untranslated')).toBeVisible();
    expect(screen.queryByTitle('Pending review')).not.toBeInTheDocument();
  });

  it('keeps context outside the review subset and blocks stale mappings', () => {
    const blocks = [
      heading,
      {
        ...heading,
        id: 'context',
        type: 'paragraph',
        source: 'Read this context',
        mappingStatus: 'NOT_IN_PROJECT' as const,
        reviewProjectTextUnitId: null,
        tmTextUnitId: null,
      },
      {
        ...heading,
        id: 'stale',
        source: 'Changed source',
        mappingStatus: 'SOURCE_CHANGED' as const,
      },
    ];
    renderDocument(blocks);
    expect(screen.getByText('Read this context')).toBeInTheDocument();
    expect(screen.getByText('Not in this review project — source context')).toBeInTheDocument();
    expect(
      screen.getByText('Source changed — document context cannot be reviewed'),
    ).toBeInTheDocument();
    expect(screen.getAllByRole('button')).toHaveLength(1);
    expect(screen.getByText('Read this context').closest('[data-block-id]')).not.toHaveClass(
      'is-pending-review',
    );
    expect(screen.getByText('Changed source').closest('[data-block-id]')).not.toHaveClass(
      'is-pending-review',
    );
    expect(documentReviewRows(documentData(blocks), [row])).toEqual([row]);
  });

  it('detects a newly remapped source even when the cached document says matched', () => {
    renderDocument(
      [heading],
      [{ ...row, tmTextUnit: { ...row.tmTextUnit!, content: 'New source' } }],
    );
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(
      screen.getByText('Source changed — document context cannot be reviewed'),
    ).toBeInTheDocument();
  });

  it('renders MDX code, HTML and unsafe links inertly without executing or fetching media', () => {
    const source =
      '<img src=x onerror="alert(1)"> {globalThis.alert(1)} [link](javascript:alert(1))';
    const { container } = renderDocument([
      { ...heading, type: 'paragraph', source, mappingStatus: 'CONTEXT', translatable: false },
      {
        ...heading,
        id: null,
        type: 'component',
        source: '<Widget value={run()} />',
        mappingStatus: 'CONTEXT',
        translatable: false,
      },
    ]);
    expect(container.querySelectorAll('img, script, iframe, a')).toHaveLength(0);
    expect(screen.getByText('<Widget value={run()} />')).toBeInTheDocument();
    expect(within(screen.getByRole('article')).getByText(/globalThis.alert/)).toBeInTheDocument();
  });
});
