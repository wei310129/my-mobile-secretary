package com.aproject.aidriven.mymobilesecretary.knowledge.persistence;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.PlanningPreference;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanningPreferenceRepository extends JpaRepository<PlanningPreference, Integer> {

    Optional<PlanningPreference> findFirstByOrderByIdAsc();
}
