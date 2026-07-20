package com.aproject.aidriven.mymobilesecretary.schedule.decision.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.decision.domain.ConditionalVenueDraft;
import com.aproject.aidriven.mymobilesecretary.schedule.decision.persistence.ConditionalVenueDraftRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ConditionalVenueService {

    private final ConditionalVenueDraftRepository repository;
    private final TaskService taskService;
    private final ScheduleService scheduleService;
    private final PlaceService placeService;
    private final Clock clock;

    public ConditionalVenueService(
            ConditionalVenueDraftRepository repository, TaskService taskService,
            ScheduleService scheduleService, PlaceService placeService, Clock clock) {
        this.repository = repository;
        this.taskService = taskService;
        this.scheduleService = scheduleService;
        this.placeService = placeService;
        this.clock = clock;
    }

    public ConditionalVenueDraft createDraft(
            String title, Instant eventStartAt, Duration duration,
            String primaryPlaceName, String fallbackPlaceName, Instant decisionAt) {
        Instant now = Instant.now(clock);
        if (decisionAt.isBefore(now.plus(Duration.ofMinutes(5)))) {
            throw new IllegalArgumentException("decision reminder must be at least five minutes later");
        }
        if (!decisionAt.isBefore(eventStartAt)) {
            throw new IllegalArgumentException("decision reminder must precede the event");
        }
        Task reminder = taskService.createTask(
                "確認「%s」最終場地".formatted(title),
                "原定「%s」；若條件不成立則改為「%s」。只選一個場地後才建立行程。"
                        .formatted(primaryPlaceName, fallbackPlaceName),
                TaskPriority.NORMAL, decisionAt, Task.Category.PERSONAL,
                Task.Recurrence.NONE, Task.ConditionType.NONE, null);
        return repository.save(ConditionalVenueDraft.create(
                title, eventStartAt, eventStartAt.plus(duration),
                primaryPlaceName, fallbackPlaceName, decisionAt,
                reminder.getId(), now));
    }

    @Transactional(readOnly = true)
    public Optional<ConditionalVenueDraft> latestPending() {
        return repository.findFirstByCreatedByUserIdAndStatusOrderByCreatedAtDesc(
                WorkspaceContextHolder.requireContext().actorId(),
                ConditionalVenueDraft.Status.PENDING);
    }

    public Resolution resolve(ConditionalVenueDraft draft, String selectedPlaceName) {
        String selected = canonicalSelection(draft, selectedPlaceName);
        Long placeId = findKnownPlace(selected).map(Place::getId).orElse(null);
        ScheduleService.ScheduleDecision decision = scheduleService.createSchedule(
                draft.getTitle(), draft.getEventStartAt(), draft.getEventEndAt(), placeId);
        draft.resolve(selected, decision.item().getId(), Instant.now(clock));
        taskService.confirmTask(draft.getDecisionTaskId());
        return new Resolution(draft, decision);
    }

    private static String canonicalSelection(ConditionalVenueDraft draft, String selected) {
        if (draft.getPrimaryPlaceName().equalsIgnoreCase(selected)) return draft.getPrimaryPlaceName();
        if (draft.getFallbackPlaceName().equalsIgnoreCase(selected)) return draft.getFallbackPlaceName();
        throw new IllegalArgumentException("selected place is not a draft option");
    }

    private Optional<Place> findKnownPlace(String placeName) {
        return placeService.listPlaces().stream()
                .filter(place -> place.getName().equalsIgnoreCase(placeName))
                .findFirst();
    }

    public record Resolution(
            ConditionalVenueDraft draft, ScheduleService.ScheduleDecision scheduleDecision) {
    }
}
