package com.box.l10n.mojito.service.team;

import com.box.l10n.mojito.entity.TeamPmPool;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface TeamPmPoolRepository extends JpaRepository<TeamPmPool, Long> {

  @Query(
      """
      select new com.box.l10n.mojito.service.team.TeamPmPoolRowProjection(
        p.id
      )
      from TeamPmPool tpp
      join tpp.pmUser p
      where tpp.team.id = :teamId
      order by tpp.position, p.id
      """)
  List<TeamPmPoolRowProjection> findByTeamId(@Param("teamId") Long teamId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from TeamPmPool tpp
      where tpp.team.id = :teamId
      """)
  int deleteByTeamId(@Param("teamId") Long teamId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from TeamPmPool tpp
      where tpp.team.id = :teamId
        and tpp.pmUser.id in :pmUserIds
      """)
  int deleteByTeamIdAndPmUserIds(
      @Param("teamId") Long teamId, @Param("pmUserIds") List<Long> pmUserIds);
}
