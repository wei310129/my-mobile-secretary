package com.aproject.aidriven.mymobilesecretary.booking.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.booking.domain.BookingExecutionState;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ConfirmationMode;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ExternalBookingOrder;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ExternalBookingOrderStatus;
import com.aproject.aidriven.mymobilesecretary.booking.domain.OfferSnapshot;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ProviderCapability;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ProviderEnvironment;
import com.aproject.aidriven.mymobilesecretary.booking.domain.PurchaseAuthorization;
import com.aproject.aidriven.mymobilesecretary.booking.domain.SubstitutionStrength;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BookingExecutionStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BookingExecutionStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public void saveOffer(OfferSnapshot offer) {
        WorkspaceContext owner = owner();
        int inserted = jdbc.update(
                """
                INSERT INTO booking_offer_snapshot (
                    id, provider, environment, inventory_identity,
                    retrieved_at, expires_at, total_price, currency,
                    traveller_ids, terms_fingerprint, non_refundable,
                    available, capabilities, created_at,
                    workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                offer.offerId(),
                offer.provider(),
                offer.environment().name(),
                offer.inventoryIdentity(),
                Timestamp.from(offer.retrievedAt()),
                Timestamp.from(offer.expiresAt()),
                offer.totalPrice(),
                offer.currency().getCurrencyCode(),
                encodeUuids(offer.travellerIds()),
                offer.termsFingerprint(),
                offer.nonRefundable(),
                offer.available(),
                encodeCapabilities(offer.capabilities()),
                Timestamp.from(clock.instant()),
                owner.workspaceId(),
                owner.actorId());
        if (inserted == 0
                && !loadOffer(offer.offerId()).filter(saved -> sameOffer(saved, offer)).isPresent()) {
            throw new IllegalStateException("offer replay conflicts with durable snapshot");
        }
    }

    @Transactional
    public void saveAuthorization(PurchaseAuthorization authorization) {
        WorkspaceContext owner = owner();
        if (!authorization.workspaceId().equals(owner.workspaceId())
                || !authorization.actorId().equals(owner.actorId())) {
            throw new SecurityException("authorization owner does not match current context");
        }
        int inserted = jdbc.update(
                """
                INSERT INTO booking_purchase_authorization (
                    id, offer_id, provider, environment, traveller_ids,
                    max_total_price, currency, terms_fingerprint,
                    non_refundable_accepted, expires_at, confirmation_mode,
                    substitution_strength, created_at,
                    workspace_id, created_by_user_id)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                WHERE EXISTS (
                    SELECT 1
                    FROM booking_offer_snapshot offer
                    WHERE offer.id = ?
                      AND offer.workspace_id = ?
                      AND offer.created_by_user_id = ?)
                ON CONFLICT (id) DO NOTHING
                """,
                authorization.authorizationId(),
                authorization.offerId(),
                authorization.provider(),
                authorization.environment().name(),
                encodeUuids(authorization.travellerIds()),
                authorization.maxTotalPrice(),
                authorization.currency().getCurrencyCode(),
                authorization.termsFingerprint(),
                authorization.nonRefundableAccepted(),
                Timestamp.from(authorization.expiresAt()),
                authorization.confirmationMode().name(),
                authorization.substitutionStrength().name(),
                Timestamp.from(clock.instant()),
                owner.workspaceId(),
                owner.actorId(),
                authorization.offerId(),
                owner.workspaceId(),
                owner.actorId());
        if (inserted == 0) {
            PurchaseAuthorization saved =
                    loadAuthorization(authorization.authorizationId()).orElseThrow(() ->
                            new IllegalStateException(
                                    "authorization offer is unavailable to actor"));
            if (!sameAuthorization(saved, authorization)) {
                throw new IllegalStateException(
                        "authorization replay conflicts with durable grant");
            }
        }
    }

    @Transactional
    public void createAuthorizedPlan(UUID planId, UUID authorizationId, int totalItems) {
        if (totalItems < 1) {
            throw new IllegalArgumentException("totalItems must be positive");
        }
        WorkspaceContext owner = owner();
        int inserted = jdbc.update(
                """
                INSERT INTO booking_plan (
                    id, authorization_id, total_items, state, version,
                    created_at, updated_at, workspace_id, created_by_user_id)
                SELECT ?, auth.id, ?, 'AUTHORIZED', 0, ?, ?, ?, ?
                FROM booking_purchase_authorization auth
                WHERE auth.id = ?
                  AND auth.workspace_id = ?
                  AND auth.created_by_user_id = ?
                  AND auth.expires_at > ?
                ON CONFLICT (id) DO NOTHING
                """,
                planId,
                totalItems,
                Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()),
                owner.workspaceId(),
                owner.actorId(),
                authorizationId,
                owner.workspaceId(),
                owner.actorId(),
                Timestamp.from(clock.instant()));
        if (inserted == 0) {
            List<PlanSeed> existing = jdbc.query(
                    """
                    SELECT authorization_id, total_items
                    FROM booking_plan
                    WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                    """,
                    (result, row) -> new PlanSeed(
                            result.getObject("authorization_id", UUID.class),
                            result.getInt("total_items")),
                    planId,
                    owner.workspaceId(),
                    owner.actorId());
            if (existing.isEmpty()) {
                throw new IllegalStateException("fresh authorization is required");
            }
            if (!existing.getFirst().authorizationId().equals(authorizationId)
                    || existing.getFirst().totalItems() != totalItems) {
                throw new IllegalStateException(
                        "plan replay conflicts with durable authorization");
            }
        }
    }

    @Transactional
    public OperationClaim claimOperation(
            UUID planId,
            String operationId,
            ProviderCapability capability,
            Duration leaseDuration) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        WorkspaceContext owner = owner();
        Instant now = clock.instant();
        ExistingAttempt existing = findAttemptForUpdate(owner, operationId).orElse(null);
        if (existing != null) {
            return replayClaim(existing, planId, capability, leaseDuration, now, owner);
        }
        requireExecutablePlan(planId, owner, capability);
        UUID attemptId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        int inserted = jdbc.update(
                """
                INSERT INTO booking_attempt (
                    id, plan_id, operation_id, capability, dispatch_state,
                    public_reason, claim_token, lease_until,
                    created_at, updated_at, workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, 'CLAIMED', '', ?, ?, ?, ?, ?, ?)
                ON CONFLICT (
                    workspace_id, created_by_user_id, operation_id)
                DO NOTHING
                """,
                attemptId,
                planId,
                operationId,
                capability.name(),
                token,
                Timestamp.from(now.plus(leaseDuration)),
                Timestamp.from(now),
                Timestamp.from(now),
                owner.workspaceId(),
                owner.actorId());
        if (inserted == 0) {
            ExistingAttempt raced = findAttemptForUpdate(owner, operationId)
                    .orElseThrow(() -> new IllegalStateException(
                            "operation claim conflict was not durable"));
            return replayClaim(raced, planId, capability, leaseDuration, now, owner);
        }
        jdbc.update(
                """
                UPDATE booking_plan
                SET state = 'EXECUTING', version = version + 1, updated_at = ?
                WHERE id = ? AND state = 'AUTHORIZED'
                """,
                Timestamp.from(now),
                planId);
        return new OperationClaim(
                attemptId, token, now.plus(leaseDuration), DispatchDisposition.MAY_DISPATCH);
    }

    @Transactional
    public void markDispatched(UUID attemptId, UUID claimToken) {
        WorkspaceContext owner = owner();
        Instant now = clock.instant();
        int updated = jdbc.update(
                """
                UPDATE booking_attempt
                SET dispatch_state = 'DISPATCHED',
                    outcome_status = 'NEEDS_RECONCILIATION',
                    dispatched_at = ?, updated_at = ?
                WHERE id = ? AND claim_token = ?
                  AND dispatch_state = 'CLAIMED'
                  AND lease_until > ?
                  AND workspace_id = ? AND created_by_user_id = ?
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                attemptId,
                claimToken,
                Timestamp.from(now),
                owner.workspaceId(),
                owner.actorId());
        if (updated != 1) {
            throw new IllegalStateException("attempt lease is stale or already dispatched");
        }
        jdbc.update(
                """
                UPDATE booking_plan plan
                SET state = 'NEEDS_RECONCILIATION',
                    version = version + 1, updated_at = ?
                FROM booking_attempt attempt
                WHERE attempt.id = ? AND plan.id = attempt.plan_id
                  AND plan.workspace_id = ? AND plan.created_by_user_id = ?
                """,
                Timestamp.from(now),
                attemptId,
                owner.workspaceId(),
                owner.actorId());
    }

    @Transactional
    public void recordSuccess(
            UUID attemptId, UUID claimToken, ExternalBookingOrder order) {
        WorkspaceContext owner = owner();
        ExistingAttempt attempt = findAttemptByIdForUpdate(owner, attemptId)
                .orElseThrow(() -> new IllegalStateException("attempt not found"));
        if (attempt.dispatchState().equals("SETTLED")) {
            if (!claimToken.equals(attempt.claimToken())
                    || !order.orderId().equals(attempt.orderId())) {
                throw new IllegalStateException("settled provider result conflicts with attempt");
            }
            assertOrderMatchesAuthorization(attempt.planId(), order, owner);
            StoredOrder stored = findStoredOrderForUpdate(owner, order.orderId())
                    .orElseThrow(() -> new IllegalStateException("settled order not found"));
            if (!stored.planId().equals(attempt.planId())
                    || !sameOrder(stored.order(), order)) {
                throw new IllegalStateException("settled provider result conflicts with durable order");
            }
            return;
        }
        if (!attempt.dispatchState().equals("DISPATCHED")
                || !claimToken.equals(attempt.claimToken())
                || !attempt.leaseUntil().isAfter(clock.instant())) {
            throw new IllegalStateException("provider result does not own dispatched attempt");
        }
        settleSuccess(attempt, order, owner);
    }

    @Transactional
    public void reconcileSuccess(
            UUID planId, String operationId, ExternalBookingOrder order) {
        WorkspaceContext owner = owner();
        ExistingAttempt attempt = findAttemptForUpdate(owner, operationId)
                .orElseThrow(() -> new IllegalStateException("attempt not found"));
        if (!attempt.planId().equals(planId)
                || !attempt.dispatchState().equals("DISPATCHED")) {
            throw new IllegalStateException("operation is not awaiting reconciliation");
        }
        settleSuccess(attempt, order, owner);
    }

    @Transactional
    public void recordFailure(UUID attemptId, UUID claimToken, String publicReason) {
        requireText(publicReason, "publicReason");
        if (publicReason.length() > 512) {
            throw new IllegalArgumentException("publicReason is too long");
        }
        WorkspaceContext owner = owner();
        ExistingAttempt attempt = findAttemptByIdForUpdate(owner, attemptId)
                .orElseThrow(() -> new IllegalStateException("attempt not found"));
        if (!attempt.dispatchState().equals("DISPATCHED")
                || !claimToken.equals(attempt.claimToken())
                || !attempt.leaseUntil().isAfter(clock.instant())) {
            throw new IllegalStateException("provider result does not own dispatched attempt");
        }
        settleFailure(attempt, publicReason, owner);
    }

    @Transactional
    public void reconcileFailure(UUID planId, String operationId, String publicReason) {
        requireText(publicReason, "publicReason");
        if (publicReason.length() > 512) {
            throw new IllegalArgumentException("publicReason is too long");
        }
        WorkspaceContext owner = owner();
        ExistingAttempt attempt = findAttemptForUpdate(owner, operationId)
                .orElseThrow(() -> new IllegalStateException("attempt not found"));
        if (!attempt.planId().equals(planId)
                || !attempt.dispatchState().equals("DISPATCHED")) {
            throw new IllegalStateException("operation is not awaiting reconciliation");
        }
        settleFailure(attempt, publicReason, owner);
    }

    private void settleFailure(
            ExistingAttempt attempt, String publicReason, WorkspaceContext owner) {
        Instant now = clock.instant();
        jdbc.update(
                """
                UPDATE booking_attempt
                SET dispatch_state = 'SETTLED', outcome_status = 'FAILED',
                    public_reason = ?, reconciled_at = ?, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                """,
                publicReason,
                Timestamp.from(now),
                Timestamp.from(now),
                attempt.attemptId(),
                owner.workspaceId(),
                owner.actorId());
        Long succeeded = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM booking_attempt
                WHERE plan_id = ? AND outcome_status = 'SUCCEEDED'
                  AND workspace_id = ? AND created_by_user_id = ?
                """,
                Long.class,
                attempt.planId(),
                owner.workspaceId(),
                owner.actorId());
        BookingExecutionState next = succeeded != null && succeeded > 0
                ? BookingExecutionState.PARTIALLY_COMPLETED
                : BookingExecutionState.FAILED;
        jdbc.update(
                """
                UPDATE booking_plan
                SET state = ?, version = version + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                """,
                next.name(),
                Timestamp.from(now),
                attempt.planId(),
                owner.workspaceId(),
                owner.actorId());
        enqueueTerminal(attempt.planId(), attempt.attemptId(), owner, now);
    }

    @Transactional(readOnly = true)
    public Optional<OfferSnapshot> loadOffer(UUID offerId) {
        WorkspaceContext owner = owner();
        return jdbc.query(
                        """
                        SELECT *
                        FROM booking_offer_snapshot
                        WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                        """,
                        (result, row) -> mapOffer(result),
                        offerId,
                        owner.workspaceId(),
                        owner.actorId())
                .stream()
                .findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<PurchaseAuthorization> loadAuthorization(UUID authorizationId) {
        WorkspaceContext owner = owner();
        return jdbc.query(
                        """
                        SELECT *
                        FROM booking_purchase_authorization
                        WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                        """,
                        (result, row) -> mapAuthorization(result),
                        authorizationId,
                        owner.workspaceId(),
                        owner.actorId())
                .stream()
                .findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<PlanView> loadPlan(UUID planId) {
        WorkspaceContext owner = owner();
        List<PlanRow> plans = jdbc.query(
                """
                SELECT id, authorization_id, total_items, state
                FROM booking_plan
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                """,
                (result, row) -> new PlanRow(
                        result.getObject("id", UUID.class),
                        result.getObject("authorization_id", UUID.class),
                        result.getInt("total_items"),
                        BookingExecutionState.valueOf(result.getString("state"))),
                planId,
                owner.workspaceId(),
                owner.actorId());
        if (plans.isEmpty()) {
            return Optional.empty();
        }
        List<AttemptView> attempts = jdbc.query(
                """
                SELECT id, operation_id, capability, dispatch_state,
                       outcome_status, order_id
                FROM booking_attempt
                WHERE plan_id = ? AND workspace_id = ? AND created_by_user_id = ?
                ORDER BY created_at, id
                """,
                (result, row) -> new AttemptView(
                        result.getObject("id", UUID.class),
                        result.getString("operation_id"),
                        ProviderCapability.valueOf(result.getString("capability")),
                        result.getString("dispatch_state"),
                        result.getString("outcome_status"),
                        result.getObject("order_id", UUID.class)),
                planId,
                owner.workspaceId(),
                owner.actorId());
        List<ExternalBookingOrder> orders = jdbc.query(
                """
                SELECT *
                FROM external_booking_order
                WHERE plan_id = ? AND workspace_id = ? AND created_by_user_id = ?
                ORDER BY created_at, id
                """,
                (result, row) -> mapOrder(result),
                planId,
                owner.workspaceId(),
                owner.actorId());
        PlanRow plan = plans.getFirst();
        return Optional.of(
                new PlanView(
                        plan.planId(),
                        plan.authorizationId(),
                        plan.totalItems(),
                        plan.state(),
                        attempts,
                        orders));
    }

    @Transactional
    public WebhookDisposition acceptWebhook(
            String provider,
            ProviderEnvironment environment,
            String eventKeyDigest,
            String payloadDigest,
            String eventType,
            long providerVersion) {
        requireText(provider, "provider");
        requireText(eventKeyDigest, "eventKeyDigest");
        requireText(payloadDigest, "payloadDigest");
        requireText(eventType, "eventType");
        WorkspaceContext owner = owner();
        List<WebhookRow> existing = jdbc.query(
                """
                SELECT payload_digest, event_type, provider_version
                FROM booking_webhook_inbox
                WHERE workspace_id = ? AND created_by_user_id = ?
                  AND provider = ? AND environment = ?
                  AND event_key_digest = ?
                FOR UPDATE
                """,
                (result, row) -> new WebhookRow(
                        result.getString("payload_digest"),
                        result.getString("event_type"),
                        result.getLong("provider_version")),
                owner.workspaceId(),
                owner.actorId(),
                provider,
                environment.name(),
                eventKeyDigest);
        if (!existing.isEmpty()) {
            WebhookRow row = existing.getFirst();
            if (row.payloadDigest().equals(payloadDigest)
                    && row.eventType().equals(eventType)
                    && row.providerVersion() == providerVersion) {
                return WebhookDisposition.DUPLICATE;
            }
            throw new IllegalStateException("webhook replay conflicts with recorded digest");
        }
        Long latestVersion = jdbc.queryForObject(
                """
                SELECT max(provider_version)
                FROM booking_webhook_inbox
                WHERE workspace_id = ? AND created_by_user_id = ?
                  AND provider = ? AND environment = ?
                """,
                Long.class,
                owner.workspaceId(),
                owner.actorId(),
                provider,
                environment.name());
        boolean outOfOrder = latestVersion != null && providerVersion < latestVersion;
        jdbc.update(
                """
                INSERT INTO booking_webhook_inbox (
                    id, provider, environment, event_key_digest,
                    payload_digest, event_type, provider_version, status,
                    received_at, workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                provider,
                environment.name(),
                eventKeyDigest,
                payloadDigest,
                eventType,
                providerVersion,
                outOfOrder ? "PROCESSED" : "RECEIVED",
                Timestamp.from(clock.instant()),
                owner.workspaceId(),
                owner.actorId());
        return outOfOrder ? WebhookDisposition.OUT_OF_ORDER : WebhookDisposition.ACCEPTED;
    }

    @Transactional
    public List<TerminalClaim> claimTerminalResults(int limit, Duration leaseDuration) {
        if (limit < 1 || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("positive limit and leaseDuration are required");
        }
        WorkspaceContext owner = owner();
        Instant now = clock.instant();
        List<UUID> ids = jdbc.query(
                """
                SELECT id
                FROM booking_execution_outbox
                WHERE workspace_id = ? AND created_by_user_id = ?
                  AND (
                    status = 'PENDING'
                    OR (status = 'CLAIMED' AND claim_until <= ?))
                ORDER BY created_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """,
                (result, row) -> result.getObject("id", UUID.class),
                owner.workspaceId(),
                owner.actorId(),
                Timestamp.from(now),
                limit);
        List<TerminalClaim> claims = new ArrayList<>();
        for (UUID id : ids) {
            UUID token = UUID.randomUUID();
            Instant claimUntil = now.plus(leaseDuration);
            jdbc.update(
                    """
                    UPDATE booking_execution_outbox
                    SET status = 'CLAIMED', claim_token = ?, claim_until = ?,
                        attempt_count = attempt_count + 1, updated_at = ?
                    WHERE id = ?
                    """,
                    token,
                    Timestamp.from(claimUntil),
                    Timestamp.from(now),
                    id);
            claims.add(jdbc.queryForObject(
                    """
                    SELECT id, plan_id, event_key, event_type
                    FROM booking_execution_outbox
                    WHERE id = ?
                    """,
                    (result, row) -> new TerminalClaim(
                            result.getObject("id", UUID.class),
                            result.getObject("plan_id", UUID.class),
                            result.getString("event_key"),
                            result.getString("event_type"),
                            token,
                            claimUntil),
                    id));
        }
        return List.copyOf(claims);
    }

    @Transactional
    public boolean acknowledgeTerminal(UUID outboxId, UUID claimToken) {
        WorkspaceContext owner = owner();
        Instant now = clock.instant();
        return jdbc.update(
                        """
                        UPDATE booking_execution_outbox
                        SET status = 'ACKNOWLEDGED', acknowledged_at = ?,
                            claim_token = NULL, claim_until = NULL, updated_at = ?
                        WHERE id = ? AND claim_token = ? AND status = 'CLAIMED'
                          AND claim_until > ?
                          AND workspace_id = ? AND created_by_user_id = ?
                        """,
                        Timestamp.from(now),
                        Timestamp.from(now),
                        outboxId,
                        claimToken,
                        Timestamp.from(now),
                        owner.workspaceId(),
                        owner.actorId())
                == 1;
    }

    private OperationClaim replayClaim(
            ExistingAttempt existing,
            UUID planId,
            ProviderCapability capability,
            Duration leaseDuration,
            Instant now,
            WorkspaceContext owner) {
        if (!existing.planId().equals(planId)
                || !existing.capability().equals(capability.name())) {
            throw new IllegalStateException("operation replay changes plan or capability");
        }
        if (existing.dispatchState().equals("DISPATCHED")) {
            return new OperationClaim(
                    existing.attemptId(),
                    existing.claimToken(),
                    existing.leaseUntil(),
                    DispatchDisposition.NEEDS_RECONCILIATION);
        }
        if (existing.dispatchState().equals("SETTLED")) {
            return new OperationClaim(
                    existing.attemptId(),
                    existing.claimToken(),
                    existing.leaseUntil(),
                    DispatchDisposition.ALREADY_SETTLED);
        }
        if (existing.leaseUntil().isAfter(now)) {
            return new OperationClaim(
                    existing.attemptId(),
                    existing.claimToken(),
                    existing.leaseUntil(),
                    DispatchDisposition.LEASE_HELD);
        }
        UUID token = UUID.randomUUID();
        Instant until = now.plus(leaseDuration);
        jdbc.update(
                """
                UPDATE booking_attempt
                SET claim_token = ?, lease_until = ?, updated_at = ?
                WHERE id = ? AND dispatch_state = 'CLAIMED'
                  AND workspace_id = ? AND created_by_user_id = ?
                """,
                token,
                Timestamp.from(until),
                Timestamp.from(now),
                existing.attemptId(),
                owner.workspaceId(),
                owner.actorId());
        return new OperationClaim(
                existing.attemptId(), token, until, DispatchDisposition.MAY_DISPATCH);
    }

    private void settleSuccess(
            ExistingAttempt attempt,
            ExternalBookingOrder order,
            WorkspaceContext owner) {
        Instant now = clock.instant();
        assertOrderMatchesAuthorization(attempt.planId(), order, owner);
        jdbc.update(
                """
                INSERT INTO external_booking_order (
                    id, plan_id, provider, environment, provider_reference,
                    status, observed_at, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                order.orderId(),
                attempt.planId(),
                order.provider(),
                order.environment().name(),
                order.providerReference(),
                order.status().name(),
                Timestamp.from(order.observedAt().truncatedTo(ChronoUnit.MICROS)),
                Timestamp.from(now),
                Timestamp.from(now),
                owner.workspaceId(),
                owner.actorId());
        StoredOrder stored = findStoredOrderForUpdate(owner, order.orderId())
                .orElseThrow(() -> new IllegalStateException("provider order was not persisted"));
        if (!stored.planId().equals(attempt.planId())
                || !sameOrder(stored.order(), order)) {
            throw new IllegalStateException("provider order conflicts with durable terminal result");
        }
        int settled = jdbc.update(
                """
                UPDATE booking_attempt
                SET dispatch_state = 'SETTLED', outcome_status = 'SUCCEEDED',
                    order_id = ?, reconciled_at = ?, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                """,
                order.orderId(),
                Timestamp.from(now),
                Timestamp.from(now),
                attempt.attemptId(),
                owner.workspaceId(),
                owner.actorId());
        if (settled != 1) {
            throw new IllegalStateException("attempt could not be settled");
        }
        Integer totalItems = jdbc.queryForObject(
                """
                SELECT total_items
                FROM booking_plan
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                FOR UPDATE
                """,
                Integer.class,
                attempt.planId(),
                owner.workspaceId(),
                owner.actorId());
        Long succeeded = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM booking_attempt
                WHERE plan_id = ? AND outcome_status = 'SUCCEEDED'
                  AND workspace_id = ? AND created_by_user_id = ?
                """,
                Long.class,
                attempt.planId(),
                owner.workspaceId(),
                owner.actorId());
        BookingExecutionState next = succeeded != null
                        && totalItems != null
                        && succeeded >= totalItems
                ? BookingExecutionState.COMPLETED
                : BookingExecutionState.EXECUTING;
        jdbc.update(
                """
                UPDATE booking_plan
                SET state = ?, version = version + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                """,
                next.name(),
                Timestamp.from(now),
                attempt.planId(),
                owner.workspaceId(),
                owner.actorId());
        if (next == BookingExecutionState.COMPLETED) {
            enqueueTerminal(attempt.planId(), attempt.attemptId(), owner, now);
        }
    }

    private void enqueueTerminal(
            UUID planId, UUID attemptId, WorkspaceContext owner, Instant now) {
        jdbc.update(
                """
                INSERT INTO booking_execution_outbox (
                    id, plan_id, attempt_id, event_key, event_type,
                    status, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, 'BOOKING_TERMINAL',
                    'PENDING', ?, ?, ?, ?)
                ON CONFLICT (
                    workspace_id, created_by_user_id, event_key)
                DO NOTHING
                """,
                UUID.randomUUID(),
                planId,
                attemptId,
                "booking-terminal:" + planId,
                Timestamp.from(now),
                Timestamp.from(now),
                owner.workspaceId(),
                owner.actorId());
    }

    private void requireExecutablePlan(
            UUID planId, WorkspaceContext owner, ProviderCapability capability) {
        List<PlanAuthorizationRow> plans = jdbc.query(
                """
                SELECT state, authorization_id
                FROM booking_plan
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                FOR UPDATE
                """,
                (result, row) -> new PlanAuthorizationRow(
                        result.getString("state"),
                        result.getObject("authorization_id", UUID.class)),
                planId,
                owner.workspaceId(),
                owner.actorId());
        if (plans.isEmpty()
                || (!plans.getFirst().state().equals(BookingExecutionState.AUTHORIZED.name())
                        && !plans.getFirst()
                                .state()
                                .equals(BookingExecutionState.EXECUTING.name()))) {
            throw new IllegalStateException("plan is not executable");
        }
        PurchaseAuthorization authorization =
                loadAuthorization(plans.getFirst().authorizationId()).orElseThrow();
        OfferSnapshot offer = loadOffer(authorization.offerId()).orElseThrow();
        authorization.assertAllows(
                offer, capability, owner.workspaceId(), owner.actorId(), clock);
    }

    private Optional<ExistingAttempt> findAttemptForUpdate(
            WorkspaceContext owner, String operationId) {
        return jdbc.query(
                        """
                        SELECT *
                        FROM booking_attempt
                        WHERE operation_id = ?
                          AND workspace_id = ? AND created_by_user_id = ?
                        FOR UPDATE
                        """,
                        (result, row) -> mapAttempt(result),
                        operationId,
                        owner.workspaceId(),
                        owner.actorId())
                .stream()
                .findFirst();
    }

    private Optional<ExistingAttempt> findAttemptByIdForUpdate(
            WorkspaceContext owner, UUID attemptId) {
        return jdbc.query(
                        """
                        SELECT *
                        FROM booking_attempt
                        WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                        FOR UPDATE
                        """,
                        (result, row) -> mapAttempt(result),
                        attemptId,
                        owner.workspaceId(),
                        owner.actorId())
                .stream()
                .findFirst();
    }

    private Optional<StoredOrder> findStoredOrderForUpdate(
            WorkspaceContext owner, UUID orderId) {
        return jdbc.query(
                        """
                        SELECT *
                        FROM external_booking_order
                        WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                        FOR UPDATE
                        """,
                        (result, row) -> new StoredOrder(
                                result.getObject("plan_id", UUID.class),
                                mapOrder(result)),
                        orderId,
                        owner.workspaceId(),
                        owner.actorId())
                .stream()
                .findFirst();
    }

    private void assertOrderMatchesAuthorization(
            UUID planId, ExternalBookingOrder order, WorkspaceContext owner) {
        List<OrderBoundary> boundaries = jdbc.query(
                """
                SELECT auth.provider AS authorization_provider,
                       auth.environment AS authorization_environment,
                       offer.provider AS offer_provider,
                       offer.environment AS offer_environment
                FROM booking_plan plan
                JOIN booking_purchase_authorization auth
                  ON auth.id = plan.authorization_id
                 AND auth.workspace_id = plan.workspace_id
                 AND auth.created_by_user_id = plan.created_by_user_id
                JOIN booking_offer_snapshot offer
                  ON offer.id = auth.offer_id
                 AND offer.workspace_id = auth.workspace_id
                 AND offer.created_by_user_id = auth.created_by_user_id
                WHERE plan.id = ?
                  AND plan.workspace_id = ? AND plan.created_by_user_id = ?
                FOR UPDATE OF plan, auth, offer
                """,
                (result, row) -> new OrderBoundary(
                        result.getString("authorization_provider"),
                        ProviderEnvironment.valueOf(
                                result.getString("authorization_environment")),
                        result.getString("offer_provider"),
                        ProviderEnvironment.valueOf(result.getString("offer_environment"))),
                planId,
                owner.workspaceId(),
                owner.actorId());
        if (boundaries.size() != 1) {
            throw new IllegalStateException("plan authorization boundary not found");
        }
        OrderBoundary boundary = boundaries.getFirst();
        if (!boundary.authorizationProvider().equals(order.provider())
                || boundary.authorizationEnvironment() != order.environment()
                || !boundary.offerProvider().equals(order.provider())
                || boundary.offerEnvironment() != order.environment()) {
            throw new IllegalStateException(
                    "provider result crosses authorization provider boundary");
        }
    }

    private static OfferSnapshot mapOffer(ResultSet result) throws SQLException {
        return new OfferSnapshot(
                result.getObject("id", UUID.class),
                result.getString("provider"),
                ProviderEnvironment.valueOf(result.getString("environment")),
                result.getString("inventory_identity"),
                result.getTimestamp("retrieved_at").toInstant(),
                result.getTimestamp("expires_at").toInstant(),
                result.getBigDecimal("total_price"),
                Currency.getInstance(result.getString("currency").trim()),
                decodeUuids(result.getString("traveller_ids")),
                result.getString("terms_fingerprint"),
                result.getBoolean("non_refundable"),
                result.getBoolean("available"),
                decodeCapabilities(result.getString("capabilities")));
    }

    private static PurchaseAuthorization mapAuthorization(ResultSet result)
            throws SQLException {
        return new PurchaseAuthorization(
                result.getObject("id", UUID.class),
                result.getObject("workspace_id", UUID.class),
                result.getObject("created_by_user_id", UUID.class),
                decodeUuids(result.getString("traveller_ids")),
                result.getObject("offer_id", UUID.class),
                result.getString("provider"),
                ProviderEnvironment.valueOf(result.getString("environment")),
                result.getBigDecimal("max_total_price"),
                Currency.getInstance(result.getString("currency").trim()),
                result.getString("terms_fingerprint"),
                result.getBoolean("non_refundable_accepted"),
                result.getTimestamp("expires_at").toInstant(),
                ConfirmationMode.valueOf(result.getString("confirmation_mode")),
                SubstitutionStrength.valueOf(result.getString("substitution_strength")));
    }

    private static ExternalBookingOrder mapOrder(ResultSet result)
            throws SQLException {
        return new ExternalBookingOrder(
                result.getObject("id", UUID.class),
                result.getString("provider"),
                ProviderEnvironment.valueOf(result.getString("environment")),
                result.getString("provider_reference"),
                ExternalBookingOrderStatus.valueOf(result.getString("status")),
                result.getTimestamp("observed_at").toInstant());
    }

    private static ExistingAttempt mapAttempt(ResultSet result) throws SQLException {
        Timestamp leaseUntil = result.getTimestamp("lease_until");
        return new ExistingAttempt(
                result.getObject("id", UUID.class),
                result.getObject("plan_id", UUID.class),
                result.getString("capability"),
                result.getString("dispatch_state"),
                result.getString("outcome_status"),
                result.getObject("order_id", UUID.class),
                result.getObject("claim_token", UUID.class),
                leaseUntil == null ? Instant.EPOCH : leaseUntil.toInstant());
    }

    private static String encodeUuids(Set<UUID> values) {
        TreeSet<String> sorted = new TreeSet<>();
        values.forEach(value -> sorted.add(value.toString()));
        return String.join(",", sorted);
    }

    private static boolean sameOffer(OfferSnapshot left, OfferSnapshot right) {
        return left.offerId().equals(right.offerId())
                && left.provider().equals(right.provider())
                && left.environment() == right.environment()
                && left.inventoryIdentity().equals(right.inventoryIdentity())
                && left.retrievedAt().equals(right.retrievedAt())
                && left.expiresAt().equals(right.expiresAt())
                && left.totalPrice().compareTo(right.totalPrice()) == 0
                && left.currency().equals(right.currency())
                && left.travellerIds().equals(right.travellerIds())
                && left.termsFingerprint().equals(right.termsFingerprint())
                && left.nonRefundable() == right.nonRefundable()
                && left.available() == right.available()
                && left.capabilities().equals(right.capabilities());
    }

    private static boolean sameAuthorization(
            PurchaseAuthorization left, PurchaseAuthorization right) {
        return left.authorizationId().equals(right.authorizationId())
                && left.workspaceId().equals(right.workspaceId())
                && left.actorId().equals(right.actorId())
                && left.travellerIds().equals(right.travellerIds())
                && left.offerId().equals(right.offerId())
                && left.provider().equals(right.provider())
                && left.environment() == right.environment()
                && left.maxTotalPrice().compareTo(right.maxTotalPrice()) == 0
                && left.currency().equals(right.currency())
                && left.termsFingerprint().equals(right.termsFingerprint())
                && left.nonRefundableAccepted() == right.nonRefundableAccepted()
                && left.expiresAt().equals(right.expiresAt())
                && left.confirmationMode() == right.confirmationMode()
                && left.substitutionStrength() == right.substitutionStrength();
    }

    private static boolean sameOrder(
            ExternalBookingOrder left, ExternalBookingOrder right) {
        return left.orderId().equals(right.orderId())
                && left.provider().equals(right.provider())
                && left.environment() == right.environment()
                && left.providerReference().equals(right.providerReference())
                && left.status() == right.status()
                && left.observedAt().truncatedTo(ChronoUnit.MICROS)
                        .equals(right.observedAt().truncatedTo(ChronoUnit.MICROS));
    }

    private static Set<UUID> decodeUuids(String encoded) {
        TreeSet<UUID> values = new TreeSet<>();
        if (encoded != null && !encoded.isBlank()) {
            for (String value : encoded.split(",")) {
                values.add(UUID.fromString(value));
            }
        }
        return Set.copyOf(values);
    }

    private static String encodeCapabilities(Set<ProviderCapability> values) {
        TreeSet<String> sorted = new TreeSet<>();
        values.forEach(value -> sorted.add(value.name()));
        return String.join(",", sorted);
    }

    private static Set<ProviderCapability> decodeCapabilities(String encoded) {
        TreeSet<ProviderCapability> values = new TreeSet<>();
        if (encoded != null && !encoded.isBlank()) {
            for (String value : encoded.split(",")) {
                values.add(ProviderCapability.valueOf(value));
            }
        }
        return Set.copyOf(values);
    }

    private static WorkspaceContext owner() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("tenant actor context is required");
        }
        return context;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public enum DispatchDisposition {
        MAY_DISPATCH,
        LEASE_HELD,
        NEEDS_RECONCILIATION,
        ALREADY_SETTLED
    }

    public enum WebhookDisposition {
        ACCEPTED,
        DUPLICATE,
        OUT_OF_ORDER
    }

    public record OperationClaim(
            UUID attemptId,
            UUID claimToken,
            Instant leaseUntil,
            DispatchDisposition disposition) {
    }

    public record AttemptView(
            UUID attemptId,
            String operationId,
            ProviderCapability capability,
            String dispatchState,
            String outcomeStatus,
            UUID orderId) {
    }

    public record PlanView(
            UUID planId,
            UUID authorizationId,
            int totalItems,
            BookingExecutionState state,
            List<AttemptView> attempts,
            List<ExternalBookingOrder> orders) {

        public PlanView {
            attempts = List.copyOf(attempts);
            orders = List.copyOf(orders);
        }
    }

    public record TerminalClaim(
            UUID outboxId,
            UUID planId,
            String eventKey,
            String eventType,
            UUID claimToken,
            Instant claimUntil) {
    }

    private record ExistingAttempt(
            UUID attemptId,
            UUID planId,
            String capability,
            String dispatchState,
            String outcomeStatus,
            UUID orderId,
            UUID claimToken,
            Instant leaseUntil) {
    }

    private record StoredOrder(UUID planId, ExternalBookingOrder order) {}

    private record OrderBoundary(
            String authorizationProvider,
            ProviderEnvironment authorizationEnvironment,
            String offerProvider,
            ProviderEnvironment offerEnvironment) {}

    private record PlanRow(
            UUID planId,
            UUID authorizationId,
            int totalItems,
            BookingExecutionState state) {
    }

    private record PlanAuthorizationRow(String state, UUID authorizationId) {
    }

    private record PlanSeed(UUID authorizationId, int totalItems) {
    }

    private record WebhookRow(
            String payloadDigest, String eventType, long providerVersion) {
    }
}
