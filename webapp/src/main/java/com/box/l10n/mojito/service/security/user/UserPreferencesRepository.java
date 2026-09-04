package com.box.l10n.mojito.service.security.user;

import com.box.l10n.mojito.entity.security.user.UserPreferencesEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface UserPreferencesRepository extends JpaRepository<UserPreferencesEntity, Long> {
  Optional<UserPreferencesEntity> findByUserId(Long userId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from UserPreferencesEntity p where p.user.id = ?1")
  Optional<UserPreferencesEntity> findForUpdateByUserId(Long userId);
}
