package com.box.l10n.mojito.service.glossary;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;

public interface TermIndexExtractedTermRepositoryCustom {

  List<TermIndexExtractedTermRepository.SearchRow> searchEntries(
      boolean repositoryIdsEmpty,
      Collection<Long> repositoryIds,
      String searchQuery,
      String extractionMethod,
      String reviewStatusFilter,
      String reviewAuthorityFilter,
      long minOccurrences,
      ZonedDateTime lastOccurrenceAfter,
      ZonedDateTime lastOccurrenceBefore,
      ZonedDateTime reviewChangedAfter,
      ZonedDateTime reviewChangedBefore,
      String sortBy,
      Pageable pageable);
}
