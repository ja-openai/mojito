package com.box.l10n.mojito.service.content;

import java.util.List;

/** Only the immediate child directories for one branch and path prefix; no tree or counts. */
public record RepositoryContentDirectories(
    Long repositoryId,
    Long branchId,
    String directory,
    List<String> directories,
    String nextCursor) {}
