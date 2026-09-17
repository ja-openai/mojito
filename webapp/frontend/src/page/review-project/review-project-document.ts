import type {
  ApiReviewProjectDocumentBlock,
  ApiReviewProjectDocuments,
  ApiReviewProjectTextUnit,
} from '../../api/review-projects';

export function documentBlockRow(
  block: ApiReviewProjectDocumentBlock,
  rows: ReadonlyMap<number, ApiReviewProjectTextUnit>,
): ApiReviewProjectTextUnit | null {
  const row =
    block.reviewProjectTextUnitId == null ? null : rows.get(block.reviewProjectTextUnitId);
  return block.mappingStatus === 'MATCHED' &&
    row?.tmTextUnit?.id === block.tmTextUnitId &&
    row.tmTextUnit.content === block.source
    ? row
    : null;
}

export function documentReviewRows(
  data: ApiReviewProjectDocuments,
  textUnits: ApiReviewProjectTextUnit[],
): ApiReviewProjectTextUnit[] {
  const rows = new Map(textUnits.map((row) => [row.id, row]));
  const ordered = new Map<number, ApiReviewProjectTextUnit>();
  for (const document of data.documents) {
    for (const block of document.blocks) {
      const row = documentBlockRow(block, rows);
      if (row) ordered.set(row.id, row);
    }
  }
  return [...ordered.values()];
}
