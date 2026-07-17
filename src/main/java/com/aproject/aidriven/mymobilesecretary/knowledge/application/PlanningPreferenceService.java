package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.PlanningPreference;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.PlanningPreferenceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PlanningPreferenceService {
    private final PlanningPreferenceRepository repository;
    private final Clock clock;

    public PlanningPreferenceService(PlanningPreferenceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public PlanningPreference setBuffers(int transferMinutes, int mealMinutes) {
        PlanningPreference preference = current();
        preference.setBuffers(transferMinutes, mealMinutes, Instant.now(clock));
        return preference;
    }

    @Transactional(readOnly = true)
    public Duration extraTransferBuffer() {
        return repository.findFirstByOrderByIdAsc()
                .map(p -> Duration.ofMinutes(p.getExtraTransferMinutes())).orElse(Duration.ZERO);
    }

    @Transactional(readOnly = true)
    public Duration mealBuffer() {
        return repository.findFirstByOrderByIdAsc()
                .map(p -> Duration.ofMinutes(p.getMealBufferMinutes())).orElse(Duration.ZERO);
    }

    private PlanningPreference current() {
        return repository.findFirstByOrderByIdAsc().orElseGet(() ->
                repository.save(PlanningPreference.create(Instant.now(clock))));
    }
}
