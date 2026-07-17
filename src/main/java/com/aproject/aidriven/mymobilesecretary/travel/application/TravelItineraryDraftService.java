package com.aproject.aidriven.mymobilesecretary.travel.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.intent.application.ReceiptCommand;
import com.aproject.aidriven.mymobilesecretary.shared.security.PromptInjectionGuard;
import com.aproject.aidriven.mymobilesecretary.travel.domain.TravelItineraryDraft;
import com.aproject.aidriven.mymobilesecretary.travel.domain.TravelItineraryDraft.Status;
import com.aproject.aidriven.mymobilesecretary.travel.persistence.TravelItineraryDraftRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores normalized itinerary-image output as an actor-private, expiring confirmation draft. */
@Service
@Transactional
public class TravelItineraryDraftService {

    private static final int MAX_ENTRIES = 50;
    private static final int MAX_AUXILIARY_LINES = 30;

    private final TravelItineraryDraftRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration retention;

    public TravelItineraryDraftService(
            TravelItineraryDraftRepository repository,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${app.travel.itinerary-draft-retention:30d}") Duration retention) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.retention = retention;
    }

    public DraftView create(ReceiptCommand command) {
        if (command == null || command.documentType() != ReceiptCommand.DocumentType.TRAVEL_ITINERARY) {
            throw new IllegalArgumentException("travel itinerary draft missing document type");
        }
        Payload payload = sanitize(command);
        rejectInstructionLikeContent(command.documentTitle(), payload);
        if (payload.entries().isEmpty() && payload.activities().isEmpty()
                && payload.notices().isEmpty()) {
            throw new IllegalArgumentException("travel itinerary draft has no readable content");
        }
        Instant now = Instant.now(clock);
        latestPendingEntity(now).ifPresent(previous -> previous.discard(now));
        String title = limited(command.documentTitle(), 160, "旅行行程表");
        String json = serialize(payload);
        if (json.length() > 30000) {
            throw new IllegalArgumentException("travel itinerary draft payload too large");
        }
        TravelItineraryDraft saved = repository.save(TravelItineraryDraft.create(
                title, json, now.plus(retention), now));
        return view(saved, payload);
    }

    @Transactional(readOnly = true)
    public Optional<DraftView> latestPending() {
        Instant now = Instant.now(clock);
        return latestPendingEntity(now).map(this::view);
    }

    public Optional<DraftView> confirmLatest() {
        Instant now = Instant.now(clock);
        return latestPendingEntity(now).map(draft -> {
            draft.confirm(now);
            return view(draft);
        });
    }

    public Optional<DraftView> discardLatest() {
        Instant now = Instant.now(clock);
        return latestPendingEntity(now).map(draft -> {
            draft.discard(now);
            return view(draft);
        });
    }

    /** Must run under SYSTEM scope; the database policy grants only global expired-row deletion. */
    public int purgeExpired() {
        return repository.deleteExpiredForSystem(Instant.now(clock));
    }

    private Optional<TravelItineraryDraft> latestPendingEntity(Instant now) {
        UUID actorId = WorkspaceContextHolder.requireContext().actorId();
        return repository
                .findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                        actorId, Status.PENDING, now);
    }

    private DraftView view(TravelItineraryDraft draft) {
        try {
            return view(draft, objectMapper.readValue(draft.getPayload(), Payload.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored travel itinerary draft is invalid", e);
        }
    }

    private static DraftView view(TravelItineraryDraft draft, Payload payload) {
        return new DraftView(draft.getId(), draft.getTitle(), draft.getStatus(), payload,
                draft.getExpiresAt());
    }

    private Payload sanitize(ReceiptCommand command) {
        List<Entry> entries = safe(command.itineraryEntries()).stream()
                .filter(java.util.Objects::nonNull)
                .limit(MAX_ENTRIES)
                .map(line -> new Entry(
                        limited(line.date(), 20, null),
                        limited(line.startTime(), 10, null),
                        limited(line.endTime(), 10, null),
                        limited(line.title(), 160, "未命名行程"),
                        limited(line.placeName(), 160, null),
                        limited(line.details(), 500, null)))
                .toList();
        return new Payload(entries,
                sanitizeLines(command.activities()), sanitizeLines(command.notices()));
    }

    private static List<String> sanitizeLines(List<String> values) {
        return safe(values).stream().filter(java.util.Objects::nonNull)
                .map(value -> limited(value, 500, null))
                .filter(java.util.Objects::nonNull)
                .limit(MAX_AUXILIARY_LINES).toList();
    }

    private String serialize(Payload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("travel itinerary draft cannot be serialized", e);
        }
    }

    private static void rejectInstructionLikeContent(String documentTitle, Payload payload) {
        List<String> values = new java.util.ArrayList<>();
        values.add(documentTitle);
        for (Entry entry : payload.entries()) {
            values.add(entry.title());
            values.add(entry.placeName());
            values.add(entry.details());
        }
        values.addAll(payload.activities());
        values.addAll(payload.notices());
        if (PromptInjectionGuard.inspectExternalContent(values).suspicious()) {
            throw new IllegalArgumentException("travel itinerary contains instruction-like content");
        }
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String limited(String value, int max, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String stripped = value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }

    public record Entry(String date, String startTime, String endTime,
                        String title, String placeName, String details) {
    }

    public record Payload(List<Entry> entries, List<String> activities, List<String> notices) {
        public Payload {
            entries = entries == null ? List.of() : List.copyOf(entries);
            activities = activities == null ? List.of() : List.copyOf(activities);
            notices = notices == null ? List.of() : List.copyOf(notices);
        }
    }

    public record DraftView(Long id, String title, Status status,
                            Payload payload, Instant expiresAt) {
    }
}
