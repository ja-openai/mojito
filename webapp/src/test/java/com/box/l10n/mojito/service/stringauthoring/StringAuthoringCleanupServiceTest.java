package com.box.l10n.mojito.service.stringauthoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Branch;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.service.branch.BranchRepository;
import com.box.l10n.mojito.service.branch.BranchService;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.Test;
import org.springframework.data.domain.Pageable;

public class StringAuthoringCleanupServiceTest {

  @Test
  public void cleanupDueBranchesSchedulesBranchDeletes() {
    BranchRepository branchRepository = mock(BranchRepository.class);
    BranchService branchService = mock(BranchService.class);
    StringAuthoringCleanupService cleanupService =
        new StringAuthoringCleanupService(branchRepository, branchService);
    Repository repository = new Repository();
    repository.setId(7L);
    Branch branch = new Branch();
    branch.setId(12L);
    branch.setName("authoring/checkout");
    branch.setRepository(repository);

    when(branchRepository
            .findByDeletedFalseAndNameStartingWithAndCleanupDateLessThanEqualOrderByCleanupDateAscIdAsc(
                eq(StringAuthoringService.AUTHORING_BRANCH_PREFIX),
                any(ZonedDateTime.class),
                any(Pageable.class)))
        .thenReturn(List.of(branch));

    assertThat(cleanupService.cleanupDueBranches(25)).isEqualTo(1);
    verify(branchService).asyncDeleteBranch(7L, 12L);
  }
}
