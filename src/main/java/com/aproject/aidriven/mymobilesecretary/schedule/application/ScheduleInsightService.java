package com.aproject.aidriven.mymobilesecretary.schedule.application;

import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 已確認行程的只讀洞察：下一個、間隔、按日分組與衝突。 */
@Service
public class ScheduleInsightService {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final ScheduleService scheduleService;
    private final Clock clock;

    public ScheduleInsightService(ScheduleService scheduleService, Clock clock) {
        this.scheduleService = scheduleService;
        this.clock = clock;
    }

    public List<ScheduleItem> upcoming() {
        Instant now = Instant.now(clock);
        return scheduleService.listSchedules(ScheduleStatus.CONFIRMED).stream()
                .filter(item -> item.getEndAt().isAfter(now))
                .toList();
    }

    public Optional<ScheduleItem> next() {
        return upcoming().stream().findFirst();
    }

    public Gap gap(ScheduleItem first, ScheduleItem second) {
        if (second.getStartAt().isBefore(first.getStartAt())) {
            ScheduleItem swap = first;
            first = second;
            second = swap;
        }
        Duration duration = Duration.between(first.getEndAt(), second.getStartAt());
        return new Gap(first, second, duration, duration.isNegative());
    }

    public Map<LocalDate, List<ScheduleItem>> groupByDay(List<ScheduleItem> items) {
        Map<LocalDate, List<ScheduleItem>> groups = new LinkedHashMap<>();
        items.forEach(item -> groups.computeIfAbsent(
                LocalDate.ofInstant(item.getStartAt(), TAIPEI), ignored -> new ArrayList<>()).add(item));
        return groups;
    }

    public List<Gap> conflicts(List<ScheduleItem> items) {
        List<Gap> conflicts = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                Gap gap = gap(items.get(i), items.get(j));
                if (gap.overlapping()) {
                    conflicts.add(gap);
                }
            }
        }
        return conflicts;
    }

    public record Gap(ScheduleItem first, ScheduleItem second,
                      Duration duration, boolean overlapping) {
    }
}
