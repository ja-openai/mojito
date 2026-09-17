package com.box.l10n.mojito.service.content;

import com.box.l10n.mojito.service.review.ReviewProjectDocumentView;
import java.util.List;

public record RepositoryContentPreview(
    Long repositoryId,
    Long branchId,
    String branchName,
    String sourceLocaleTag,
    String localeTag,
    ReviewProjectDocumentView.Document document,
    List<String> warnings) {}
