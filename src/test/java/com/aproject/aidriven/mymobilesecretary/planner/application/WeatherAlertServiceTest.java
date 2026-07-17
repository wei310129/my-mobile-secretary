package com.aproject.aidriven.mymobilesecretary.planner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationPublisher;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationRequest;
import com.aproject.aidriven.mymobilesecretary.integration.weather.CwaWeatherClient;
import com.aproject.aidriven.mymobilesecretary.integration.weather.WeatherForecast;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleItemRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 天氣主動提醒規則測試:雨天帶傘一天一次、大雨+行程才問調整、
 * 夜間不吵、天氣查詢失敗不燒當日額度。
 */
@ExtendWith(MockitoExtension.class)
class WeatherAlertServiceTest {

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

    /** 2026-07-15 10:00 台北時間(提醒時窗內)。 */
    private static final Instant DAYTIME = Instant.parse("2026-07-15T02:00:00Z");
    /** 2026-07-15 23:00 台北時間(時窗外)。 */
    private static final Instant NIGHT = Instant.parse("2026-07-15T15:00:00Z");

    @Mock
    private CwaWeatherClient weatherClient;
    @Mock
    private ScheduleItemRepository scheduleItemRepository;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private NotificationPublisher notificationPublisher;

    private WeatherAlertService service(Instant now) {
        return new WeatherAlertService(weatherClient, scheduleItemRepository,
                notificationPublisher, redis,
                new WeatherRuleProperties(true, "新北市", 60, 34),
                new WeatherAlertProperties(LocalTime.of(7, 0), LocalTime.of(21, 0),
                        80, Duration.ofHours(6)),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private void forecast(int rainProbability) {
        when(weatherClient.getForecast("新北市")).thenReturn(
                new WeatherForecast("新北市", "短暫陣雨", rainProbability, 26, 31));
    }

    private void redisAllows(boolean allowed) {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(allowed);
    }

    @Test
    void rainyDaySendsUmbrellaReminderWithProbability() {
        forecast(70);
        redisAllows(true);

        assertThat(service(DAYTIME).remindUmbrellaIfRainy()).isTrue();
        ArgumentCaptor<NotificationRequest> captured = ArgumentCaptor.forClass(NotificationRequest.class);
        org.mockito.Mockito.verify(notificationPublisher).enqueue(captured.capture());
        assertThat(captured.getValue().message()).contains("帶傘").contains("70%").contains("短暫陣雨");
    }

    /** 一天只提醒一次:Redis key 已存在 → 不重複。 */
    @Test
    void umbrellaReminderOnlyOncePerDay() {
        forecast(70);
        redisAllows(false);

        assertThat(service(DAYTIME).remindUmbrellaIfRainy()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(notificationPublisher);
    }

    @Test
    void dryDayStaysQuiet() {
        forecast(30);

        assertThat(service(DAYTIME).remindUmbrellaIfRainy()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(notificationPublisher);
    }

    /** 夜間(23:00)不吵人,天氣也不用查。 */
    @Test
    void nightTimeStaysQuiet() {
        assertThat(service(NIGHT).remindUmbrellaIfRainy()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(weatherClient);
    }

    /** 天氣查詢失敗 → 本輪跳過且不燒當日額度(Redis 不該被碰)。 */
    @Test
    void forecastFailureSkipsWithoutBurningQuota() {
        when(weatherClient.getForecast("新北市")).thenThrow(new IllegalStateException("CWA down"));

        assertThat(service(DAYTIME).remindUmbrellaIfRainy()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(redis);
    }

    /** 大雨(85%)且 6 小時內有已確認行程 → 主動問要不要調整。 */
    @Test
    void heavyRainWithUpcomingScheduleAsksForAdjustment() {
        forecast(85);
        redisAllows(true);
        ScheduleItem item = ScheduleItem.propose("回診",
                DAYTIME.plus(Duration.ofHours(2)), DAYTIME.plus(Duration.ofHours(3)), null, DAYTIME);
        item.confirm(DAYTIME);
        when(scheduleItemRepository.findByStatusOrderByStartAtAsc(ScheduleStatus.CONFIRMED))
                .thenReturn(List.of(item));

        assertThat(service(DAYTIME).askScheduleAdjustmentIfHeavyRain()).isTrue();
        ArgumentCaptor<NotificationRequest> captured = ArgumentCaptor.forClass(NotificationRequest.class);
        org.mockito.Mockito.verify(notificationPublisher).enqueue(captured.capture());
        assertThat(captured.getValue().message())
                .contains("回診").contains("85%").contains("要調整行程嗎");
    }

    /** 大雨但接下來沒行程 → 沒什麼好調整,不問。 */
    @Test
    void heavyRainWithoutScheduleStaysQuiet() {
        forecast(85);
        when(scheduleItemRepository.findByStatusOrderByStartAtAsc(ScheduleStatus.CONFIRMED))
                .thenReturn(List.of());

        assertThat(service(DAYTIME).askScheduleAdjustmentIfHeavyRain()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(notificationPublisher);
    }

    /** 普通雨(70%<80%)不觸發調整詢問(帶傘提醒管這段)。 */
    @Test
    void moderateRainDoesNotAskForAdjustment() {
        forecast(70);
        lenient().when(scheduleItemRepository.findByStatusOrderByStartAtAsc(any()))
                .thenReturn(List.of());

        assertThat(service(DAYTIME).askScheduleAdjustmentIfHeavyRain()).isFalse();
    }
}
