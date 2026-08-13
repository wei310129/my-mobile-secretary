package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationScopeResolver;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.RoutePlaceCreationDraftRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Typed persistence and materialization boundary for a Calendar route draft.
 *
 * <p>The caller may supply only Java-validated placement, endpoints, source semantics, and
 * provider evidence. This service does not call an LLM, a route provider, or a LINE adapter.
 * Provider failure leaves the Calendar plan untouched; a route draft alone is never represented
 * as a created Calendar plan.</p>
 */
@Service
@Transactional
public class CalendarIntentDraftService {

    public static final String ROOT_DOMAIN = "CALENDAR_ROUTE";
    private static final Duration RETENTION = Duration.ofDays(7);

    private final JdbcTemplate jdbc;
    private final CalendarApplicationService calendars;
    private final ConversationScopeResolver scopes;
    private final RoutePlaceCreationDraftRepository placeChildren;
    private final Clock clock;

    public CalendarIntentDraftService(
            JdbcTemplate jdbc,
            CalendarApplicationService calendars,
            ConversationScopeResolver scopes,
            RoutePlaceCreationDraftRepository placeChildren,
            Clock clock) {
        this.jdbc = jdbc;
        this.calendars = calendars;
        this.scopes = scopes;
        this.placeChildren = placeChildren;
        this.clock = clock;
    }

    /**
     * Starts an idempotent, still-unmaterialized route workflow from an already verified command.
     */
    public DraftView startRoute(RouteDraftRequest request) {
        Objects.requireNonNull(request, "route draft request");
        WorkspaceContext context = tenantContext();
        String scopeDigest = scopes.current(context).digest();
        String requestHash = hash("calendar-route-v1|" + scopeDigest + "|" + request.requestKey());
        DraftView existing = findByRequestHash(context, scopeDigest, requestHash);
        if (existing != null) {
            request.requireSamePayload(existing);
            return existing;
        }

        Instant now = Instant.now(clock);
        PlacementColumns placement = PlacementColumns.from(request.placement());
        int inserted = jdbc.update(
                """
                INSERT INTO calendar_intent_draft (
                    id, channel, conversation_scope_digest, scope_key_version,
                    title, placement_kind, timed_start, timed_end, zone_id,
                    all_day_start, all_day_end_exclusive, category,
                    location_label, latitude, longitude, location_source,
                    transport_origin_label, transport_origin_latitude,
                    transport_origin_longitude, transport_origin_source,
                    transport_offer_status, route_provider_status, route_journey_kind,
                    status, revision, request_hash, expires_at, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'NONE', 'NOT_REQUESTED', ?, 'PENDING', 1, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (workspace_id, created_by_user_id, request_hash) DO NOTHING
                """,
                UUID.randomUUID(),
                context.channel().name(),
                scopeDigest,
                scopes.current(context).keyVersion(),
                request.title(),
                request.placement().kind().name(),
                placement.timedStart(),
                placement.timedEnd(),
                placement.zoneId(),
                placement.allDayStart(),
                placement.allDayEndExclusive(),
                request.category(),
                request.destination().label(),
                request.destination().latitude(),
                request.destination().longitude(),
                request.destinationSource().name(),
                request.origin() == null ? null : request.origin().label(),
                request.origin() == null ? null : request.origin().latitude(),
                request.origin() == null ? null : request.origin().longitude(),
                request.originSource() == null ? null : request.originSource().name(),
                request.journeyKind().name(),
                requestHash,
                Timestamp.from(now.plus(RETENTION)),
                Timestamp.from(now),
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId());
        if (inserted != 0 && inserted != 1) {
            throw new IllegalStateException("calendar route draft arbitration returned an invalid count");
        }
        DraftView created = requireByRequestHash(context, scopeDigest, requestHash);
        request.requireSamePayload(created);
        return created;
    }

    /**
     * Records only an already verified provider result. No external query or Calendar mutation is
     * performed here.
     */
    public DraftView recordStandaloneRouteEvidence(
            UUID draftId, long expectedRevision, RouteProviderEvidence evidence) {
        Objects.requireNonNull(evidence, "route provider evidence");
        WorkspaceContext context = tenantContext();
        StoredDraft draft = load(context, draftId, true);
        requirePendingRevision(draft, expectedRevision);
        if (draft.journeyKind() != RouteJourneyKind.STANDALONE_TRIP
                || !(draft.placement() instanceof CalendarPlacement.TimedPoint point)
                || draft.origin() == null
                || draft.destination() == null) {
            throw changed();
        }
        evidence.requireStandaloneDeparture(point.time());
        CalendarPlacement.TimedInterval materializedPlacement = CalendarPlacement.interval(
                evidence.departureAt(), evidence.arrivalAt(), point.zoneId());
        PlacementColumns columns = PlacementColumns.from(materializedPlacement);
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET placement_kind = 'TIMED_INTERVAL', timed_start = ?, timed_end = ?, zone_id = ?,
                    all_day_start = NULL, all_day_end_exclusive = NULL,
                    transport_mode = ?, route_provider_status = 'AVAILABLE',
                    route_time_role = ?, route_provider = ?, route_evidence_retrieved_at = ?,
                    revision = revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND channel = ? AND conversation_scope_digest = ?
                  AND status = 'PENDING' AND revision = ?
                """,
                columns.timedStart(),
                columns.timedEnd(),
                columns.zoneId(),
                evidence.travelMode().name(),
                evidence.timeRole().name(),
                evidence.provider().name(),
                Timestamp.from(evidence.retrievedAt()),
                Timestamp.from(Instant.now(clock)),
                draftId,
                context.workspaceId(),
                context.actorId(),
                context.channel().name(),
                scopes.current(context).digest(),
                expectedRevision);
        if (changed != 1) {
            throw stale();
        }
        return view(load(context, draftId, false));
    }

    /**
     * Retains a typed, public-safe provider failure state. It creates no Calendar plan and keeps
     * the route operation resumable.
     */
    public DraftView recordProviderFailure(
            UUID draftId, long expectedRevision, RouteProviderFailure failure) {
        Objects.requireNonNull(failure, "route provider failure");
        WorkspaceContext context = tenantContext();
        StoredDraft draft = load(context, draftId, true);
        requirePendingRevision(draft, expectedRevision);
        if (draft.origin() == null || draft.destination() == null) {
            throw changed();
        }
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET transport_mode = ?, route_provider_status = 'UNAVAILABLE',
                    route_time_role = ?, route_provider = NULL, route_evidence_retrieved_at = NULL,
                    revision = revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND channel = ? AND conversation_scope_digest = ?
                  AND status = 'PENDING' AND revision = ?
                """,
                failure.travelMode().name(),
                failure.timeRole().name(),
                Timestamp.from(Instant.now(clock)),
                draftId,
                context.workspaceId(),
                context.actorId(),
                context.channel().name(),
                scopes.current(context).digest(),
                expectedRevision);
        if (changed != 1) {
            throw stale();
        }
        return view(load(context, draftId, false));
    }

    /** Binds a child-verified origin to its still-pending parent, without materializing a plan. */
    public DraftView setOriginFromChild(
            UUID draftId, long expectedRevision, CalendarLocation origin, EndpointSource source) {
        Objects.requireNonNull(origin, "origin");
        requireConfirmedEndpointSource(source);
        WorkspaceContext context = tenantContext();
        StoredDraft draft = load(context, draftId, true);
        requirePendingRevision(draft, expectedRevision);
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET transport_origin_label = ?, transport_origin_latitude = ?,
                    transport_origin_longitude = ?, transport_origin_source = ?,
                    route_provider_status = 'NOT_REQUESTED', route_time_role = NULL,
                    route_provider = NULL, route_evidence_retrieved_at = NULL,
                    transport_mode = NULL, revision = revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND channel = ? AND conversation_scope_digest = ?
                  AND status = 'PENDING' AND revision = ?
                """,
                origin.label(),
                origin.latitude(),
                origin.longitude(),
                source.name(),
                Timestamp.from(Instant.now(clock)),
                draftId,
                context.workspaceId(),
                context.actorId(),
                context.channel().name(),
                scopes.current(context).digest(),
                expectedRevision);
        if (changed != 1) {
            throw stale();
        }
        return view(load(context, draftId, false));
    }

    /**
     * The only route path that creates a Calendar plan. It requires provider evidence and no
     * unfinished Place child, then reuses CalendarApplicationService's idempotent plan and event
     * boundary (including LifeRecord/tag-graph listeners attached there).
     */
    public MaterializationResult materializeStandaloneRoute(UUID draftId, long expectedRevision) {
        WorkspaceContext context = tenantContext();
        StoredDraft draft = load(context, draftId, true);
        if (draft.status() == Status.MATERIALIZED && draft.revision() == expectedRevision + 1) {
            return MaterializationResult.replayed(view(draft));
        }
        requirePendingRevision(draft, expectedRevision);
        requireMaterializableStandaloneRoute(draft, context);

        String confirmationHash = hash("calendar-route-confirm-v1|" + draft.id()
                + "|" + expectedRevision + "|" + draft.requestHash());
        CalendarPlacement.TimedInterval placement = (CalendarPlacement.TimedInterval) draft.placement();
        List<CalendarNodeDraft> routeNodes = List.of(
                CalendarNodeDraft.at(CalendarTimeNode.absolute(
                        "departure", "出發", placement.start()), draft.origin()),
                CalendarNodeDraft.at(CalendarTimeNode.absolute(
                        "arrival", "抵達", placement.end()), draft.destination()));
        CreateCalendarPlanCommand command = new CreateCalendarPlanCommand(
                "calendar-route:" + confirmationHash,
                draft.title(),
                placement,
                draft.category(),
                null,
                null,
                List.of(),
                routeNodes);
        CalendarPlanIdentityView plan = calendars.createPlanWithIdentity(command);
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET status = 'MATERIALIZED', confirmation_hash = ?, materialized_plan_id = ?,
                    revision = revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND channel = ? AND conversation_scope_digest = ?
                  AND status = 'PENDING' AND revision = ?
                """,
                confirmationHash,
                plan.planId(),
                Timestamp.from(Instant.now(clock)),
                draftId,
                context.workspaceId(),
                context.actorId(),
                context.channel().name(),
                scopes.current(context).digest(),
                expectedRevision);
        if (changed != 1) {
            throw stale();
        }
        return MaterializationResult.created(view(load(context, draftId, false)), plan);
    }

    /** Cancels only an unfinished route draft; a created plan is never silently removed. */
    public boolean discard(UUID draftId, long expectedRevision) {
        WorkspaceContext context = tenantContext();
        StoredDraft draft = load(context, draftId, true);
        if (draft.status() == Status.DISCARDED) {
            return false;
        }
        requirePendingRevision(draft, expectedRevision);
        int changed = jdbc.update(
                """
                UPDATE calendar_intent_draft
                SET status = 'DISCARDED', revision = revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND channel = ? AND conversation_scope_digest = ?
                  AND status = 'PENDING' AND revision = ?
                """,
                Timestamp.from(Instant.now(clock)),
                draftId,
                context.workspaceId(),
                context.actorId(),
                context.channel().name(),
                scopes.current(context).digest(),
                expectedRevision);
        if (changed != 1) {
            throw stale();
        }
        return true;
    }

    @Transactional(readOnly = true)
    public java.util.Optional<DraftView> findAvailableForLifecycle(UUID draftId) {
        if (draftId == null) {
            return java.util.Optional.empty();
        }
        try {
            StoredDraft draft = load(tenantContext(), draftId, false);
            if (!draft.expiresAt().isAfter(Instant.now(clock))
                    || (draft.status() != Status.PENDING && draft.status() != Status.MATERIALIZED)) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(view(draft));
        } catch (NotFoundException ignored) {
            return java.util.Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public DraftView get(UUID draftId) {
        return view(load(tenantContext(), draftId, false));
    }

    private void requireMaterializableStandaloneRoute(StoredDraft draft, WorkspaceContext context) {
        if (draft.journeyKind() != RouteJourneyKind.STANDALONE_TRIP
                || draft.providerStatus() != RouteProviderStatus.AVAILABLE
                || !(draft.placement() instanceof CalendarPlacement.TimedInterval)
                || draft.provider() == null
                || draft.timeRole() != RouteTimeRole.DEPART_AT
                || draft.origin() == null
                || draft.destination() == null
                || placeChildren.existsByWorkspaceIdAndCreatedByUserIdAndParentCalendarDraftIdAndStatus(
                        context.workspaceId(),
                        context.actorId(),
                        draft.id(),
                        com.aproject.aidriven.mymobilesecretary.conversation.domain
                                .RoutePlaceCreationDraftStatus.PENDING)) {
            throw new BusinessException(
                    "CALENDAR_ROUTE_EVIDENCE_REQUIRED",
                    "A verified route and completed required route details are required");
        }
    }

    private DraftView findByRequestHash(
            WorkspaceContext context, String scopeDigest, String requestHash) {
        List<StoredDraft> rows = jdbc.query(select() + """
                WHERE workspace_id = ? AND created_by_user_id = ? AND channel = ?
                  AND conversation_scope_digest = ? AND request_hash = ?
                """, (row, ignored) -> stored(row), context.workspaceId(), context.actorId(),
                context.channel().name(), scopeDigest, requestHash);
        return rows.isEmpty() ? null : view(rows.getFirst());
    }

    private DraftView requireByRequestHash(
            WorkspaceContext context, String scopeDigest, String requestHash) {
        DraftView result = findByRequestHash(context, scopeDigest, requestHash);
        if (result == null) {
            throw new IllegalStateException("calendar route draft arbitration did not produce a draft");
        }
        return result;
    }

    private StoredDraft load(WorkspaceContext context, UUID id, boolean forUpdate) {
        if (id == null) {
            throw new NotFoundException("Calendar route draft", "requested route draft");
        }
        List<StoredDraft> rows = jdbc.query(select() + """
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND channel = ? AND conversation_scope_digest = ?
                """ + (forUpdate ? " FOR UPDATE" : ""),
                (row, ignored) -> stored(row),
                id,
                context.workspaceId(),
                context.actorId(),
                context.channel().name(),
                scopes.current(context).digest());
        if (rows.isEmpty()) {
            throw new NotFoundException("Calendar route draft", "requested route draft");
        }
        return rows.getFirst();
    }

    private static String select() {
        return """
                SELECT id, title, placement_kind, timed_start, timed_end, zone_id,
                       all_day_start, all_day_end_exclusive, category,
                       location_label, latitude, longitude, location_source,
                       transport_origin_label, transport_origin_latitude,
                       transport_origin_longitude, transport_origin_source,
                       route_provider_status, route_time_role, route_provider,
                       route_evidence_retrieved_at, route_journey_kind,
                       status, revision, request_hash, materialized_plan_id, expires_at
                FROM calendar_intent_draft
                """;
    }

    private static StoredDraft stored(ResultSet row) throws SQLException {
        CalendarPlacement placement = placement(row);
        return new StoredDraft(
                row.getObject("id", UUID.class),
                row.getString("title"),
                placement,
                row.getString("category"),
                location(row, "transport_origin_label", "transport_origin_latitude",
                        "transport_origin_longitude"),
                location(row, "location_label", "latitude", "longitude"),
                nullableEnum(row.getString("transport_origin_source"), EndpointSource.class),
                nullableEnum(row.getString("location_source"), EndpointSource.class),
                RouteProviderStatus.valueOf(row.getString("route_provider_status")),
                nullableEnum(row.getString("route_time_role"), RouteTimeRole.class),
                nullableEnum(row.getString("route_provider"), RouteProvider.class),
                timestamp(row, "route_evidence_retrieved_at"),
                nullableEnum(row.getString("route_journey_kind"), RouteJourneyKind.class),
                Status.valueOf(row.getString("status")),
                row.getLong("revision"),
                row.getString("request_hash"),
                row.getObject("materialized_plan_id", UUID.class),
                timestamp(row, "expires_at"));
    }

    private static CalendarPlacement placement(ResultSet row) throws SQLException {
        String kind = row.getString("placement_kind");
        if ("TIMED_INTERVAL".equals(kind)) {
            return CalendarPlacement.interval(
                    row.getTimestamp("timed_start").toInstant(),
                    row.getTimestamp("timed_end").toInstant(),
                    ZoneId.of(row.getString("zone_id")));
        }
        if ("TIMED_POINT".equals(kind)) {
            return CalendarPlacement.point(
                    row.getTimestamp("timed_start").toInstant(),
                    ZoneId.of(row.getString("zone_id")));
        }
        return CalendarPlacement.allDay(
                row.getObject("all_day_start", LocalDate.class),
                row.getObject("all_day_end_exclusive", LocalDate.class));
    }

    private static CalendarLocation location(
            ResultSet row, String labelColumn, String latitudeColumn, String longitudeColumn)
            throws SQLException {
        String label = row.getString(labelColumn);
        if (label == null) {
            return null;
        }
        Double latitude = row.getObject(latitudeColumn, Double.class);
        Double longitude = row.getObject(longitudeColumn, Double.class);
        if (latitude == null || longitude == null) {
            throw new IllegalStateException("calendar route endpoint is incomplete");
        }
        return new CalendarLocation(label, latitude, longitude);
    }

    private static Instant timestamp(ResultSet row, String column) throws SQLException {
        Timestamp timestamp = row.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static <T extends Enum<T>> T nullableEnum(String value, Class<T> type) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static void requirePendingRevision(StoredDraft draft, long expectedRevision) {
        if (expectedRevision < 1 || draft.status() != Status.PENDING
                || draft.revision() != expectedRevision) {
            throw stale();
        }
    }

    private static BusinessException stale() {
        return new BusinessException("CALENDAR_ROUTE_CHANGED", "The route changed before it was updated");
    }

    private static BusinessException changed() {
        return new BusinessException("CALENDAR_ROUTE_CHANGED", "The route no longer accepts this step");
    }

    private static void requireConfirmedEndpointSource(EndpointSource source) {
        if (source == null || source == EndpointSource.LEGACY_UNSPECIFIED) {
            throw new IllegalArgumentException("A confirmed route endpoint source is required");
        }
    }

    private static WorkspaceContext tenantContext() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("Calendar route operations require a tenant workspace");
        }
        return context;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public enum Status {
        PENDING,
        MATERIALIZED,
        DISCARDED
    }

    public enum RouteProviderStatus {
        NOT_REQUESTED,
        UNAVAILABLE,
        RETAINED,
        AVAILABLE
    }

    public enum RouteProvider {
        TDX,
        GOOGLE
    }

    public enum RouteTimeRole {
        DEPART_AT,
        ARRIVE_BY
    }

    public enum RouteTravelMode {
        DRIVE,
        TWO_WHEELER,
        WALK,
        TRANSIT
    }

    public enum EndpointSource {
        EXPLICIT_CURRENT_TURN,
        CONFIRMED_HOME,
        CONFIRMED_CONTEXT,
        LEGACY_UNSPECIFIED
    }

    public record RouteDraftRequest(
            String requestKey,
            String title,
            CalendarPlacement placement,
            String category,
            CalendarLocation origin,
            EndpointSource originSource,
            CalendarLocation destination,
            EndpointSource destinationSource,
            RouteJourneyKindPolicy.SourceSemantics sourceSemantics) {
        public RouteDraftRequest {
            requestKey = required(requestKey, 160, "route request key");
            title = required(title, 200, "route title");
            Objects.requireNonNull(placement, "route placement");
            category = optional(category, 100);
            Objects.requireNonNull(destination, "route destination");
            if ((origin == null) != (originSource == null)) {
                throw new IllegalArgumentException(
                        "Route origin and its source must be supplied together");
            }
            if (originSource != null) {
                requireConfirmedEndpointSource(originSource);
            }
            requireConfirmedEndpointSource(destinationSource);
            Objects.requireNonNull(sourceSemantics, "route source semantics");
            RouteJourneyKindPolicy.resolve(placement, sourceSemantics);
        }

        public RouteJourneyKind journeyKind() {
            return RouteJourneyKindPolicy.resolve(placement, sourceSemantics);
        }

        private void requireSamePayload(DraftView existing) {
            if (!existing.title().equals(title)
                    || !existing.placement().equals(placement)
                    || !Objects.equals(existing.category(), category)
                    || !Objects.equals(origin, existing.origin())
                    || !Objects.equals(destination, existing.destination())
                    || originSource != existing.originSource()
                    || destinationSource != existing.destinationSource()
                    || journeyKind() != existing.journeyKind()) {
                throw new BusinessException(
                        "IDEMPOTENCY_CONFLICT", "The route request key was reused with different content");
            }
        }
    }

    public record RouteProviderEvidence(
            RouteProvider provider,
            RouteTravelMode travelMode,
            RouteTimeRole timeRole,
            Instant retrievedAt,
            Instant departureAt,
            Instant arrivalAt) {
        public RouteProviderEvidence {
            Objects.requireNonNull(provider, "route provider");
            Objects.requireNonNull(travelMode, "route travel mode");
            Objects.requireNonNull(timeRole, "route time role");
            Objects.requireNonNull(retrievedAt, "route evidence retrieved time");
            Objects.requireNonNull(departureAt, "route departure");
            Objects.requireNonNull(arrivalAt, "route arrival");
            if (!arrivalAt.isAfter(departureAt)) {
                throw new IllegalArgumentException("Route arrival must be after departure");
            }
        }

        private void requireStandaloneDeparture(Instant expectedDeparture) {
            if (timeRole != RouteTimeRole.DEPART_AT || !departureAt.equals(expectedDeparture)) {
                throw new IllegalArgumentException(
                        "Standalone route evidence must preserve the verified departure time");
            }
        }
    }

    public record RouteProviderFailure(RouteTravelMode travelMode, RouteTimeRole timeRole) {
        public RouteProviderFailure {
            Objects.requireNonNull(travelMode, "route travel mode");
            Objects.requireNonNull(timeRole, "route time role");
        }
    }

    public record DraftView(
            UUID id,
            String title,
            CalendarPlacement placement,
            String category,
            CalendarLocation origin,
            CalendarLocation destination,
            EndpointSource originSource,
            EndpointSource destinationSource,
            RouteProviderStatus providerStatus,
            RouteTimeRole timeRole,
            RouteProvider provider,
            Instant evidenceRetrievedAt,
            RouteJourneyKind journeyKind,
            Status status,
            long revision,
            UUID materializedPlanId,
            Instant expiresAt) {
        public boolean mayClaimCreated() {
            return status == Status.MATERIALIZED && materializedPlanId != null;
        }

        /** Evidence is present; freshness policy is supplied by the Travel routing lane. */
        public boolean hasProviderEvidence() {
            return providerStatus == RouteProviderStatus.AVAILABLE
                    && provider != null
                    && timeRole != null
                    && evidenceRetrievedAt != null;
        }
    }

    public record MaterializationResult(DraftView draft, CalendarPlanIdentityView plan, boolean replayed) {
        static MaterializationResult created(DraftView draft, CalendarPlanIdentityView plan) {
            return new MaterializationResult(draft, plan, false);
        }

        static MaterializationResult replayed(DraftView draft) {
            return new MaterializationResult(draft, null, true);
        }
    }

    private static DraftView view(StoredDraft draft) {
        return new DraftView(
                draft.id(),
                draft.title(),
                draft.placement(),
                draft.category(),
                draft.origin(),
                draft.destination(),
                draft.originSource(),
                draft.destinationSource(),
                draft.providerStatus(),
                draft.timeRole(),
                draft.provider(),
                draft.evidenceRetrievedAt(),
                draft.journeyKind(),
                draft.status(),
                draft.revision(),
                draft.materializedPlanId(),
                draft.expiresAt());
    }

    private record StoredDraft(
            UUID id,
            String title,
            CalendarPlacement placement,
            String category,
            CalendarLocation origin,
            CalendarLocation destination,
            EndpointSource originSource,
            EndpointSource destinationSource,
            RouteProviderStatus providerStatus,
            RouteTimeRole timeRole,
            RouteProvider provider,
            Instant evidenceRetrievedAt,
            RouteJourneyKind journeyKind,
            Status status,
            long revision,
            String requestHash,
            UUID materializedPlanId,
            Instant expiresAt) {}

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
                        Timestamp.from(point.time()), null, point.zoneId().getId(), null, null);
            }
            CalendarPlacement.AllDay allDay = (CalendarPlacement.AllDay) placement;
            return new PlacementColumns(null, null, null, allDay.start(), allDay.endExclusive());
        }
    }

    private static String required(String value, int maximumLength, String label) {
        String normalized = optional(value, maximumLength);
        if (normalized == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return normalized;
    }

    private static String optional(String value, int maximumLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.length() > maximumLength
                || normalized.indexOf('\n') >= 0
                || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("route text is invalid");
        }
        return normalized;
    }
}
