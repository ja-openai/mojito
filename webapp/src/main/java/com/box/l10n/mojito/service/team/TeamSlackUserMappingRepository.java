package com.box.l10n.mojito.service.team;

import com.box.l10n.mojito.entity.TeamSlackUserMapping;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface TeamSlackUserMappingRepository extends JpaRepository<TeamSlackUserMapping, Long> {

  @Query(
      """
      select m
      from TeamSlackUserMapping m
      join fetch m.mojitoUser u
      where m.team.id = :teamId
      order by lower(u.username), u.id
      """)
  List<TeamSlackUserMapping> findByTeamId(@Param("teamId") Long teamId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from TeamSlackUserMapping m
      where m.team.id = :teamId
      """)
  int deleteByTeamId(@Param("teamId") Long teamId);
}
