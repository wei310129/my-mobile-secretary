package com.aproject.aidriven.mymobilesecretary.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.geo.domain.LocationExitRecorded;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationChannel;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationSender;
import com.aproject.aidriven.mymobilesecretary.integration.notification.ReminderNotification;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.FollowUpStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleFollowUp;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleFollowUpRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleItemRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleOutcomeRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 追蹤詢問服務的規則測試(mock 隔離):
 * 日上限擋發問、GPS 離開的建立/提前、自然語言回報找不到對象。
 * 完整閉環走真實 DB 的測試在 ScheduleFollowUpFlowTest。
 */
@ExtendWith(MockitoExtension.class)
class ScheduleFollowUpServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-14T02:00:00Z");

    @Mock
    private ScheduleItemRepository scheduleItemRepository;
    @Mock
    private ScheduleFollowUpRepository followUpRepository;
    @Mock
    private ScheduleOutcomeRepository outcomeRepository;
    @Mock
    private PlaceRepository placeRepository;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

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

    private ScheduleFollowUpService service() {
        return new ScheduleFollowUpService(
                scheduleItemRepository, followUpRepository, outcomeRepository, placeRepository,
                List.of(sender), redis, eventPublisher,
                new FollowUpProperties(Duration.ofMinutes(15), Duration.ofMinutes(5),
                        50, Duration.ofHours(24), 200),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ScheduleItem confirmedItem(String title) {
        ScheduleItem item = ScheduleItem.propose(title, NOW.minusSeconds(7200), NOW.minusSeconds(1800), null, NOW);
        item.confirm(NOW);
        return item;
    }

    /** 到期詢問正常發出:通知送達、狀態轉 ASKED。 */
    @Test
    void asksDueFollowUpAndMarksAsked() {
        ScheduleFollowUp due = ScheduleFollowUp.planAt(1L, NOW.minusSeconds(60), NOW.minusSeconds(600));
        when(followUpRepository.findByStatusAndDueAtLessThanEqualOrderByDueAtAsc(any(), any()))
                .thenReturn(List.of(due));
        when(scheduleItemRepository.findById(1L)).thenReturn(Optional.of(confirmedItem("跟客戶開會")));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(any())).thenReturn(1L);
        when(redis.expire(any(), any(Duration.class))).thenReturn(true);

        int asked = service().askDueFollowUps();

        assertThat(asked).isEqualTo(1);
        assertThat(due.getStatus()).isEqualTo(FollowUpStatus.ASKED);
        assertThat(sender.received).hasSize(1);
        assertThat(sender.received.get(0).message()).contains("跟客戶開會");
    }

    /** 日上限(50)已滿 → 不發、留在 SCHEDULED 隔天再發,不丟失。 */
    @Test
    void dailyLimitDefersPrompts() {
        ScheduleFollowUp due = ScheduleFollowUp.planAt(1L, NOW.minusSeconds(60), NOW.minusSeconds(600));
        when(followUpRepository.findByStatusAndDueAtLessThanEqualOrderByDueAtAsc(any(), any()))
                .thenReturn(List.of(due));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(any())).thenReturn(51L);

        int asked = service().askDueFollowUps();

        assertThat(asked).isZero();
        assertThat(due.getStatus()).isEqualTo(FollowUpStatus.SCHEDULED);
        assertThat(sender.received).isEmpty();
    }

    /** GPS 離開行程地點 → 對進行中的行程建立 exitAt+5 分鐘的詢問。 */
    @Test
    void locationExitPlansFollowUpFiveMinutesAfterExit() {
        Place place = mock(Place.class);
        when(place.getId()).thenReturn(7L);
        when(placeRepository.findWithinRadius(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(place));
        ScheduleItem item = mock(ScheduleItem.class);
        when(item.getId()).thenReturn(3L);
        when(scheduleItemRepository
                .findByStatusAndPlaceIdInAndStartAtLessThanEqualAndEndAtGreaterThanEqual(
                        any(), anyList(), any(), any()))
                .thenReturn(List.of(item));
        when(followUpRepository.findByScheduleItemId(3L)).thenReturn(Optional.empty());

        Instant exitAt = NOW.minusSeconds(30);
        service().onLocationExit(new LocationExitRecorded(24.98, 121.54, exitAt));

        org.mockito.ArgumentCaptor<ScheduleFollowUp> captor =
                org.mockito.ArgumentCaptor.forClass(ScheduleFollowUp.class);
        org.mockito.Mockito.verify(followUpRepository).save(captor.capture());
        assertThat(captor.getValue().getDueAt()).isEqualTo(exitAt.plusSeconds(300));
    }

    /** 已有較晚的時間路徑排定 → GPS 離開把詢問提前。 */
    @Test
    void locationExitAdvancesExistingFollowUp() {
        Place place = mock(Place.class);
        when(place.getId()).thenReturn(7L);
        when(placeRepository.findWithinRadius(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(place));
        ScheduleItem item = mock(ScheduleItem.class);
        when(item.getId()).thenReturn(3L);
        when(scheduleItemRepository
                .findByStatusAndPlaceIdInAndStartAtLessThanEqualAndEndAtGreaterThanEqual(
                        any(), anyList(), any(), any()))
                .thenReturn(List.of(item));
        ScheduleFollowUp existing = ScheduleFollowUp.planAt(3L, NOW.plusSeconds(3600), NOW);
        when(followUpRepository.findByScheduleItemId(3L)).thenReturn(Optional.of(existing));

        Instant exitAt = NOW.minusSeconds(30);
        service().onLocationExit(new LocationExitRecorded(24.98, 121.54, exitAt));

        assertThat(existing.getDueAt()).isEqualTo(exitAt.plusSeconds(300));
    }

    /** 離開點不在任何已知地點半徑內 → 什麼都不做。 */
    @Test
    void exitOutsideKnownPlacesDoesNothing() {
        when(placeRepository.findWithinRadius(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of());

        service().onLocationExit(new LocationExitRecorded(0, 0, NOW));

        org.mockito.Mockito.verifyNoInteractions(followUpRepository);
    }

    /** 自然語言回報但目前沒有等待回報的詢問 → empty,由呼叫端回問。 */
    @Test
    void recordForLatestAskedReturnsEmptyWhenNothingAsked() {
        when(followUpRepository.findFirstByStatusOrderByAskedAtDescIdDesc(FollowUpStatus.ASKED))
                .thenReturn(Optional.empty());

        assertThat(service().recordOutcomeForLatestAsked(true, null, null, "準時結束")).isEmpty();
    }
}
