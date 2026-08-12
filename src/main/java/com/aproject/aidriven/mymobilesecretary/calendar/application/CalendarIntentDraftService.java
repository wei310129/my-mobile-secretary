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
import com.aproject.aidriven.mymobilesecretary.geo.application.SystemPlaceCatalog;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningRequest;
import com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningResult;
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
    private SystemPlaceCatalog systemPlaceCatalog;

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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSystemPlaceCatalog(SystemPlaceCatalog systemPlaceCatalog) {
        this.systemPlaceCatalog = systemPlaceCatalog;
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
        RouteJourneyKind routeJourneyKind = command.type() == IntentCommand.Type.PLAN_ROUTE_ITINERARY
                ? RouteJourneyKind.resolve(command, placement)
                : null;
        CalendarLocation location = resolveLocation(command.placeName());
        CalendarLocation transportOrigin = command.type() == IntentCommand.Type.PLAN_ROUTE_ITINERARY
                ? resolveRouteOrigin(command.safeOptions().fromPlaceName()) : null;
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
                    category, location_label, latitude, longitude, location_source,
                    transport_origin_label, transport_origin_latitude,
                    transport_origin_longitude, transport_origin_source, transport_offer_status,
                    route_journey_kind,
                    recurrence_rule, recurrence_until,
                    status, revision, request_hash,
                    confirmation_hash, materialized_plan_id,
                    expires_at, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NONE', ?, ?, ?,
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
                location == null ? null : EndpointSource.EXPLICIT_CURRENT_TURN.name(),
                transportOrigin == null ? null : transportOrigin.label(),
                transportOrigin == null ? null : transportOrigin.latitude(),
                transportOrigin == null ? null : transportOrigin.longitude(),
                transportOrigin == null ? null : EndpointSource.EXPLICIT_CURRENT_TURN.name(),
                routeJourneyKind == null ? null : routeJourneyKind.name(),
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

    public DraftView setTransportOrigin(
            UUID draftId,
            long expectedRevision,
            com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation origin) {
        return setTransportOrigin(
                draftId, expectedRevision, origin, EndpointSource.CONFIRMED_CONTEXT);
    }

    public DraftView setTransportOrigin(
            UUID draftId,
            long expectedRevision,
            com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation origin,
            EndpointSource source) {
        if (draftId == null
                || expectedRevision < 1
                || origin == null
                || origin.label() == null
                || origin.label().isBlank()
                || source == null
                || source == EndpointSource.LEGACY_UNSPECIFIED) {
            throw new IllegalArgumentException("Confirmed transport origin is required");
        }
        WorkspaceContext context = tenantContext();
        StoredDraft draft = loadForUpdate(context, draftId);
        requirePendingRevision(draft, expectedRevision);
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET transport_origin_label = ?, transport_origin_latitude = ?,
                    transport_origin_longitude = ?, transport_origin_source = ?,
                    revision = revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'PENDING' AND revision = ?
                """,
                origin.label().strip(),
                origin.latitude(),
                origin.longitude(),
                source.name(),
                Timestamp.from(clock.instant()),
                draftId,
                context.workspaceId(),
                context.actorId(),
                expectedRevision);
        if (changed != 1) throw stale();
        return view(loadForUpdate(context, draftId));
    }

    public DraftView setPrematureMaterializedRouteOrigin(
            UUID draftId,
            long expectedRevision,
            CalendarLocation origin,
            EndpointSource source) {
        if (draftId == null
                || expectedRevision < 1
                || origin == null
                || origin.label() == null
                || origin.label().isBlank()
                || source == null
                || source == EndpointSource.LEGACY_UNSPECIFIED) {
            throw new IllegalArgumentException("Confirmed transport origin is required");
        }
        WorkspaceContext context = tenantContext();
        StoredDraft draft = loadForUpdate(context, draftId);
        if (draft.status() != Status.MATERIALIZED
                || draft.revision() != expectedRevision
                || draft.materializedPlanId() == null
                || draft.routeJourneyKind() != RouteJourneyKind.STANDALONE_TRIP
                || !(draft.placement() instanceof CalendarPlacement.TimedPoint)
                || draft.transportOrigin() != null
                || draft.routeProviderStatus() != RouteProviderStatus.NOT_REQUESTED) {
            throw new BusinessException(
                    "CALENDAR_ROUTE_CHANGED",
                    "The premature route changed before its origin was confirmed");
        }
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET transport_origin_label = ?, transport_origin_latitude = ?,
                    transport_origin_longitude = ?, transport_origin_source = ?,
                    revision = revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'MATERIALIZED' AND revision = ?
                  AND route_provider_status = 'NOT_REQUESTED'
                  AND transport_origin_label IS NULL
                """,
                origin.label().strip(),
                origin.latitude(),
                origin.longitude(),
                source.name(),
                Timestamp.from(clock.instant()),
                draftId,
                context.workspaceId(),
                context.actorId(),
                expectedRevision);
        if (changed != 1) throw stale();
        return view(loadForUpdate(context, draftId));
    }

    public DraftView prepareStandaloneRouteRequest(
            UUID draftId,
            long expectedRevision,
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningRequest
                            .TravelMode
                    mode,
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningRequest
                            .TimeRole
                    timeRole) {
        if (draftId == null || expectedRevision < 1 || timeRole == null) {
            throw new IllegalArgumentException("Standalone route request context is incomplete");
        }
        WorkspaceContext context = tenantContext();
        StoredDraft draft = loadForUpdate(context, draftId);
        requirePendingRevision(draft, expectedRevision);
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET transport_mode = ?, route_time_role = ?,
                    revision = revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'PENDING' AND revision = ?
                  AND route_provider_status = 'NOT_REQUESTED'
                """,
                mode == null ? null : mode.name(),
                timeRole.name(),
                Timestamp.from(clock.instant()),
                draftId,
                context.workspaceId(),
                context.actorId(),
                expectedRevision);
        if (changed != 1) throw stale();
        return view(loadForUpdate(context, draftId));
    }

    public DraftView prepareStandaloneRoute(
            UUID draftId,
            long expectedRevision,
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningRequest
                            .TravelMode
                    mode,
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningRequest
                            .TimeRole
                    timeRole,
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningResult
                            .RouteOption
                    option) {
        if (draftId == null || expectedRevision < 1 || mode == null || timeRole == null
                || option == null
                || option.mode() != mode) {
            throw new IllegalArgumentException("Standalone route evidence is incomplete");
        }
        WorkspaceContext context = tenantContext();
        StoredDraft draft = loadForUpdate(context, draftId);
        requirePendingRevision(draft, expectedRevision);
        if (draft.transportOrigin() == null || draft.location() == null) {
            throw new IllegalArgumentException("Standalone route endpoints are not confirmed");
        }
        ZoneId zone = switch (draft.placement()) {
            case CalendarPlacement.TimedPoint point -> point.zoneId();
            case CalendarPlacement.TimedInterval interval -> interval.zoneId();
            case CalendarPlacement.AllDay ignored -> throw new IllegalArgumentException(
                    "Standalone route requires a timed placement");
        };
        CalendarPlacement placement = CalendarPlacement.interval(
                option.departAt(), option.arriveAt(), zone);
        PlacementColumns columns = PlacementColumns.from(placement);
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET placement_kind = ?, timed_start = ?, timed_end = ?, zone_id = ?,
                    all_day_start = NULL, all_day_end_exclusive = NULL,
                    transport_mode = ?, route_provider_status = 'AVAILABLE',
                    route_time_role = ?, route_provider = ?,
                    route_evidence_retrieved_at = ?, route_preflight_status = NULL,
                    route_preflight_hash = NULL, revision = revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'PENDING' AND revision = ?
                """,
                placement.kind().name(),
                columns.timedStart(),
                columns.timedEnd(),
                columns.zoneId(),
                mode.name(),
                timeRole.name(),
                option.provider().name(),
                Timestamp.from(option.retrievedAt()),
                Timestamp.from(clock.instant()),
                draftId,
                context.workspaceId(),
                context.actorId(),
                expectedRevision);
        if (changed != 1) throw stale();
        return view(loadForUpdate(context, draftId));
    }

    public ConfirmationResult completePrematureMaterializedStandaloneRoute(
            UUID draftId,
            long expectedRevision,
            RoutePlanningRequest.TravelMode mode,
            RoutePlanningRequest.TimeRole timeRole,
            RoutePlanningResult.RouteOption option) {
        if (draftId == null
                || expectedRevision < 1
                || mode == null
                || timeRole == null
                || option == null
                || option.mode() != mode) {
            throw new IllegalArgumentException(
                    "Standalone route completion evidence is incomplete");
        }
        WorkspaceContext context = tenantContext();
        StoredDraft draft = loadForUpdate(context, draftId);
        if (draft.status() != Status.MATERIALIZED
                || draft.revision() != expectedRevision
                || draft.materializedPlanId() == null
                || draft.routeJourneyKind() != RouteJourneyKind.STANDALONE_TRIP
                || !(draft.placement() instanceof CalendarPlacement.TimedPoint point)
                || draft.transportOrigin() == null
                || draft.location() == null
                || (draft.routeProviderStatus() != RouteProviderStatus.NOT_REQUESTED
                        && draft.routeProviderStatus() != RouteProviderStatus.RETAINED)) {
            throw new BusinessException(
                    "CALENDAR_ROUTE_CHANGED",
                    "The premature route changed before provider completion");
        }
        CalendarPlacement.TimedInterval placement = (CalendarPlacement.TimedInterval)
                CalendarPlacement.interval(option.departAt(), option.arriveAt(), point.zoneId());
        calendars.completePrematureStandaloneRouteById(
                draft.materializedPlanId(),
                placement,
                draft.transportOrigin(),
                draft.location());
        PlacementColumns columns = PlacementColumns.from(placement);
        Instant now = clock.instant();
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET placement_kind = ?, timed_start = ?, timed_end = ?, zone_id = ?,
                    all_day_start = NULL, all_day_end_exclusive = NULL,
                    transport_mode = ?, route_provider_status = 'AVAILABLE',
                    route_time_role = ?, route_provider = ?,
                    route_evidence_retrieved_at = ?, route_preflight_status = NULL,
                    route_preflight_hash = NULL, revision = revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'MATERIALIZED' AND revision = ?
                  AND route_provider_status IN ('NOT_REQUESTED', 'RETAINED')
                """,
                placement.kind().name(),
                columns.timedStart(),
                columns.timedEnd(),
                columns.zoneId(),
                mode.name(),
                timeRole.name(),
                option.provider().name(),
                Timestamp.from(option.retrievedAt()),
                Timestamp.from(now),
                draftId,
                context.workspaceId(),
                context.actorId(),
                expectedRevision);
        if (changed != 1) throw stale();
        DraftView completed = view(loadForUpdate(context, draftId));
        var preflight = preflights.assess(completed);
        if (preflight.requiresConfirmation()) {
            int recorded = jdbc.update(
                    """
                    UPDATE calendar_intent_draft
                    SET route_preflight_status = ?, route_preflight_hash = ?,
                        revision = revision + 1, updated_at = ?
                    WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                      AND status = 'MATERIALIZED' AND revision = ?
                    """,
                    preflight.status(),
                    preflight.fingerprint(),
                    Timestamp.from(clock.instant()),
                    draftId,
                    context.workspaceId(),
                    context.actorId(),
                    completed.revision());
            if (recorded != 1) throw stale();
            completed = view(loadForUpdate(context, draftId));
        }
        if ("ROUTE_RISK".equals(preflight.status())) {
            routeRisks.confirmCurrentRisksForPlan(completed.materializedPlanId());
        }
        routeRisks.synchronizeCurrent();
        return preflight.overlaps().isEmpty()
                ? ConfirmationResult.completed(completed, preflight)
                : ConfirmationResult.awaiting(completed, preflight);
    }

    public DraftView rescheduleMaterializedStandaloneRoute(
            UUID draftId,
            long expectedRevision,
            RoutePlanningResult.RouteOption option) {
        if (draftId == null || expectedRevision < 1 || option == null) {
            throw new IllegalArgumentException("Safe route adjustment evidence is incomplete");
        }
        WorkspaceContext context = tenantContext();
        StoredDraft draft = loadForUpdate(context, draftId);
        if (draft.status() != Status.MATERIALIZED
                || draft.revision() != expectedRevision
                || draft.materializedPlanId() == null
                || draft.routeProviderStatus() != RouteProviderStatus.AVAILABLE
                || draft.transportMode() == null
                || option.mode() != RoutePlanningRequest.TravelMode.valueOf(
                        draft.transportMode())) {
            throw new BusinessException(
                    "CALENDAR_ROUTE_CHANGED",
                    "The route changed before its safe-time adjustment");
        }
        ZoneId zone = switch (draft.placement()) {
            case CalendarPlacement.TimedInterval interval -> interval.zoneId();
            default -> throw new IllegalStateException(
                    "A materialized standalone route requires an interval");
        };
        CalendarPlacement.TimedInterval placement = (CalendarPlacement.TimedInterval)
                CalendarPlacement.interval(option.departAt(), option.arriveAt(), zone);
        calendars.rescheduleFreshStandaloneRouteById(
                draft.materializedPlanId(), placement);
        PlacementColumns columns = PlacementColumns.from(placement);
        Instant now = clock.instant();
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET placement_kind = ?, timed_start = ?, timed_end = ?, zone_id = ?,
                    route_provider_status = 'AVAILABLE', route_provider = ?,
                    route_evidence_retrieved_at = ?, route_preflight_status = NULL,
                    route_preflight_hash = NULL, revision = revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'MATERIALIZED' AND revision = ?
                """,
                placement.kind().name(),
                columns.timedStart(),
                columns.timedEnd(),
                columns.zoneId(),
                option.provider().name(),
                Timestamp.from(option.retrievedAt()),
                Timestamp.from(now),
                draftId,
                context.workspaceId(),
                context.actorId(),
                expectedRevision);
        if (changed != 1) throw stale();
        routeRisks.synchronizeCurrent();
        return view(loadForUpdate(context, draftId));
    }

    public DraftView markStandaloneRouteUnavailable(
            UUID draftId,
            long expectedRevision,
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningRequest
                            .TravelMode
                    mode,
            com.aproject.aidriven.mymobilesecretary.planner.application.RoutePlanningRequest
                            .TimeRole
                    timeRole) {
        WorkspaceContext context = tenantContext();
        StoredDraft draft = loadForUpdate(context, draftId);
        boolean prematureMaterialized = isPrematureMaterializedStandaloneRoute(
                draft, expectedRevision);
        if (!prematureMaterialized) requirePendingRevision(draft, expectedRevision);
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET transport_mode = ?, route_provider_status = 'UNAVAILABLE',
                    route_time_role = ?, route_provider = NULL,
                    route_evidence_retrieved_at = NULL,
                    revision = revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = ? AND revision = ?
                """,
                mode.name(),
                timeRole.name(),
                Timestamp.from(clock.instant()),
                draftId,
                context.workspaceId(),
                context.actorId(),
                prematureMaterialized ? Status.MATERIALIZED.name() : Status.PENDING.name(),
                expectedRevision);
        if (changed != 1) throw stale();
        return view(loadForUpdate(context, draftId));
    }

    public DraftView retainUnavailableRoute(UUID draftId, long expectedRevision) {
        WorkspaceContext context = tenantContext();
        StoredDraft draft = loadForUpdate(context, draftId);
        boolean prematureMaterialized = draft.status() == Status.MATERIALIZED
                && draft.revision() == expectedRevision
                && draft.materializedPlanId() != null
                && draft.routeJourneyKind() == RouteJourneyKind.STANDALONE_TRIP;
        if (!prematureMaterialized) requirePendingRevision(draft, expectedRevision);
        if (draft.routeProviderStatus() != RouteProviderStatus.UNAVAILABLE) {
            throw new IllegalStateException("Route draft is not awaiting retention");
        }
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET route_provider_status = 'RETAINED', revision = revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = ? AND revision = ?
                """,
                Timestamp.from(clock.instant()),
                draftId,
                context.workspaceId(),
                context.actorId(),
                prematureMaterialized ? Status.MATERIALIZED.name() : Status.PENDING.name(),
                expectedRevision);
        if (changed != 1) throw stale();
        return view(loadForUpdate(context, draftId));
    }

    public DraftView offerTransportForCurrentRequest() {
        WorkspaceContext context = tenantContext();
        String requestHash = hash(
                "calendar-intent-draft-v1|" + context.workspaceId() + "|"
                        + context.actorId() + "|" + scopes.current(context).digest() + "|"
                        + RequestCorrelationContext.currentId());
        StoredDraft current = findByRequestHash(context, requestHash);
        if (current == null || current.status() != Status.MATERIALIZED
                || current.transportOrigin() == null) {
            throw new IllegalStateException("Materialized route draft is unavailable");
        }
        return offerTransport(current.id(), current.revision());
    }

    public DraftView offerTransport(UUID draftId, long expectedRevision) {
        WorkspaceContext context = tenantContext();
        StoredDraft current = loadForUpdate(context, draftId);
        if (current.status() != Status.MATERIALIZED || current.transportOrigin() == null) {
            throw new IllegalStateException("Materialized route draft is unavailable");
        }
        if (current.transportOfferStatus() == TransportOfferStatus.OFFERED) return view(current);
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET transport_offer_status = 'OFFERED', revision = revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND revision = ? AND transport_offer_status = 'NONE'
                """,
                Timestamp.from(clock.instant()), current.id(), context.workspaceId(),
                context.actorId(), expectedRevision);
        if (changed != 1) throw stale();
        return view(loadForUpdate(context, current.id()));
    }

    public java.util.Optional<DraftView> currentTransportConversation() {
        WorkspaceContext context = tenantContext();
        var scope = scopes.current(context);
        List<StoredDraft> rows = jdbc.query(
                select() + """
                 WHERE workspace_id = ? AND created_by_user_id = ?
                   AND channel = ? AND conversation_scope_digest = ?
                   AND (
                       (status = 'MATERIALIZED'
                           AND (transport_offer_status IN ('OFFERED', 'ACCEPTED')
                                OR route_provider_status IN ('UNAVAILABLE', 'RETAINED')))
                       OR (status = 'PENDING'
                           AND (
                               route_provider_status IN ('UNAVAILABLE', 'RETAINED')
                               OR (route_provider_status = 'NOT_REQUESTED'
                                   AND route_time_role IS NOT NULL)))
                   )
                 ORDER BY updated_at DESC
                 LIMIT 2
                 FOR UPDATE
                 """,
                (row, ignored) -> stored(row), context.workspaceId(), context.actorId(),
                context.channel().name(), scope.digest());
        return rows.size() == 1 ? java.util.Optional.of(view(rows.getFirst()))
                : java.util.Optional.empty();
    }

    @Transactional(readOnly = true)
    public boolean hasDurableCompletedStandaloneRoute(UUID draftId) {
        if (draftId == null) return false;
        WorkspaceContext context = tenantContext();
        StoredDraft draft = load(context, draftId, false);
        if (draft.status() != Status.MATERIALIZED
                || draft.materializedPlanId() == null
                || draft.routeJourneyKind() != RouteJourneyKind.STANDALONE_TRIP
                || !(draft.placement() instanceof CalendarPlacement.TimedInterval interval)
                || draft.transportOrigin() == null
                || draft.location() == null
                || draft.transportOriginSource() == null
                || draft.locationSource() == null
                || draft.transportMode() == null
                || draft.routeTimeRole() == null
                || draft.routeProviderStatus() != RouteProviderStatus.AVAILABLE
                || draft.routeProvider() == null
                || draft.routeEvidenceRetrievedAt() == null) {
            return false;
        }
        Long matchingPlan = jdbc.queryForObject(
                """
                SELECT count(*) FROM calendar_plan
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'ACTIVE' AND placement_kind = 'TIMED_INTERVAL'
                  AND timed_start = ? AND timed_end = ? AND zone_id = ?
                """,
                Long.class,
                draft.materializedPlanId(),
                context.workspaceId(),
                context.actorId(),
                Timestamp.from(interval.start()),
                Timestamp.from(interval.end()),
                interval.zoneId().getId());
        if (matchingPlan == null || matchingPlan != 1L) return false;
        Long matchingNodes = jdbc.queryForObject(
                """
                SELECT count(*) FROM calendar_time_node
                WHERE plan_id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND cancellation_status = 'ACTIVE'
                  AND (
                    (node_key = 'start' AND resolved_time = ?
                      AND location_label = ? AND latitude = ? AND longitude = ?)
                    OR
                    (node_key = 'end' AND resolved_time = ?
                      AND location_label = ? AND latitude = ? AND longitude = ?)
                  )
                """,
                Long.class,
                draft.materializedPlanId(),
                context.workspaceId(),
                context.actorId(),
                Timestamp.from(interval.start()),
                draft.transportOrigin().label(),
                draft.transportOrigin().latitude(),
                draft.transportOrigin().longitude(),
                Timestamp.from(interval.end()),
                draft.location().label(),
                draft.location().latitude(),
                draft.location().longitude());
        return matchingNodes != null && matchingNodes == 2L;
    }

    public DraftView answerTransportOffer(UUID draftId, long expectedRevision, boolean accepted) {
        WorkspaceContext context = tenantContext();
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET transport_offer_status = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'MATERIALIZED' AND revision = ?
                  AND transport_offer_status = 'OFFERED'
                """,
                accepted ? "ACCEPTED" : "DECLINED", Timestamp.from(clock.instant()),
                draftId, context.workspaceId(), context.actorId(), expectedRevision);
        if (changed != 1) throw stale();
        return view(loadForUpdate(context, draftId));
    }

    public DraftView setActivityAdjustability(
            UUID draftId, long expectedRevision,
            com.aproject.aidriven.mymobilesecretary.calendar.domain.Adjustability value) {
        WorkspaceContext context = tenantContext();
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET activity_adjustability = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'MATERIALIZED' AND revision = ?
                  AND transport_offer_status = 'ACCEPTED'
                  AND activity_adjustability IS NULL
                """,
                value.name(), Timestamp.from(clock.instant()), draftId,
                context.workspaceId(), context.actorId(), expectedRevision);
        if (changed != 1) throw stale();
        return view(loadForUpdate(context, draftId));
    }

    public DraftView materializeTransportNode(
            UUID draftId, long expectedRevision,
            RoutePlanningRequest.TravelMode mode,
            RoutePlanningResult.RouteOption option) {
        WorkspaceContext context = tenantContext();
        StoredDraft current = loadForUpdate(context, draftId);
        if (current.revision() != expectedRevision
                || current.transportOfferStatus() != TransportOfferStatus.ACCEPTED
                || current.activityAdjustability() == null
                || current.materializedPlanId() == null) {
            throw stale();
        }
        UUID nodeId = calendars.addTransportDepartureNode(
                current.materializedPlanId(), current.transportOrigin(), option.departAt()).getId();
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET transport_offer_status = 'PLANNED', transport_mode = ?,
                    transport_node_id = ?, revision = revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND revision = ? AND transport_offer_status = 'ACCEPTED'
                """,
                mode.name(), nodeId, Timestamp.from(clock.instant()), draftId,
                context.workspaceId(), context.actorId(), expectedRevision);
        if (changed != 1) throw stale();
        return view(loadForUpdate(context, draftId));
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

    public DraftView reviseLocation(
            UUID draftId, long expectedRevision, CalendarLocation location) {
        if (draftId == null || expectedRevision < 1 || location == null) {
            throw new IllegalArgumentException(
                    "Draft identity, revision and location are required");
        }
        WorkspaceContext context = tenantContext();
        StoredDraft current = loadForUpdate(context, draftId);
        requirePendingRevision(current, expectedRevision);
        Instant now = clock.instant();
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET location_label = ?, latitude = ?, longitude = ?,
                    route_preflight_status = NULL, route_preflight_hash = NULL,
                    revision = revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'PENDING' AND revision = ?
                """,
                location.label(), location.latitude(), location.longitude(),
                Timestamp.from(now), draftId, context.workspaceId(), context.actorId(),
                expectedRevision);
        if (changed != 1) throw stale();
        return view(loadForUpdate(context, draftId));
    }

    public ConfirmationResult confirm(UUID draftId, long expectedRevision) {
        return confirm(draftId, expectedRevision, false);
    }

    public ConfirmationResult confirmStandaloneRoute(UUID draftId, long expectedRevision) {
        return confirm(draftId, expectedRevision, true);
    }

    private ConfirmationResult confirm(
            UUID draftId, long expectedRevision, boolean allowInsufficientAdjacentEvidence) {
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
        if (draft.routeProviderStatus() == RouteProviderStatus.UNAVAILABLE
                || draft.routeProviderStatus() == RouteProviderStatus.RETAINED) {
            throw new IllegalStateException(
                    "Route draft without provider evidence cannot be materialized");
        }
        var preflight = preflights.assess(view(draft));
        boolean standaloneMayMaterialize = allowInsufficientAdjacentEvidence
                && preflight.overlaps().isEmpty();
        boolean blocks = preflight.requiresConfirmation() && !standaloneMayMaterialize;
        if (blocks
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
        return ConfirmationResult.completed(materialized, preflight);
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

    public DraftView closePrematureMaterializedRouteRecovery(
            UUID draftId, long expectedRevision) {
        WorkspaceContext context = tenantContext();
        StoredDraft draft = loadForUpdate(context, draftId);
        if (draft.status() != Status.MATERIALIZED
                || draft.revision() != expectedRevision
                || draft.materializedPlanId() == null
                || draft.routeJourneyKind() != RouteJourneyKind.STANDALONE_TRIP
                || draft.routeProviderStatus() != RouteProviderStatus.UNAVAILABLE) {
            throw new BusinessException(
                    "CALENDAR_ROUTE_CHANGED",
                    "The route recovery changed before it was closed");
        }
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET status = 'DISCARDED', revision = revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'MATERIALIZED' AND revision = ?
                  AND route_provider_status = 'UNAVAILABLE'
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
            return (draft.status() == Status.PENDING || draft.status() == Status.MATERIALIZED)
                    && draft.expiresAt().isAfter(clock.instant());
        } catch (NotFoundException exception) {
            return false;
        }
    }

    /** Exact actor/scope lookup used by the upper conversation lifecycle boundary. */
    @Transactional(readOnly = true)
    public java.util.Optional<DraftView> findAvailableForLifecycle(UUID draftId) {
        if (draftId == null) return java.util.Optional.empty();
        try {
            StoredDraft draft = load(tenantContext(), draftId, false);
            if ((draft.status() != Status.PENDING && draft.status() != Status.MATERIALIZED)
                    || !draft.expiresAt().isAfter(clock.instant())) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(view(draft));
        } catch (NotFoundException exception) {
            return java.util.Optional.empty();
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
                    category, location_label, latitude, longitude, location_source,
                    transport_origin_label, transport_origin_latitude,
                    transport_origin_longitude, transport_origin_source, transport_offer_status,
                    activity_adjustability, transport_mode,
                    route_provider_status, route_time_role, route_provider,
                    route_evidence_retrieved_at,
                    route_journey_kind,
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
        CalendarLocation transportOrigin = row.getString("transport_origin_label") == null
                ? null
                : new CalendarLocation(
                        row.getString("transport_origin_label"),
                        row.getDouble("transport_origin_latitude"),
                        row.getDouble("transport_origin_longitude"));
        return new StoredDraft(
                row.getObject("id", UUID.class),
                row.getString("channel"),
                row.getString("conversation_scope_digest"),
                row.getInt("scope_key_version"),
                row.getString("title"),
                placement,
                row.getString("category"),
                location,
                transportOrigin,
                row.getString("location_source") == null
                        ? null
                        : EndpointSource.valueOf(row.getString("location_source")),
                row.getString("transport_origin_source") == null
                        ? null
                        : EndpointSource.valueOf(row.getString("transport_origin_source")),
                TransportOfferStatus.valueOf(row.getString("transport_offer_status")),
                row.getString("activity_adjustability"),
                row.getString("transport_mode"),
                RouteProviderStatus.valueOf(row.getString("route_provider_status")),
                row.getString("route_time_role"),
                row.getString("route_provider"),
                row.getTimestamp("route_evidence_retrieved_at") == null
                        ? null
                        : row.getTimestamp("route_evidence_retrieved_at").toInstant(),
                row.getString("recurrence_rule"),
                row.getObject("recurrence_until", LocalDate.class),
                Status.valueOf(row.getString("status")),
                row.getLong("revision"),
                row.getString("request_hash"),
                row.getObject("materialized_plan_id", UUID.class),
                row.getTimestamp("expires_at").toInstant(),
                row.getString("route_preflight_status"),
                row.getString("route_preflight_hash"),
                row.getString("route_journey_kind") == null
                        ? null
                        : RouteJourneyKind.valueOf(row.getString("route_journey_kind")));
    }

    private CalendarLocation resolveLocation(String placeName) {
        if (placeName == null || placeName.isBlank()) return null;
        var custom = places.resolve(placeName);
        if (custom.isPresent()) {
            Place place = custom.orElseThrow();
            return new CalendarLocation(
                    place.getName(), place.getLatitude(), place.getLongitude());
        }
        if (systemPlaceCatalog != null) {
            SystemPlaceCatalog.Resolution resolution =
                    systemPlaceCatalog.resolveMention(placeName);
            if (resolution.status() == SystemPlaceCatalog.Resolution.Status.EXACT
                    || resolution.status()
                            == SystemPlaceCatalog.Resolution.Status.LOGICAL_PLACE_MULTIPOINT) {
                SystemPlaceCatalog.SystemPlace selected = resolution.selected();
                return new CalendarLocation(
                        systemPlaceCatalog.publicPointLabel(selected),
                        selected.latitude(),
                        selected.longitude());
            }
            if (resolution.status() == SystemPlaceCatalog.Resolution.Status.ENTITY_AMBIGUOUS) {
                return null;
            }
        }
        throw new IllegalArgumentException("The requested place is not confirmed");
    }

    private CalendarLocation resolveRouteOrigin(String placeName) {
        try {
            return resolveLocation(placeName);
        } catch (IllegalArgumentException exception) {
            return null;
        }
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
        CalendarLocation startLocation = draft.transportOrigin() != null
                        && draft.transportMode() != null
                        && draft.transportOfferStatus() == TransportOfferStatus.NONE
                ? draft.transportOrigin()
                : draft.location();
        CalendarNodeDraft startDraft = startLocation == null
                ? CalendarNodeDraft.of(startNode)
                : CalendarNodeDraft.at(startNode, startLocation);
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

    private static boolean isPrematureMaterializedStandaloneRoute(
            StoredDraft draft, long expectedRevision) {
        return draft.status() == Status.MATERIALIZED
                && draft.revision() == expectedRevision
                && draft.materializedPlanId() != null
                && draft.routeJourneyKind() == RouteJourneyKind.STANDALONE_TRIP
                && draft.placement() instanceof CalendarPlacement.TimedPoint
                && draft.transportOrigin() != null
                && draft.location() != null
                && draft.routeProviderStatus() != RouteProviderStatus.AVAILABLE;
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
                draft.transportOrigin(),
                draft.locationSource(),
                draft.transportOriginSource(),
                draft.transportOfferStatus(),
                draft.activityAdjustability(),
                draft.transportMode(),
                draft.routeProviderStatus(),
                draft.routeTimeRole(),
                draft.routeProvider(),
                draft.routeEvidenceRetrievedAt(),
                draft.recurrenceRule(),
                draft.recurrenceUntil(),
                draft.status(),
                draft.revision(),
                draft.materializedPlanId(),
                draft.routeJourneyKind());
    }

    public enum Status {
        PENDING,
        MATERIALIZED,
        DISCARDED
    }

    public enum TransportOfferStatus { NONE, OFFERED, ACCEPTED, DECLINED, PLANNED }

    public enum RouteProviderStatus { NOT_REQUESTED, UNAVAILABLE, RETAINED, AVAILABLE }

    public enum EndpointSource {
        EXPLICIT_CURRENT_TURN,
        CONFIRMED_HOME,
        CONFIRMED_CONTEXT,
        LEGACY_UNSPECIFIED
    }

    public record DraftView(
            UUID id,
            String title,
            CalendarPlacement placement,
            String category,
            CalendarLocation location,
            CalendarLocation transportOrigin,
            EndpointSource locationSource,
            EndpointSource transportOriginSource,
            TransportOfferStatus transportOfferStatus,
            String activityAdjustability,
            String transportMode,
            RouteProviderStatus routeProviderStatus,
            String routeTimeRole,
            String routeProvider,
            Instant routeEvidenceRetrievedAt,
            String recurrenceRule,
            LocalDate recurrenceUntil,
            Status status,
            long revision,
            UUID materializedPlanId,
            RouteJourneyKind routeJourneyKind) {

        public DraftView(
                UUID id,
                String title,
                CalendarPlacement placement,
                String category,
                CalendarLocation location,
                CalendarLocation transportOrigin,
                EndpointSource locationSource,
                EndpointSource transportOriginSource,
                TransportOfferStatus transportOfferStatus,
                String activityAdjustability,
                String transportMode,
                RouteProviderStatus routeProviderStatus,
                String routeTimeRole,
                String routeProvider,
                Instant routeEvidenceRetrievedAt,
                String recurrenceRule,
                LocalDate recurrenceUntil,
                Status status,
                long revision,
                UUID materializedPlanId) {
            this(
                    id,
                    title,
                    placement,
                    category,
                    location,
                    transportOrigin,
                    locationSource,
                    transportOriginSource,
                    transportOfferStatus,
                    activityAdjustability,
                    transportMode,
                    routeProviderStatus,
                    routeTimeRole,
                    routeProvider,
                    routeEvidenceRetrievedAt,
                    recurrenceRule,
                    recurrenceUntil,
                    status,
                    revision,
                    materializedPlanId,
                    null);
        }

        public DraftView(
                UUID id,
                String title,
                CalendarPlacement placement,
                String category,
                CalendarLocation location,
                CalendarLocation transportOrigin,
                TransportOfferStatus transportOfferStatus,
                String activityAdjustability,
                String transportMode,
                RouteProviderStatus routeProviderStatus,
                String routeTimeRole,
                String routeProvider,
                Instant routeEvidenceRetrievedAt,
                String recurrenceRule,
                LocalDate recurrenceUntil,
                Status status,
                long revision,
                UUID materializedPlanId) {
            this(
                    id,
                    title,
                    placement,
                    category,
                    location,
                    transportOrigin,
                    null,
                    null,
                    transportOfferStatus,
                    activityAdjustability,
                    transportMode,
                    routeProviderStatus,
                    routeTimeRole,
                    routeProvider,
                    routeEvidenceRetrievedAt,
                    recurrenceRule,
                    recurrenceUntil,
                    status,
                    revision,
                    materializedPlanId,
                    null);
        }

        public DraftView(
                UUID id,
                String title,
                CalendarPlacement placement,
                String category,
                CalendarLocation location,
                CalendarLocation transportOrigin,
                TransportOfferStatus transportOfferStatus,
                String activityAdjustability,
                String transportMode,
                RouteProviderStatus routeProviderStatus,
                String routeTimeRole,
                String routeProvider,
                Instant routeEvidenceRetrievedAt,
                String recurrenceRule,
                LocalDate recurrenceUntil,
                Status status,
                long revision,
                UUID materializedPlanId,
                RouteJourneyKind routeJourneyKind) {
            this(
                    id,
                    title,
                    placement,
                    category,
                    location,
                    transportOrigin,
                    null,
                    null,
                    transportOfferStatus,
                    activityAdjustability,
                    transportMode,
                    routeProviderStatus,
                    routeTimeRole,
                    routeProvider,
                    routeEvidenceRetrievedAt,
                    recurrenceRule,
                    recurrenceUntil,
                    status,
                    revision,
                    materializedPlanId,
                    routeJourneyKind);
        }

        public boolean hasRouteJourneyKind() {
            return routeJourneyKind != null;
        }
    }

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

        static ConfirmationResult completed(
                DraftView draft,
                CalendarIntentDraftPreflightService.PreflightResult preflight) {
            return new ConfirmationResult(draft, false, preflight);
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
            CalendarLocation transportOrigin,
            EndpointSource locationSource,
            EndpointSource transportOriginSource,
            TransportOfferStatus transportOfferStatus,
            String activityAdjustability,
            String transportMode,
            RouteProviderStatus routeProviderStatus,
            String routeTimeRole,
            String routeProvider,
            Instant routeEvidenceRetrievedAt,
            String recurrenceRule,
            LocalDate recurrenceUntil,
            Status status,
            long revision,
            String requestHash,
            UUID materializedPlanId,
            Instant expiresAt,
            String routePreflightStatus,
            String routePreflightHash,
            RouteJourneyKind routeJourneyKind) {}

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
