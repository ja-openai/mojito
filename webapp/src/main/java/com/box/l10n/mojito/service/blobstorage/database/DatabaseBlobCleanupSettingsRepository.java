package com.box.l10n.mojito.service.blobstorage.database;

import com.box.l10n.mojito.entity.DatabaseBlobCleanupSettings;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface DatabaseBlobCleanupSettingsRepository
    extends JpaRepository<DatabaseBlobCleanupSettings, Long> {

  Optional<DatabaseBlobCleanupSettings> findFirstByOrderByIdAsc();
}
