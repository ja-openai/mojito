package com.box.l10n.mojito.service.stringauthoring;

import com.box.l10n.mojito.entity.Branch;
import com.box.l10n.mojito.service.branch.BranchRepository;
import com.box.l10n.mojito.service.branch.BranchService;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StringAuthoringCleanupService {

  private final BranchRepository branchRepository;
  private final BranchService branchService;

  public StringAuthoringCleanupService(
      BranchRepository branchRepository, BranchService branchService) {
    this.branchRepository = branchRepository;
    this.branchService = branchService;
  }

  @Transactional
  public int cleanupDueBranches(int batchSize) {
    Pageable page = PageRequest.of(0, Math.max(1, batchSize));
    List<Branch> branches =
        branchRepository
            .findByDeletedFalseAndNameStartingWithAndCleanupDateLessThanEqualOrderByCleanupDateAscIdAsc(
                StringAuthoringService.AUTHORING_BRANCH_PREFIX, ZonedDateTime.now(), page);

    branches.forEach(
        branch -> branchService.asyncDeleteBranch(branch.getRepository().getId(), branch.getId()));
    return branches.size();
  }
}
