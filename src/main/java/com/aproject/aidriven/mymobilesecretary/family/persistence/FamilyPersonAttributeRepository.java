package com.aproject.aidriven.mymobilesecretary.family.persistence;

import com.aproject.aidriven.mymobilesecretary.family.domain.FamilyPersonAttribute;
import com.aproject.aidriven.mymobilesecretary.family.domain.FamilyPersonAttribute.Key;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyPersonAttributeRepository
        extends JpaRepository<FamilyPersonAttribute, Long> {

    Optional<FamilyPersonAttribute> findByCreatedByUserIdAndPersonIdAndKey(
            UUID actorId, Long personId, Key key);
}
