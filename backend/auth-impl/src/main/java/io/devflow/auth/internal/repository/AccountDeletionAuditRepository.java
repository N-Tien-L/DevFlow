package io.devflow.auth.internal.repository;

import io.devflow.auth.internal.entity.AccountDeletionAuditEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link AccountDeletionAuditEntity}.
 */
@Repository
public interface AccountDeletionAuditRepository extends JpaRepository<AccountDeletionAuditEntity, UUID> {
}
