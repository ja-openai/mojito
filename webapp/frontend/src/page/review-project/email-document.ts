import type {
  ApiReviewProjectDocument,
  ApiReviewProjectDocumentBlock,
} from '../../api/review-projects';
import { emailCompanionPaths } from '../../utils/emailAssetPaths';

// Group only documents supplied by the authorized endpoint, in the same repository/branch.
// Each passage keeps its asset, text-unit and review-row identity.
export function groupEmailDocuments(
  documents: ApiReviewProjectDocument[],
): ApiReviewProjectDocument[] {
  const consumed = new Set<ApiReviewProjectDocument>();
  const grouped = new Map<ApiReviewProjectDocument, ApiReviewProjectDocument>();
  for (const body of documents) {
    const paths = emailCompanionPaths(body.assetPath);
    if (!paths.length) continue;
    const companions = paths.map((path) =>
      documents.find(
        (candidate) =>
          candidate.assetPath === path &&
          candidate.repositoryId === body.repositoryId &&
          candidate.branchId === body.branchId &&
          candidate.branchName === body.branchName,
      ),
    );
    if (companions.some((document) => !document)) continue;
    const parts = [...companions, body] as ApiReviewProjectDocument[];
    const blocks: ApiReviewProjectDocumentBlock[] = parts.flatMap((part, index) => {
      const name = ['Subject', 'Preheader', 'Body'][index];
      const occurrence = `email:${body.assetId}:${name}`;
      return [
        {
          id: null,
          type: 'email-part',
          depth: 0,
          source: name,
          line: 0,
          translatable: false,
          mappingStatus: 'CONTEXT',
          reviewProjectTextUnitId: null,
          tmTextUnitId: null,
          assetId: part.assetId,
          assetPath: part.assetPath,
          moduleDepth: 0,
          moduleStatus: 'EXPANDED',
          occurrenceId: occurrence,
        },
        ...part.blocks.map((block, position) => ({
          ...block,
          assetId: block.assetId ?? part.assetId,
          assetPath: block.assetPath ?? part.assetPath,
          moduleDepth: (block.moduleDepth ?? 0) + 1,
          occurrenceId: `${occurrence}/${block.occurrenceId ?? position}`,
        })),
      ];
    });
    companions.forEach((part) => consumed.add(part!));
    grouped.set(body, {
      ...body,
      blocks,
      warnings: [...new Set(parts.flatMap((part) => part.warnings))],
    });
  }
  return documents
    .filter((document) => !consumed.has(document))
    .map((document) => grouped.get(document) ?? document);
}
