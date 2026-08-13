package com.box.l10n.mojito.service.blobstorage.database;

import com.box.l10n.mojito.entity.DatabaseBlobCleanupPolicy;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface DatabaseBlobCleanupPolicyRepository
    extends JpaRepository<DatabaseBlobCleanupPolicy, Long> {

  List<DatabaseBlobCleanupPolicy> findAllByOrderByPrefixAsc();

  List<DatabaseBlobCleanupPolicy> findByEnabledTrueOrderByPrefixAsc();

  boolean existsByPrefix(String prefix);
}
