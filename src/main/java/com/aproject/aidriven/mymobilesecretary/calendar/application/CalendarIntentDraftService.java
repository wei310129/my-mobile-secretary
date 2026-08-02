package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarRouteRiskCoordinator;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.calendar.recurrence.CalendarRecurrenceRegistrationService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationScopeResolver;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarIntentDraftService {

    private static final Duration RETENTION = Duration.ofDays(7);

    private final JdbcTemplate jdbc;
    private final CalendarApplicationService calendars;
    private final ConversationScopeResolver scopes;
    private final PlaceAliasService places;
    private final Clock clock;
    private final CalendarIntentDraftPreflightService preflights;
    private final CalendarRouteRiskCoordinator routeRisks;
    private final CalendarRecurrenceRegistrationService recurrences;

    public CalendarIntentDraftService(
            JdbcTemplate jdbc,
            CalendarApplicationService calendars,
            ConversationScopeResolver scopes,
            PlaceAliasService places,
            Clock clock,
            CalendarIntentDraftPreflightService preflights,
            CalendarRouteRiskCoordinator routeRisks,
            CalendarRecurrenceRegistrationService recurrences) {
        this.jdbc = jdbc;
        this.calendars = calendars;
        this.scopes = scopes;
        this.places = places;
        this.clock = clock;
        this.preflights = preflights;
        this.routeRisks = routeRisks;
        this.recurrences = recurrences;
    }

    public DraftView propose(IntentCommand command) {
        requireTitle(command.title());
        var recurrence = CalendarIntentRecurrencePolicy.resolve(command);
        if (!recurrence.valid()) {
            throw new IllegalArgumentException("Calendar recurrence is not grounded");
        }
        WorkspaceContext context = tenantContext();
        var scope = scopes.current(context);
        CalendarPlacement placement = CalendarIntentPlacementResolver.resolve(command);
        CalendarLocation location = resolveLocation(command.placeName());
        Instant now = clock.instant();
        String requestHash = hash(
                "calendar-intent-draft-v1|"
                        + context.workspaceId() + "|"
                        + context.actorId() + "|"
                        + scope.digest() + "|"
                        + RequestCorrelationContext.currentId());
        StoredDraft replay = findByRequestHash(context, requestHash);
        if (replay != null) return view(replay);

        UUID id = UUID.randomUUID();
        PlacementColumns columns = PlacementColumns.from(placement);
        jdbc.update(
                """
                INSERT INTO calendar_intent_draft (
                    id, channel, conversation_scope_digest,
                    scope_key_version, title, placement_kind,
                    timed_start, timed_end, zone_id,
                    all_day_start, all_day_end_exclusive,
                    category, location_label, latitude, longitude,
                    recurrence_rule, recurrence_until,
                    status, revision, request_hash,
                    confirmation_hash, materialized_plan_id,
                    expires_at, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'PENDING', 1, ?, NULL, NULL, ?, ?, ?, ?, ?)
                """,
                id,
                context.channel().name(),
                scope.digest(),
                scope.keyVersion(),
                command.title().strip(),
                placement.kind().name(),
                columns.timedStart(),
                columns.timedEnd(),
                columns.zoneId(),
                columns.allDayStart(),
                columns.allDayEndExclusive(),
                optional(command.safeOptions().category()),
                location == null ? null : location.label(),
                location == null ? null : location.latitude(),
                location == null ? null : location.longitude(),
                recurrence.rule(),
                recurrence.until(),
                requestHash,
                Timestamp.from(now.plus(RETENTION)),
                Timestamp.from(now),
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId());
        return view(loadForUpdate(context, id));
    }

    public DraftView revise(
            UUID draftId, long expectedRevision, IntentCommand correction) {
        if (draftId == null || expectedRevision < 1 || correction == null) {
            throw new IllegalArgumentException(
                    "Draft identity, revision and correction are required");
        }
        WorkspaceContext context = tenantContext();
        StoredDraft current = loadForUpdate(context, draftId);
        requirePendingRevision(current, expectedRevision);
        String title = correction.title() == null || correction.title().isBlank()
                ? current.title()
                : correction.title().strip();
        CalendarPlacement placement = correctedPlacement(current.placement(), correction);
        CalendarLocation location = correction.placeName() == null
                        || correction.placeName().isBlank()
                ? current.location()
                : resolveLocation(correction.placeName());
        String category = correction.safeOptions().category() == null
                        || correction.safeOptions().category().isBlank()
                ? current.category()
                : correction.safeOptions().category().strip();
        requireCategory(category);
        PlacementColumns columns = PlacementColumns.from(placement);
        Instant now = clock.instant();
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET title = ?, placement_kind = ?,
                    timed_start = ?, timed_end = ?, zone_id = ?,
                    all_day_start = ?, all_day_end_exclusive = ?,
                    category = ?, location_label = ?,
                    latitude = ?, longitude = ?,
                    route_preflight_status = NULL,
                    route_preflight_hash = NULL,
                    revision = revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND status = 'PENDING' AND revision = ?
                """,
                title,
                placement.kind().name(),
                columns.timedStart(),
                columns.timedEnd(),
                columns.zoneId(),
                columns.allDayStart(),
                columns.allDayEndExclusive(),
                optional(category),
                location == null ? null : location.label(),
                location == null ? null : location.latitude(),
                location == null ? null : location.longitude(),
                Timestamp.from(now),
                draftId,
                context.workspaceId(),
                context.actorId(),
                expectedRevision);
        if (changed != 1) throw stale();
        return view(loadForUpdate(context, draftId));
    }

    public ConfirmationResult confirm(UUID draftId, long expectedRevision) {
        if (draftId == null || expectedRevision < 1) {
            throw new IllegalArgumentException(
                    "Draft identity and positive revision are required");
        }
        WorkspaceContext context = tenantContext();
        StoredDraft draft = loadForUpdate(context, draftId);
        if (draft.status() == Status.MATERIALIZED
                && draft.revision() == expectedRevision + 1) {
            return ConfirmationResult.completed(view(draft));
        }
        requirePendingRevision(draft, expectedRevision);
        var preflight = preflights.assess(view(draft));
        if (preflight.requiresConfirmation()
                && (!preflight.fingerprint().equals(draft.routePreflightHash())
                        || !preflight.status().equals(draft.routePreflightStatus()))) {
            int changed = jdbc.update(
                    """
                    UPDATE calendar_intent_draft
                    SET route_preflight_status = ?,
                        route_preflight_hash = ?,
                        revision = revision + 1, updated_at = ?
                    WHERE id = ? AND workspace_id = ?
                      AND created_by_user_id = ?
                      AND status = 'PENDING' AND revision = ?
                    """,
                    preflight.status(),
                    preflight.fingerprint(),
                    Timestamp.from(clock.instant()),
                    draftId,
                    context.workspaceId(),
                    context.actorId(),
                    expectedRevision);
            if (changed != 1) throw stale();
            return ConfirmationResult.awaiting(
                    view(loadForUpdate(context, draftId)), preflight);
        }
        DraftView materialized = materialize(draftId, expectedRevision);
        if ("ROUTE_RISK".equals(preflight.status())) {
            routeRisks.confirmCurrentRisksForPlan(materialized.materializedPlanId());
        }
        return ConfirmationResult.completed(materialized);
    }

    public DraftView materialize(UUID draftId, long expectedRevision) {
        if (draftId == null || expectedRevision < 1) {
            throw new IllegalArgumentException(
                    "Draft identity and positive revision are required");
        }
        WorkspaceContext context = tenantContext();
        StoredDraft draft = loadForUpdate(context, draftId);
        if (draft.status() == Status.MATERIALIZED
                && draft.revision() == expectedRevision + 1) {
            return view(draft);
        }
        requirePendingRevision(draft, expectedRevision);
        String confirmationHash = hash(
                "calendar-intent-confirm-v1|"
                        + draft.id() + "|"
                        + expectedRevision + "|"
                        + draft.requestHash());
        List<CalendarNodeDraft> nodes = startNode(draft);
        CalendarPlanIdentityView plan = calendars.createPlanWithIdentity(
                new CreateCalendarPlanCommand(
                        "calendar-draft:" + confirmationHash,
                        draft.title(),
                        draft.placement(),
                        draft.category(),
                        null,
                        null,
                        List.of(),
                        nodes));
        if (draft.recurrenceRule() != null) {
            recurrences.registerPlan(
                    plan.planId(),
                    draft.placement(),
                    draft.recurrenceRule(),
                    draft.recurrenceUntil(),
                    "calendar-draft:" + confirmationHash + ":recurrence");
        }
        Instant now = clock.instant();
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET status = 'MATERIALIZED', revision = revision + 1,
                    confirmation_hash = ?, materialized_plan_id = ?,
                    updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND status = 'PENDING' AND revision = ?
                """,
                confirmationHash,
                plan.planId(),
                Timestamp.from(now),
                draftId,
                context.workspaceId(),
                context.actorId(),
                expectedRevision);
        if (changed != 1) throw stale();
        return view(loadForUpdate(context, draftId));
    }

    public DraftView discard(UUID draftId, long expectedRevision) {
        if (draftId == null || expectedRevision < 1) {
            throw new IllegalArgumentException(
                    "Draft identity and positive revision are required");
        }
        WorkspaceContext context = tenantContext();
        StoredDraft draft = loadForUpdate(context, draftId);
        if (draft.status() == Status.DISCARDED
                && draft.revision() == expectedRevision + 1) {
            return view(draft);
        }
        requirePendingRevision(draft, expectedRevision);
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET status = 'DISCARDED', revision = revision + 1,
                    updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND status = 'PENDING' AND revision = ?
                """,
                Timestamp.from(clock.instant()),
                draftId,
                context.workspaceId(),
                context.actorId(),
                expectedRevision);
        if (changed != 1) throw stale();
        return view(loadForUpdate(context, draftId));
    }

    @Transactional(readOnly = true)
    public DraftView get(UUID draftId) {
        return view(load(tenantContext(), draftId, false));
    }

    @Transactional(readOnly = true)
    public boolean isAvailableForFocus(UUID draftId) {
        if (draftId == null) return false;
        try {
            StoredDraft draft = load(tenantContext(), draftId, false);
            return draft.status() == Status.PENDING
                    && draft.expiresAt().isAfter(clock.instant());
        } catch (NotFoundException exception) {
            return false;
        }
    }

    private StoredDraft findByRequestHash(
            WorkspaceContext context, String requestHash) {
        List<StoredDraft> rows = jdbc.query(
                select() + """
                 WHERE workspace_id = ? AND created_by_user_id = ?
                   AND request_hash = ?
                """,
                (row, ignored) -> stored(row),
                context.workspaceId(),
                context.actorId(),
                requestHash);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private StoredDraft loadForUpdate(WorkspaceContext context, UUID id) {
        return load(context, id, true);
    }

    private StoredDraft load(
            WorkspaceContext context, UUID id, boolean forUpdate) {
        List<StoredDraft> rows = jdbc.query(
                select() + """
                 WHERE id = ? AND workspace_id = ?
                   AND created_by_user_id = ?
                   AND channel = ?
                   AND conversation_scope_digest = ?
                """ + (forUpdate ? " FOR UPDATE" : ""),
                (row, ignored) -> stored(row),
                id,
                context.workspaceId(),
                context.actorId(),
                context.channel().name(),
                scopes.current(context).digest());
        if (rows.isEmpty()) {
            throw new NotFoundException("Calendar intent draft", "requested draft");
        }
        return rows.getFirst();
    }

    private static String select() {
        return """
                SELECT id, channel, conversation_scope_digest,
                    scope_key_version, title, placement_kind,
                    timed_start, timed_end, zone_id,
                    all_day_start, all_day_end_exclusive,
                    category, location_label, latitude, longitude,
                    recurrence_rule, recurrence_until,
                    status, revision, request_hash,
                    materialized_plan_id, expires_at,
                    route_preflight_status, route_preflight_hash
                FROM calendar_intent_draft
                """;
    }

    private static StoredDraft stored(ResultSet row) throws SQLException {
        CalendarPlacement placement = switch (row.getString("placement_kind")) {
            case "TIMED_INTERVAL" -> CalendarPlacement.interval(
                    row.getTimestamp("timed_start").toInstant(),
                    row.getTimestamp("timed_end").toInstant(),
                    ZoneId.of(row.getString("zone_id")));
            case "TIMED_POINT" -> CalendarPlacement.point(
                    row.getTimestamp("timed_start").toInstant(),
                    ZoneId.of(row.getString("zone_id")));
            case "ALL_DAY" -> CalendarPlacement.allDay(
                    row.getObject("all_day_start", LocalDate.class),
                    row.getObject("all_day_end_exclusive", LocalDate.class));
            default -> throw new IllegalStateException("Unsupported stored placement");
        };
        CalendarLocation location = row.getString("location_label") == null
                ? null
                : new CalendarLocation(
                        row.getString("location_label"),
                        row.getDouble("latitude"),
                        row.getDouble("longitude"));
        return new StoredDraft(
                row.getObject("id", UUID.class),
                row.getString("channel"),
                row.getString("conversation_scope_digest"),
                row.getInt("scope_key_version"),
                row.getString("title"),
                placement,
                row.getString("category"),
                location,
                row.getString("recurrence_rule"),
                row.getObject("recurrence_until", LocalDate.class),
                Status.valueOf(row.getString("status")),
                row.getLong("revision"),
                row.getString("request_hash"),
                row.getObject("materialized_plan_id", UUID.class),
                row.getTimestamp("expires_at").toInstant(),
                row.getString("route_preflight_status"),
                row.getString("route_preflight_hash"));
    }

    private CalendarLocation resolveLocation(String placeName) {
        if (placeName == null || placeName.isBlank()) return null;
        Place place = places.resolve(placeName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "The requested place is not confirmed"));
        return new CalendarLocation(
                place.getName(), place.getLatitude(), place.getLongitude());
    }

    private static CalendarPlacement correctedPlacement(
            CalendarPlacement current, IntentCommand correction) {
        ZonedDateTime parsedStart = parseOptional(correction.startAt());
        ZonedDateTime parsedEnd = parseOptional(correction.endAt());
        if (parsedStart == null && parsedEnd == null) return current;
        ZoneId zone = parsedStart != null
                ? parsedStart.getZone()
                : parsedEnd != null ? parsedEnd.getZone() : ZoneId.of("Asia/Taipei");
        if (current instanceof CalendarPlacement.TimedInterval interval) {
            Instant start = parsedStart == null ? interval.start() : parsedStart.toInstant();
            Instant end = parsedEnd == null
                    ? parsedStart == null
                            ? interval.end()
                            : start.plus(Duration.between(interval.start(), interval.end()))
                    : parsedEnd.toInstant();
            return CalendarPlacement.interval(start, end, zone);
        }
        if (current instanceof CalendarPlacement.TimedPoint point) {
            Instant start = parsedStart == null ? point.time() : parsedStart.toInstant();
            return parsedEnd == null
                    ? CalendarPlacement.point(start, zone)
                    : CalendarPlacement.interval(start, parsedEnd.toInstant(), zone);
        }
        if (parsedStart == null) {
            throw new IllegalArgumentException(
                    "An all-day calendar proposal needs a start time before an end time");
        }
        return parsedEnd == null
                ? CalendarPlacement.point(parsedStart.toInstant(), zone)
                : CalendarPlacement.interval(
                        parsedStart.toInstant(), parsedEnd.toInstant(), zone);
    }

    private static ZonedDateTime parseOptional(String value) {
        return value == null || value.isBlank() ? null : ZonedDateTime.parse(value);
    }

    private static List<CalendarNodeDraft> startNode(StoredDraft draft) {
        Instant start;
        Instant end = null;
        if (draft.placement() instanceof CalendarPlacement.TimedInterval interval) {
            start = interval.start();
            end = interval.end();
        } else if (draft.placement() instanceof CalendarPlacement.TimedPoint point) {
            start = point.time();
        } else {
            return List.of();
        }
        CalendarTimeNode startNode = CalendarTimeNode.absolute(
                "start", draft.title() + "開始", start);
        CalendarNodeDraft startDraft = draft.location() == null
                ? CalendarNodeDraft.of(startNode)
                : CalendarNodeDraft.at(startNode, draft.location());
        if (end == null) return List.of(startDraft);
        CalendarTimeNode endNode = CalendarTimeNode.absolute(
                "end", draft.title() + "結束", end);
        CalendarNodeDraft endDraft = draft.location() == null
                ? CalendarNodeDraft.of(endNode)
                : CalendarNodeDraft.at(endNode, draft.location());
        return List.of(startDraft, endDraft);
    }

    private void requirePendingRevision(StoredDraft draft, long expectedRevision) {
        if (draft.status() != Status.PENDING) {
            throw new BusinessException(
                    "CALENDAR_DRAFT_NOT_PENDING",
                    "The calendar draft is no longer pending");
        }
        if (draft.revision() != expectedRevision) throw stale();
        if (!draft.expiresAt().isAfter(clock.instant())) {
            throw new BusinessException(
                    "CALENDAR_DRAFT_EXPIRED", "The calendar draft expired");
        }
    }

    private static BusinessException stale() {
        return new BusinessException(
                "STALE_CALENDAR_DRAFT",
                "The calendar draft changed before this action");
    }

    private static void requireTitle(String title) {
        if (title == null || title.isBlank() || title.strip().length() > 200) {
            throw new IllegalArgumentException("Calendar draft title is required");
        }
    }

    private static String optional(String value) {
        String normalized = value == null || value.isBlank() ? null : value.strip();
        requireCategory(normalized);
        return normalized;
    }

    private static void requireCategory(String category) {
        if (category != null && category.length() > 100) {
            throw new IllegalArgumentException("Calendar draft category is too long");
        }
    }

    private static WorkspaceContext tenantContext() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("Calendar intent draft requires tenant scope");
        }
        return context;
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static DraftView view(StoredDraft draft) {
        return new DraftView(
                draft.id(),
                draft.title(),
                draft.placement(),
                draft.category(),
                draft.location(),
                draft.recurrenceRule(),
                draft.recurrenceUntil(),
                draft.status(),
                draft.revision(),
                draft.materializedPlanId());
    }

    public enum Status {
        PENDING,
        MATERIALIZED,
        DISCARDED
    }

    public record DraftView(
            UUID id,
            String title,
            CalendarPlacement placement,
            String category,
            CalendarLocation location,
            String recurrenceRule,
            LocalDate recurrenceUntil,
            Status status,
            long revision,
            UUID materializedPlanId) {}

    public record ConfirmationResult(
            DraftView draft,
            boolean awaitingRouteConfirmation,
            CalendarIntentDraftPreflightService.PreflightResult preflight) {

        static ConfirmationResult awaiting(
                DraftView draft,
                CalendarIntentDraftPreflightService.PreflightResult preflight) {
            return new ConfirmationResult(draft, true, preflight);
        }

        static ConfirmationResult completed(DraftView draft) {
            return new ConfirmationResult(
                    draft,
                    false,
                    CalendarIntentDraftPreflightService.PreflightResult.clear());
        }
    }

    private record StoredDraft(
            UUID id,
            String channel,
            String scopeDigest,
            int scopeKeyVersion,
            String title,
            CalendarPlacement placement,
            String category,
            CalendarLocation location,
            String recurrenceRule,
            LocalDate recurrenceUntil,
            Status status,
            long revision,
            String requestHash,
            UUID materializedPlanId,
            Instant expiresAt,
            String routePreflightStatus,
            String routePreflightHash) {}

    private record PlacementColumns(
            Timestamp timedStart,
            Timestamp timedEnd,
            String zoneId,
            LocalDate allDayStart,
            LocalDate allDayEndExclusive) {

        static PlacementColumns from(CalendarPlacement placement) {
            if (placement instanceof CalendarPlacement.TimedInterval interval) {
                return new PlacementColumns(
                        Timestamp.from(interval.start()),
                        Timestamp.from(interval.end()),
                        interval.zoneId().getId(),
                        null,
                        null);
            }
            if (placement instanceof CalendarPlacement.TimedPoint point) {
                return new PlacementColumns(
                        Timestamp.from(point.time()),
                        null,
                        point.zoneId().getId(),
                        null,
                        null);
            }
            CalendarPlacement.AllDay allDay =
                    (CalendarPlacement.AllDay) placement;
            return new PlacementColumns(
                    null,
                    null,
                    null,
                    allDay.start(),
                    allDay.endExclusive());
        }
    }
}
