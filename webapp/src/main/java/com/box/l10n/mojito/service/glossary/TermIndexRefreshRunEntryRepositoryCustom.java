package com.box.l10n.mojito.service.glossary;

import java.util.Collection;

public interface TermIndexRefreshRunEntryRepositoryCustom {

  int insertEntries(Long refreshRunId, Collection<Long> termIndexExtractedTermIds);

  int insertExistingRepositoryEntries(Long refreshRunId, Long repositoryId);
}
