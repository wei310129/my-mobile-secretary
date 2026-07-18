package com.aproject.aidriven.mymobilesecretary.knowledge.persistence;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.LifestyleWindow;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LifestyleWindowRepository extends JpaRepository<LifestyleWindow, Long> {

    Optional<LifestyleWindow> findByDayTypeAndKind(
            LifestyleWindow.DayType dayType, LifestyleWindow.Kind kind);

    List<LifestyleWindow> findByDayTypeOrderByStartTimeAsc(LifestyleWindow.DayType dayType);
}
