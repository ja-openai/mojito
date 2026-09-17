import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import type { ApiReviewProjectDocument, ApiReviewProjectTextUnit } from '../../api/review-projects';
import { groupEmailDocuments } from './email-document';
import { ReviewProjectDocumentView } from './ReviewProjectDocumentView';

const documents: ApiReviewProjectDocument[] = ['subject', 'preheader', 'body'].map(
  (part, index) => ({
    assetId: index + 1,
    assetPath: `mail/welcome_${part}.mdx`,
    repositoryId: 1,
    branchId: 3,
    branchName: 'main',
    sourceContentMd5: `hash-${index}`,
    sourceLocaleTag: 'en',
    warnings: [],
    blocks: [
      {
        id: `welcome.${part}`,
        type: 'paragraph',
        depth: 0,
        source: `Source ${part}`,
        line: 2,
        translatable: true,
        mappingStatus: 'MATCHED',
        tmTextUnitId: index + 11,
        reviewProjectTextUnitId: index + 21,
        assetId: index + 1,
        assetPath: `mail/welcome_${part}.mdx`,
        targetContent: `Traduction ${part}`,
        targetStatus: 'APPROVED',
      },
    ],
  }),
);
const rows: ApiReviewProjectTextUnit[] = documents.map((document, index) => ({
  id: index + 21,
  tmTextUnit: { id: index + 11, content: document.blocks[0].source },
  baselineTmTextUnitVariant: {
    id: index + 31,
    content: `Traduction ${['subject', 'preheader', 'body'][index]}`,
  },
  currentTmTextUnitVariant: null,
}));
const props = {
  data: { documents, warnings: [] },
  textUnits: rows,
  selectedTextUnitId: null,
  localeTag: 'fr',
  loading: false,
  error: false,
  onRetry: vi.fn(),
  onSelect: vi.fn(),
};

describe('email document preview', () => {
  it('groups parts in reading order and keeps their real text-unit identities', () => {
    const grouped = groupEmailDocuments(documents);
    expect(grouped).toHaveLength(1);
    expect(grouped[0].assetId).toBe(3);
    expect(
      grouped[0].blocks
        .filter((b) => b.translatable)
        .map((b) => [b.assetId, b.tmTextUnitId, b.reviewProjectTextUnitId]),
    ).toEqual([
      [1, 11, 21],
      [2, 12, 22],
      [3, 13, 23],
    ]);
  });
  it('does not combine incomplete emails or cross repository/branch boundaries', () => {
    expect(groupEmailDocuments(documents.slice(1))).toEqual(documents.slice(1));
    for (const changed of [{ repositoryId: 2 }, { branchId: 4 }, { branchName: 'release' }]) {
      const mixed = [{ ...documents[0], ...changed }, ...documents.slice(1)];
      expect(groupEmailDocuments(mixed)).toEqual(mixed);
    }
  });
  it('opens the existing review row when selecting subject or body', () => {
    const onSelect = vi.fn();
    render(<ReviewProjectDocumentView {...props} onSelect={onSelect} />);
    fireEvent.click(
      within(screen.getByRole('region', { name: 'Email subject' })).getByRole('button'),
    );
    expect(onSelect).toHaveBeenLastCalledWith(21);
    fireEvent.keyDown(
      within(screen.getByRole('region', { name: 'Email body' })).getByRole('button'),
      { key: 'Enter' },
    );
    expect(onSelect).toHaveBeenLastCalledWith(23);
  });
  it('opens the existing content editor with the selected subject anchor', () => {
    const onEdit = vi.fn();
    render(
      <ReviewProjectDocumentView
        {...props}
        textUnits={[]}
        repositoryPreview={{ sourceLocaleTag: 'en', editing: true, onEdit }}
      />,
    );
    const button = within(screen.getByRole('region', { name: 'Email subject' })).getByRole(
      'button',
    );
    fireEvent.click(button);
    expect(onEdit).toHaveBeenCalledWith(11, button);
    expect(screen.getByText('Traduction subject')).toBeInTheDocument();
  });
});
