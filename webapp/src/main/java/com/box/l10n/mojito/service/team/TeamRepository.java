package com.box.l10n.mojito.service.team;

import com.box.l10n.mojito.entity.Team;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface TeamRepository extends JpaRepository<Team, Long> {

  @Query(
      """
      select v
      from Team v
      where v.deleted = false
      order by lower(v.name), v.id
      """)
  List<Team> findAllOrderedNotDeleted();

  Optional<Team> findByIdAndDeletedFalse(Long id);

  @Query(
      """
      select v
      from Team v
      where lower(v.name) = lower(:name)
        and v.deleted = false
      """)
  Optional<Team> findByNameIgnoreCaseAndDeletedFalse(@Param("name") String name);
}
