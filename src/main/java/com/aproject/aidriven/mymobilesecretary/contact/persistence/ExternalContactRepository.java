package com.aproject.aidriven.mymobilesecretary.contact.persistence;

import com.aproject.aidriven.mymobilesecretary.contact.domain.ExternalContact;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalContactRepository extends JpaRepository<ExternalContact, Long> {
    Optional<ExternalContact> findByCreatedByUserIdAndCanonicalKey(UUID actorId, String canonicalKey);
    List<ExternalContact> findByCreatedByUserIdOrderByUpdatedAtDesc(UUID actorId);
}
