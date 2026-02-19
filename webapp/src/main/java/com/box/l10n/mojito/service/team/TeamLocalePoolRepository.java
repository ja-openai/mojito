package com.box.l10n.mojito.service.team;

import com.box.l10n.mojito.entity.TeamLocalePool;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface TeamLocalePoolRepository extends JpaRepository<TeamLocalePool, Long> {

  @Query(
      """
      select new com.box.l10n.mojito.service.team.TeamLocalePoolRowProjection(
        l.bcp47Tag,
        u.id
      )
      from TeamLocalePool vlp
      join vlp.locale l
      join vlp.translatorUser u
      where vlp.team.id = :teamId
      order by lower(l.bcp47Tag), vlp.position, u.id
      """)
  List<TeamLocalePoolRowProjection> findByTeamId(@Param("teamId") Long teamId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from TeamLocalePool vlp
      where vlp.team.id = :teamId
      """)
  int deleteByTeamId(@Param("teamId") Long teamId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from TeamLocalePool vlp
      where vlp.team.id = :teamId
        and vlp.translatorUser.id in :translatorUserIds
      """)
  int deleteByTeamIdAndTranslatorUserIds(
      @Param("teamId") Long teamId, @Param("translatorUserIds") List<Long> translatorUserIds);
}
