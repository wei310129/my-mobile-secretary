package com.aproject.aidriven.mymobilesecretary.booking.provider.fake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.booking.domain.AuthorizationViolation;
import com.aproject.aidriven.mymobilesecretary.booking.domain.BookingExecutionState;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ConfirmationMode;
import com.aproject.aidriven.mymobilesecretary.booking.domain.OfferSnapshot;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ProviderCapability;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ProviderEnvironment;
import com.aproject.aidriven.mymobilesecretary.booking.domain.PurchaseAuthorization;
import com.aproject.aidriven.mymobilesecretary.booking.domain.SubstitutionStrength;
import com.aproject.aidriven.mymobilesecretary.booking.execution.FakePurchaseCommand;
import com.aproject.aidriven.mymobilesecretary.booking.execution.FakePurchaseOrchestrator;
import com.aproject.aidriven.mymobilesecretary.booking.persistence.BookingExecutionStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class FakePurchaseOrchestratorIntegrationTest extends IntegrationTestBase {

    @Autowired private FakePurchaseOrchestrator orchestrator;
    @Autowired private BookingExecutionStore store;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    @ParameterizedTest
    @MethodSource("authorizationPolicies")
    void allConfirmationAndSubstitutionPoliciesExecuteExactlyOnce(
            ConfirmationMode confirmationMode, SubstitutionStrength substitutionStrength) {
        Fixture fixture = fixture(confirmationMode, substitutionStrength, 1);
        FakeBookingProvider provider = provider(fixture);
        assertFakeEnvironment(fixture);

        var result = inContext(
                fixture,
                () -> orchestrator.execute(command(fixture, "book-policy"), provider));
        var replay = inContext(
                fixture,
                () -> orchestrator.execute(command(fixture, "book-policy"), provider));

        assertThat(result.state()).isEqualTo(BookingExecutionState.COMPLETED);
        assertThat(replay.state()).isEqualTo(BookingExecutionState.COMPLETED);
        assertThat(provider.externalMutationCount()).isEqualTo(1);
        assertThat(fixture.authorization().confirmationMode()).isEqualTo(confirmationMode);
        assertThat(fixture.authorization().substitutionStrength())
                .isEqualTo(substitutionStrength);
    }

    @Test
    void materialQuoteChangeFailsBeforeClaimOrMutation() {
        Fixture fixture =
                fixture(ConfirmationMode.PER_ITEM, SubstitutionStrength.EXACT, 1);
        FakeBookingProvider provider = provider(fixture);
        assertFakeEnvironment(fixture);
        provider.replaceOffer(new OfferSnapshot(
                fixture.offer().offerId(),
                fixture.offer().provider(),
                fixture.offer().environment(),
                fixture.offer().inventoryIdentity(),
                fixture.offer().retrievedAt(),
                fixture.offer().expiresAt(),
                fixture.offer().totalPrice().add(BigDecimal.ONE),
                fixture.offer().currency(),
                fixture.offer().travellerIds(),
                fixture.offer().termsFingerprint(),
                fixture.offer().nonRefundable(),
                true,
                fixture.offer().capabilities()));

        assertThatThrownBy(() -> inContext(
                        fixture,
                        () -> orchestrator.execute(command(fixture, "book-changed"), provider)))
                .isInstanceOf(AuthorizationViolation.class);

        assertThat(provider.externalMutationCount()).isZero();
        assertThat(inContext(fixture, () -> store.loadPlan(fixture.planId()).orElseThrow().state()))
                .isEqualTo(BookingExecutionState.AUTHORIZED);
    }

    @Test
    void inventoryChangeFailsBeforeClaimOrMutation() {
        Fixture fixture =
                fixture(ConfirmationMode.PER_ITEM, SubstitutionStrength.EXACT, 1);
        FakeBookingProvider provider = provider(fixture);
        assertFakeEnvironment(fixture);
        provider.replaceOffer(new OfferSnapshot(
                fixture.offer().offerId(),
                fixture.offer().provider(),
                fixture.offer().environment(),
                "different-inventory",
                fixture.offer().retrievedAt(),
                fixture.offer().expiresAt(),
                fixture.offer().totalPrice(),
                fixture.offer().currency(),
                fixture.offer().travellerIds(),
                fixture.offer().termsFingerprint(),
                fixture.offer().nonRefundable(),
                true,
                fixture.offer().capabilities()));

        assertThatThrownBy(() -> inContext(
                        fixture,
                        () -> orchestrator.execute(command(fixture, "book-inventory"), provider)))
                .isInstanceOf(SecurityException.class);

        assertThat(provider.externalMutationCount()).isZero();
        assertThat(inContext(fixture, () -> store.loadPlan(fixture.planId()).orElseThrow().state()))
                .isEqualTo(BookingExecutionState.AUTHORIZED);
    }

    @Test
    void unknownAfterSendReconcilesWithoutResending() {
        Fixture fixture = fixture(ConfirmationMode.BATCH, SubstitutionStrength.EQUIVALENT, 1);
        FakeBookingProvider provider = provider(fixture);
        assertFakeEnvironment(fixture);
        provider.script(
                operationId(fixture, "book-unknown"),
                FakeMutationBehavior.UNKNOWN_AFTER_SEND);

        var first = inContext(
                fixture,
                () -> orchestrator.execute(command(fixture, "book-unknown"), provider));
        var reconciled = inContext(
                fixture,
                () -> orchestrator.execute(command(fixture, "book-unknown"), provider));

        assertThat(first.state()).isEqualTo(BookingExecutionState.NEEDS_RECONCILIATION);
        assertThat(reconciled.state()).isEqualTo(BookingExecutionState.COMPLETED);
        assertThat(provider.externalMutationCount()).isEqualTo(1);
    }

    @Test
    void partialFailurePreservesSuccessAndOnlyProducesProposals() {
        Fixture fixture =
                fixture(ConfirmationMode.POLICY_BOUNDED, SubstitutionStrength.GOAL_DIRECTED, 2);
        FakeBookingProvider provider = provider(fixture);
        assertFakeEnvironment(fixture);

        var first = inContext(
                fixture,
                () -> orchestrator.execute(command(fixture, "book-first"), provider));
        provider.script(
                operationId(fixture, "book-second"),
                FakeMutationBehavior.FAIL_BEFORE_SEND);
        var second = inContext(
                fixture,
                () -> orchestrator.execute(command(fixture, "book-second"), provider));

        assertThat(first.state()).isEqualTo(BookingExecutionState.EXECUTING);
        assertThat(second.state()).isEqualTo(BookingExecutionState.PARTIALLY_COMPLETED);
        assertThat(second.cancellationProposalAvailable()).isTrue();
        assertThat(second.replacementProposalAvailable()).isTrue();
        assertThat(provider.externalMutationCount()).isEqualTo(1);
        assertThat(inContext(fixture, () -> store.loadPlan(fixture.planId()).orElseThrow().orders()))
                .hasSize(1);
    }

    private Fixture fixture(
            ConfirmationMode confirmationMode,
            SubstitutionStrength substitutionStrength,
            int totalItems) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID travellerId = UUID.randomUUID();
        seed(actorId, workspaceId);
        OfferSnapshot offer = new OfferSnapshot(
                UUID.randomUUID(),
                "fake-air",
                ProviderEnvironment.FAKE,
                "inventory-" + UUID.randomUUID(),
                now.minusSeconds(1),
                now.plus(Duration.ofHours(1)),
                new BigDecimal("100.00"),
                Currency.getInstance("USD"),
                Set.of(travellerId),
                "terms-v1",
                false,
                true,
                Set.of(ProviderCapability.BOOK));
        PurchaseAuthorization authorization = new PurchaseAuthorization(
                UUID.randomUUID(),
                workspaceId,
                actorId,
                Set.of(travellerId),
                offer.offerId(),
                offer.provider(),
                ProviderEnvironment.FAKE,
                offer.totalPrice(),
                offer.currency(),
                offer.termsFingerprint(),
                false,
                now.plus(Duration.ofMinutes(30)),
                confirmationMode,
                substitutionStrength);
        Fixture fixture =
                new Fixture(actorId, workspaceId, offer, authorization, UUID.randomUUID());
        inContext(fixture, () -> {
            store.saveOffer(offer);
            store.saveAuthorization(authorization);
            store.createAuthorizedPlan(
                    fixture.planId(), authorization.authorizationId(), totalItems);
            return null;
        });
        return fixture;
    }

    private FakeBookingProvider provider(Fixture fixture) {
        FakeBookingProvider provider = new FakeBookingProvider("fake-air", clock);
        provider.registerOffer(fixture.offer());
        return provider;
    }

    private static FakePurchaseCommand command(Fixture fixture, String operationId) {
        return new FakePurchaseCommand(
                fixture.planId(),
                fixture.authorization().authorizationId(),
                operationId(fixture, operationId),
                fixture.workspaceId(),
                fixture.actorId());
    }

    private static String operationId(Fixture fixture, String label) {
        return label + ":" + fixture.planId();
    }

    private static void assertFakeEnvironment(Fixture fixture) {
        assertThat(fixture.offer().environment()).isEqualTo(ProviderEnvironment.FAKE);
        assertThat(fixture.authorization().environment()).isEqualTo(ProviderEnvironment.FAKE);
    }

    private void seed(UUID actorId, UUID workspaceId) {
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'b4-fake', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actorId);
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'b4-fake', 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspaceId,
                actorId);
    }

    private <T> T inContext(Fixture fixture, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(
                        fixture.actorId(), fixture.workspaceId(), WorkspaceChannel.TEST))) {
            return work.get();
        }
    }

    private static Stream<Arguments> authorizationPolicies() {
        return Stream.of(ConfirmationMode.values())
                .flatMap(mode -> Stream.of(SubstitutionStrength.values())
                        .map(strength -> Arguments.of(mode, strength)));
    }

    private record Fixture(
            UUID actorId,
            UUID workspaceId,
            OfferSnapshot offer,
            PurchaseAuthorization authorization,
            UUID planId) {}
}
