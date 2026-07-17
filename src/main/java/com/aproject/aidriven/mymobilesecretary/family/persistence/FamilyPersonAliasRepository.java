package com.aproject.aidriven.mymobilesecretary.family.persistence;

import com.aproject.aidriven.mymobilesecretary.family.domain.FamilyPersonAlias;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamilyPersonAliasRepository extends JpaRepository<FamilyPersonAlias, Long> {

    Optional<FamilyPersonAlias> findByCreatedByUserIdAndNormalizedAlias(
            UUID actorId, String normalizedAlias);

    List<FamilyPersonAlias> findByCreatedByUserIdAndPersonId(
            UUID actorId, Long personId);
}
