package com.aproject.aidriven.mymobilesecretary.account.persistence;

import com.aproject.aidriven.mymobilesecretary.account.domain.ExternalIdentity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalIdentityRepository extends JpaRepository<ExternalIdentity, UUID> {

    Optional<ExternalIdentity> findByProviderAndSubject(String provider, String subject);

    Optional<ExternalIdentity> findByUserIdAndProvider(UUID userId, String provider);
}
