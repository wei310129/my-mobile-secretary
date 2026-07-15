package com.aproject.aidriven.mymobilesecretary.planner.application;

import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleItemRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 空檔與「哪天最空」的確定性計算。 */
@Service
@Transactional(readOnly = true)
public class FreeSlotService {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private final ScheduleItemRepository repository;
    private final Clock clock;

    public FreeSlotService(ScheduleItemRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<Slot> suggest(Instant from, Instant until, Duration duration, String timeOfDay) {
        Instant now = Instant.now(clock);
        Instant start = from == null ? now.plus(Duration.ofMinutes(30)) : from;
        Instant end = until == null ? start.plus(Duration.ofDays(7)) : until;
        Duration need = duration == null || duration.isNegative() || duration.isZero()
                ? Duration.ofHours(1) : duration;
        List<ScheduleItem> busy = repository.findByStatusOrderByStartAtAsc(ScheduleStatus.CONFIRMED);
        List<Slot> slots = new ArrayList<>();
        ZonedDateTime cursor = ZonedDateTime.ofInstant(start, TAIPEI)
                .withSecond(0).withNano(0);
        int minute = cursor.getMinute();
        if (minute % 30 != 0) {
            cursor = cursor.plusMinutes(30 - minute % 30);
        }
        while (cursor.toInstant().plus(need).compareTo(end) <= 0 && slots.size() < 5) {
            if (allowedTime(cursor.toLocalTime(), timeOfDay)
                    && cursor.toLocalTime().isAfter(LocalTime.of(7, 59))
                    && cursor.toLocalTime().plus(need).isBefore(LocalTime.of(23, 1))) {
                Instant candidateStart = cursor.toInstant();
                Instant candidateEnd = candidateStart.plus(need);
                boolean overlaps = busy.stream().anyMatch(item ->
                        item.getStartAt().isBefore(candidateEnd)
                                && candidateStart.isBefore(item.getEndAt()));
                if (!overlaps) {
                    slots.add(new Slot(candidateStart, candidateEnd));
                    cursor = cursor.plus(need);
                    continue;
                }
            }
            cursor = cursor.plusMinutes(30);
        }
        return slots;
    }

    public DayLoad freestDay(Instant from, Instant until) {
        LocalDate start = LocalDate.ofInstant(from == null ? Instant.now(clock) : from, TAIPEI);
        LocalDate end = LocalDate.ofInstant(until == null
                ? start.plusDays(7).atStartOfDay(TAIPEI).toInstant() : until, TAIPEI);
        List<ScheduleItem> items = repository.findByStatusOrderByStartAtAsc(ScheduleStatus.CONFIRMED);
        return start.datesUntil(end.plusDays(1))
                .map(date -> new DayLoad(date, items.stream()
                        .filter(item -> LocalDate.ofInstant(item.getStartAt(), TAIPEI).equals(date))
                        .map(item -> Duration.between(item.getStartAt(), item.getEndAt()))
                        .reduce(Duration.ZERO, Duration::plus)))
                .min(Comparator.comparing(DayLoad::busy)).orElse(new DayLoad(start, Duration.ZERO));
    }

    public boolean available(Instant from, Instant until) {
        return repository.findByStatusOrderByStartAtAsc(ScheduleStatus.CONFIRMED).stream()
                .noneMatch(item -> item.getStartAt().isBefore(until) && from.isBefore(item.getEndAt()));
    }

    private boolean allowedTime(LocalTime time, String timeOfDay) {
        if (timeOfDay == null || timeOfDay.isBlank()) return true;
        return switch (timeOfDay.toUpperCase()) {
            case "MORNING" -> time.isBefore(LocalTime.NOON);
            case "AFTERNOON" -> !time.isBefore(LocalTime.NOON) && time.isBefore(LocalTime.of(18, 0));
            case "EVENING" -> !time.isBefore(LocalTime.of(18, 0));
            default -> true;
        };
    }

    public record Slot(Instant startAt, Instant endAt) {}
    public record DayLoad(LocalDate date, Duration busy) {}
}
