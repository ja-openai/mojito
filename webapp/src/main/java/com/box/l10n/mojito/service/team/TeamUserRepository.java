package com.box.l10n.mojito.service.team;

import com.box.l10n.mojito.entity.TeamUser;
import com.box.l10n.mojito.entity.TeamUserRole;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface TeamUserRepository extends JpaRepository<TeamUser, Long> {

  @Query(
      """
      select vu
      from TeamUser vu
      join fetch vu.team v
      where vu.user.id = :userId
        and v.enabled = true
        and vu.role = :role
      order by lower(v.name), v.id
      """)
  List<TeamUser> findByUserIdAndRole(
      @Param("userId") Long userId, @Param("role") TeamUserRole role);

  @Query(
      """
      select vu
      from TeamUser vu
      join fetch vu.user u
      where vu.team.id = :teamId
        and vu.role = :role
      order by lower(u.username), u.id
      """)
  List<TeamUser> findByTeamIdAndRole(
      @Param("teamId") Long teamId, @Param("role") TeamUserRole role);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from TeamUser vu
      where vu.team.id = :teamId
        and vu.role = :role
      """)
  int deleteByTeamIdAndRole(@Param("teamId") Long teamId, @Param("role") TeamUserRole role);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from TeamUser vu
      where vu.user.id = :userId
        and vu.role = :role
      """)
  int deleteByUserIdAndRole(@Param("userId") Long userId, @Param("role") TeamUserRole role);

  boolean existsByTeam_IdAndUser_IdAndRole(Long teamId, Long userId, TeamUserRole role);

  @Query(
      """
      select v.id as teamId, v.name as teamName
      from TeamUser vu
      join vu.team v
      where vu.user.id = :userId
        and v.enabled = true
      order by lower(v.name), v.id
      """)
  List<UserTeamProjection> findUserTeams(@Param("userId") Long userId);

  @Query(
      """
      select vu
      from TeamUser vu
      join fetch vu.team v
      where vu.user.id = :userId
        and v.enabled = true
      order by lower(v.name), v.id
      """)
  List<TeamUser> findByUserId(@Param("userId") Long userId);

  @Query(
      """
      select new com.box.l10n.mojito.service.team.UserTeamByUserProjection(
        vu.user.id,
        v.id,
        v.name
      )
      from TeamUser vu
      join vu.team v
      where vu.user.id in :userIds
        and v.enabled = true
      order by vu.user.id, lower(v.name), v.id
      """)
  List<UserTeamByUserProjection> findUserTeamsByUserIds(@Param("userIds") List<Long> userIds);
}
