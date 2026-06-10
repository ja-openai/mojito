package com.box.l10n.mojito.service.branch;

import com.box.l10n.mojito.entity.Branch;
import com.box.l10n.mojito.entity.Repository;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.EntityGraph.EntityGraphType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

/**
 * @author jeanaurambault
 */
@RepositoryRestResource(exported = false)
public interface BranchRepository
    extends JpaRepository<Branch, Long>, JpaSpecificationExecutor<Branch> {

  @Override
  @EntityGraph(value = "Branch.legacy", type = EntityGraphType.FETCH)
  Optional<Branch> findById(Long aLong);

  Branch findByNameAndRepository(String name, Repository repository);

  Optional<Branch> findByIdAndRepositoryIdAndDeletedFalse(Long id, Long repositoryId);

  List<Branch> findByRepositoryIdAndDeletedFalseAndNameIsNotNullOrderByNameAsc(
      Long repositoryId, Pageable pageable);

  List<Branch> findByRepositoryIdAndDeletedFalseAndNameStartingWithOrderByNameAsc(
      Long repositoryId, String namePrefix, Pageable pageable);

  List<Branch> findByRepositoryIdAndDeletedFalseAndNameIsNotNullOrderByCreatedDateDescNameAsc(
      Long repositoryId, Pageable pageable);

  List<Branch> findByRepositoryIdAndDeletedFalseOrderByCreatedDateDescNameAsc(
      Long repositoryId, Pageable pageable);

  List<Branch> findByRepositoryIdAndDeletedFalseAndNameStartingWithOrderByCreatedDateDescNameAsc(
      Long repositoryId, String namePrefix, Pageable pageable);

  @EntityGraph(attributePaths = "repository")
  List<Branch>
      findByDeletedFalseAndNameStartingWithAndCleanupDateLessThanEqualOrderByCleanupDateAscIdAsc(
          String namePrefix, ZonedDateTime cleanupDate, Pageable pageable);

  List<Branch> findByRepositoryIdAndDeletedFalseAndNameNotNullAndNameNot(
      Long repositoryId, String primaryBranch);

  List<Branch> findByDeletedFalseAndNameNotNullAndNameNot(String primaryBranch);
}
