package com.aproject.aidriven.mymobilesecretary.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationChannel;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationSender;
import com.aproject.aidriven.mymobilesecretary.integration.notification.ReminderNotification;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleItemRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * pending 空閒詢問的四道放行條件測試。
 */
@ExtendWith(MockitoExtension.class)
class PendingPromptServiceTest {

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

    /** 記下收到通知的假 sender。 */
    private static class RecordingSender implements NotificationSender {
        final List<ReminderNotification> received = new ArrayList<>();

        @Override
        public NotificationChannel channel() {
            return NotificationChannel.LOG;
        }

        @Override
        public void send(ReminderNotification notification) {
            received.add(notification);
        }
    }

    private final RecordingSender sender = new RecordingSender();

    private PendingPromptService service(Instant now) {
        return new PendingPromptService(
                scheduleItemRepository, List.of(sender), redis,
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
        assertThat(sender.received).hasSize(1);
        assertThat(sender.received.get(0).message()).contains("待安排事項（2 件）").contains("剪頭髮");
    }

    @Test
    void emptyPendingPoolDoesNotPrompt() {
        pendingPool();

        assertThat(service(DAYTIME).promptIfIdle()).isFalse();
        assertThat(sender.received).isEmpty();
    }

    /** 安靜時段(23:00 台北)不吵人。 */
    @Test
    void quietHoursSuppressPrompt() {
        pendingPool(pendingItem("剪頭髮"));

        assertThat(service(NIGHT).promptIfIdle()).isFalse();
        assertThat(sender.received).isEmpty();
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
        assertThat(sender.received).isEmpty();
    }
}
