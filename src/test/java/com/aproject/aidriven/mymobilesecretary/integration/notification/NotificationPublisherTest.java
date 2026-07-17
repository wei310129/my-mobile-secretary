package com.aproject.aidriven.mymobilesecretary.integration.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class NotificationPublisherTest {

    private static final UUID ACTOR = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID TARGET = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID WORKSPACE = UUID.fromString("30000000-0000-0000-0000-000000000101");
    private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");

    @Mock private NotificationSender sender;
    @Mock private JdbcTemplate jdbcTemplate;
    private WorkspaceContextHolder.Scope scope;

    @BeforeEach
    void setUp() {
        scope = WorkspaceContextHolder.open(
                new WorkspaceContext(ACTOR, WORKSPACE, WorkspaceChannel.TEST));
    }

    @AfterEach
    void tearDown() {
        scope.close();
    }

    @Test
    void persistsExplicitTenantRecipientDestinationAndStableDeliveryId() {
        when(sender.channel()).thenReturn(NotificationChannel.LOG);
        when(sender.destinationFor(WORKSPACE, TARGET)).thenReturn(Optional.of("server-log"));
        when(sender.supportsStableDeliveryId()).thenReturn(true);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        NotificationPublisher publisher = publisher();

        int inserted = publisher.enqueue(new NotificationRequest(
                TARGET, "task:7:due", 9L, 7L, "提醒", "該出門了"));

        assertThat(inserted).isEqualTo(1);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), arguments.capture());
        Object[] values = arguments.getValue();
        assertThat(values[0]).isEqualTo(WORKSPACE);
        assertThat(values[1]).isEqualTo(ACTOR);
        assertThat(values[2]).isEqualTo(NotificationPublisher.stableDeliveryId(
                WORKSPACE, "task:7:due", "LOG"));
        assertThat(values[4]).isEqualTo(TARGET);
        assertThat(values[5]).isEqualTo("LOG");
        assertThat(values[6]).isEqualTo("server-log");
        assertThat(values[11]).isEqualTo(true);
    }

    @Test
    void missingDestinationFailsClosedWithoutCreatingAnOutboxRow() {
        when(sender.channel()).thenReturn(NotificationChannel.APNS);
        when(sender.destinationFor(WORKSPACE, TARGET)).thenReturn(Optional.empty());

        assertThat(publisher().enqueue(new NotificationRequest(
                TARGET, "task:7:due", null, 7L, "提醒", "該出門了"))).isZero();

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void persistenceFailureEscapesSoCallerTransactionCanRollback() {
        when(sender.channel()).thenReturn(NotificationChannel.LOG);
        when(sender.destinationFor(WORKSPACE, TARGET)).thenReturn(Optional.of("server-log"));
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> publisher().enqueue(new NotificationRequest(
                TARGET, "task:7:due", null, 7L, "提醒", "該出門了")))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    private NotificationPublisher publisher() {
        return new NotificationPublisher(List.of(sender), jdbcTemplate,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
