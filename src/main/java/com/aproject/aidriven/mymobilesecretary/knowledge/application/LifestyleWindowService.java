package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.LifestyleWindow;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.LifestyleWindowRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class LifestyleWindowService {

    private final LifestyleWindowRepository repository;
    private final Clock clock;

    public LifestyleWindowService(LifestyleWindowRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public LifestyleWindow set(LifestyleWindow.DayType dayType, LifestyleWindow.Kind kind,
                               LocalTime start, LocalTime end) {
        Instant now = Instant.now(clock);
        LifestyleWindow value = repository.findByDayTypeAndKind(dayType, kind)
                .orElseGet(() -> LifestyleWindow.create(dayType, kind, start, end, now));
        value.update(dayType, kind, start, end, now);
        return value.getId() == null ? repository.save(value) : value;
    }

    @Transactional(readOnly = true)
    public List<LifestyleWindow> list(LifestyleWindow.DayType dayType) {
        return repository.findByDayTypeOrderByStartTimeAsc(dayType);
    }
}
