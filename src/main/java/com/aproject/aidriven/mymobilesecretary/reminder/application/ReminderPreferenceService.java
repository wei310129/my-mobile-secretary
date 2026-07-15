package com.aproject.aidriven.mymobilesecretary.reminder.application;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.ReminderPreference;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.ReminderPreferenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 勿擾規則的設定與「最早何時可提醒」計算。 */
@Service
@Transactional
public class ReminderPreferenceService {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final ReminderPreferenceRepository repository;
    private final Clock clock;

    public ReminderPreferenceService(ReminderPreferenceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public ReminderPreference setQuietHours(LocalTime start, LocalTime end, boolean allowHighPriority) {
        ReminderPreference preference = current();
        preference.setQuietHours(start, end, allowHighPriority, Instant.now(clock));
        return preference;
    }

    public ReminderPreference clearQuietHours() {
        ReminderPreference preference = current();
        preference.clearQuietHours(Instant.now(clock));
        return preference;
    }

    public ReminderPreference muteUntil(Instant until) {
        ReminderPreference preference = current();
        preference.muteUntil(until, Instant.now(clock));
        return preference;
    }

    public ReminderPreference resumeNow() {
        ReminderPreference preference = current();
        preference.resumeNow(Instant.now(clock));
        return preference;
    }

    @Transactional(readOnly = true)
    public Optional<ReminderPreference> preference() {
        return repository.findById(1);
    }

    /** 回 empty 表示現在可送;有值表示應把同一提醒延後到該時間。 */
    @Transactional(readOnly = true)
    public Optional<Instant> deferUntil(Task task, Instant now) {
        ReminderPreference preference = repository.findById(1).orElse(null);
        if (preference == null
                || (task.getPriority() == TaskPriority.HIGH && preference.isAllowHighPriority())) {
            return Optional.empty();
        }

        Instant candidate = now;
        if (preference.getMutedUntil() != null && preference.getMutedUntil().isAfter(candidate)) {
            candidate = preference.getMutedUntil();
        }
        candidate = moveOutsideQuietHours(candidate, preference);
        return candidate.isAfter(now) ? Optional.of(candidate) : Optional.empty();
    }

    private Instant moveOutsideQuietHours(Instant candidate, ReminderPreference preference) {
        LocalTime start = preference.getQuietStart();
        LocalTime end = preference.getQuietEnd();
        if (start == null || end == null) {
            return candidate;
        }
        ZonedDateTime local = candidate.atZone(TAIPEI);
        LocalTime time = local.toLocalTime();
        boolean overnight = start.isAfter(end);
        boolean quiet = overnight
                ? !time.isBefore(start) || time.isBefore(end)
                : !time.isBefore(start) && time.isBefore(end);
        if (!quiet) {
            return candidate;
        }
        LocalDate endDate = overnight && !time.isBefore(start)
                ? local.toLocalDate().plusDays(1) : local.toLocalDate();
        return ZonedDateTime.of(endDate, end, TAIPEI).toInstant();
    }

    private ReminderPreference current() {
        return repository.findById(1).orElseGet(() ->
                repository.save(ReminderPreference.create(Instant.now(clock))));
    }
}
