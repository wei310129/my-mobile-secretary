package com.aproject.aidriven.mymobilesecretary.family.persistence;

import com.aproject.aidriven.mymobilesecretary.family.domain.FamilyPersonProfile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyPersonProfileRepository
        extends JpaRepository<FamilyPersonProfile, Long> {

    Optional<FamilyPersonProfile> findByCreatedByUserIdAndCanonicalKey(
            UUID actorId, String canonicalKey);

    List<FamilyPersonProfile> findByCreatedByUserIdOrderByUpdatedAtDesc(UUID actorId);
}
