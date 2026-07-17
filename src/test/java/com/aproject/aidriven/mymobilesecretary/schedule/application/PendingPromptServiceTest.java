package com.aproject.aidriven.mymobilesecretary.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationPublisher;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationRequest;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleItemRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * pending 空閒詢問的四道放行條件測試。
 */
@ExtendWith(MockitoExtension.class)
class PendingPromptServiceTest {

    private WorkspaceContextHolder.Scope workspaceScope;

    @BeforeEach
    void openWorkspaceScope() {
        workspaceScope = WorkspaceContextHolder.open(new WorkspaceContext(
                LegacyAccountIds.USER_ID, LegacyAccountIds.WORKSPACE_ID, WorkspaceChannel.TEST));
    }

    @AfterEach
    void closeWorkspaceScope() {
        workspaceScope.close();
    }

    /** 2026-07-12 10:00 台北時間(白天,可詢問時段)。 */
    private static final Instant DAYTIME = Instant.parse("2026-07-12T02:00:00Z");
    /** 2026-07-12 23:00 台北時間(安靜時段)。 */
    private static final Instant NIGHT = Instant.parse("2026-07-12T15:00:00Z");

    @Mock
    private ScheduleItemRepository scheduleItemRepository;

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private NotificationPublisher notificationPublisher;

    private PendingPromptService service(Instant now) {
        return new PendingPromptService(
                scheduleItemRepository, notificationPublisher, redis,
                new PendingPromptProperties(Duration.ofHours(1), Duration.ofHours(4),
                        LocalTime.of(8, 0), LocalTime.of(21, 0)),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private void pendingPool(ScheduleItem... items) {
        when(scheduleItemRepository.findByStatusOrderByStartAtAsc(ScheduleStatus.PENDING))
                .thenReturn(List.of(items));
    }

    private ScheduleItem pendingItem(String title) {
        ScheduleItem item = ScheduleItem.propose(title, DAYTIME, DAYTIME.plusSeconds(3600), null, DAYTIME);
        item.park(DAYTIME);
        return item;
    }

    @Test
    void promptsWhenAllGatesPass() {
        pendingPool(pendingItem("剪頭髮"), pendingItem("回媽媽家"));
        when(scheduleItemRepository.existsByStatusAndStartAtLessThanAndEndAtGreaterThan(
                any(), any(), any())).thenReturn(false);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);

        boolean prompted = service(DAYTIME).promptIfIdle();

        assertThat(prompted).isTrue();
        ArgumentCaptor<NotificationRequest> captured = ArgumentCaptor.forClass(NotificationRequest.class);
        org.mockito.Mockito.verify(notificationPublisher).enqueue(captured.capture());
        assertThat(captured.getValue().message()).contains("待安排事項（2 件）").contains("剪頭髮");
    }

    @Test
    void emptyPendingPoolDoesNotPrompt() {
        pendingPool();

        assertThat(service(DAYTIME).promptIfIdle()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(notificationPublisher);
    }

    /** 安靜時段(23:00 台北)不吵人。 */
    @Test
    void quietHoursSuppressPrompt() {
        pendingPool(pendingItem("剪頭髮"));

        assertThat(service(NIGHT).promptIfIdle()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(notificationPublisher);
    }

    /** 未來一小時內有已確認行程 → 不算空閒。 */
    @Test
    void busyWindowSuppressesPrompt() {
        pendingPool(pendingItem("剪頭髮"));
        when(scheduleItemRepository.existsByStatusAndStartAtLessThanAndEndAtGreaterThan(
                any(), any(), any())).thenReturn(true);

        assertThat(service(DAYTIME).promptIfIdle()).isFalse();
    }

    /** 4 小時內問過(Redis key 還在)→ 不重複問。 */
    @Test
    void recentPromptSuppressesRepeat() {
        pendingPool(pendingItem("剪頭髮"));
        when(scheduleItemRepository.existsByStatusAndStartAtLessThanAndEndAtGreaterThan(
                any(), any(), any())).thenReturn(false);
        when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);

        assertThat(service(DAYTIME).promptIfIdle()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(notificationPublisher);
    }
}
