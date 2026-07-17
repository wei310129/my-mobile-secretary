package com.aproject.aidriven.mymobilesecretary.planner.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.TenantRedisKeys;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 天氣主動提醒(使用者 2026-07-15 拍板的兩條規則):
 * 1. 當天會下雨 → 出門時段主動提醒帶傘,附降雨機率與天氣現象。
 *    (雨量需另接氣象署觀測資料集,v1 先用機率+現象。)
 * 2. 大雨風險(機率≧heavyRainThreshold)且接下來有已確認行程 → 主動問要不要調整。
 *
 * 防騷擾:兩種提醒各自一天最多一次(Redis SETNX + TTL,台北時間換日重算);
 * 天氣查詢失敗直接跳過本輪、不燒掉當日額度(可靠度 > 聰明度)。
 */
@Service
public class WeatherAlertService {

    private static final Logger log = LoggerFactory.getLogger(WeatherAlertService.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    static final String UMBRELLA_KEY_PREFIX = "weather:umbrella:";
    static final String ADJUST_KEY_PREFIX = "weather:adjust:";

    private final CwaWeatherClient weatherClient;
    private final ScheduleItemRepository scheduleItemRepository;
    private final NotificationPublisher notificationPublisher;
    private final StringRedisTemplate redis;
    private final WeatherRuleProperties weatherProperties;
    private final WeatherAlertProperties alertProperties;
    private final Clock clock;

    public WeatherAlertService(CwaWeatherClient weatherClient,
                               ScheduleItemRepository scheduleItemRepository,
                               NotificationPublisher notificationPublisher,
                               StringRedisTemplate redis,
                               WeatherRuleProperties weatherProperties,
                               WeatherAlertProperties alertProperties,
                               Clock clock) {
        this.weatherClient = weatherClient;
        this.scheduleItemRepository = scheduleItemRepository;
        this.notificationPublisher = notificationPublisher;
        this.redis = redis;
        this.weatherProperties = weatherProperties;
        this.alertProperties = alertProperties;
        this.clock = clock;
    }

    /**
     * 帶傘提醒:當天降雨機率過門檻 → 提醒一次。
     *
     * @return true 表示這次真的發了(測試與除錯用)
     */
    public boolean remindUmbrellaIfRainy() {
        Instant now = Instant.now(clock);
        if (!withinAlertWindow(now)) {
            return false;
        }
        Optional<WeatherForecast> forecast = fetchForecast();
        if (forecast.isEmpty()
                || forecast.get().rainProbabilityPercent() < weatherProperties.rainProbabilityThreshold()) {
            return false;
        }
        if (!acquireDailyOnce(UMBRELLA_KEY_PREFIX, now)) {
            return false;
        }
        WeatherForecast f = forecast.get();
        send("天氣出門提醒", "今日天氣:\n%s（%s）\n降雨機率 %d%%\n\n出門提醒:\n記得帶傘"
                .formatted(f.description(), f.county(), f.rainProbabilityPercent()));
        return true;
    }

    /**
     * 大雨調整行程詢問:機率≧heavyRainThreshold 且 lookahead 內有已確認行程 → 問一次。
     *
     * @return true 表示這次真的問了
     */
    public boolean askScheduleAdjustmentIfHeavyRain() {
        Instant now = Instant.now(clock);
        if (!withinAlertWindow(now)) {
            return false;
        }
        Optional<WeatherForecast> forecast = fetchForecast();
        if (forecast.isEmpty()
                || forecast.get().rainProbabilityPercent() < alertProperties.heavyRainThreshold()) {
            return false;
        }
        Instant lookaheadEnd = now.plus(alertProperties.scheduleLookahead());
        List<ScheduleItem> upcoming = scheduleItemRepository
                .findByStatusOrderByStartAtAsc(ScheduleStatus.CONFIRMED).stream()
                .filter(item -> item.getStartAt().isAfter(now) && item.getStartAt().isBefore(lookaheadEnd))
                .limit(3)
                .toList();
        if (upcoming.isEmpty()) {
            return false;
        }
        if (!acquireDailyOnce(ADJUST_KEY_PREFIX, now)) {
            return false;
        }
        WeatherForecast f = forecast.get();
        String schedules = upcoming.stream()
                .map(item -> "「%s」(%s)".formatted(item.getTitle(),
                        ZonedDateTime.ofInstant(item.getStartAt(), TAIPEI).format(TIME)))
                .reduce((a, b) -> a + "\n" + b).orElse("");
        send("天氣警示", "天氣狀況:\n%s\n降雨機率 %d%%\n%s\n\n接下來的行程:\n%s\n\n要調整行程嗎?"
                .formatted(f.county(), f.rainProbabilityPercent(), f.description(), schedules));
        return true;
    }

    /** 提醒時窗(預設 07:00-21:00 台北時間):太早太晚都不吵人。 */
    private boolean withinAlertWindow(Instant now) {
        LocalTime local = LocalTime.ofInstant(now, TAIPEI);
        return !local.isBefore(alertProperties.start()) && local.isBefore(alertProperties.end());
    }

    /** 天氣查詢;失敗回 empty(本輪跳過,不燒當日額度)。 */
    private Optional<WeatherForecast> fetchForecast() {
        if (!weatherProperties.enabled()) {
            return Optional.empty();
        }
        try {
            return Optional.of(weatherClient.getForecast(weatherProperties.county()));
        } catch (Exception e) {
            log.warn("Weather alert skipped: forecast unavailable", e);
            return Optional.empty();
        }
    }

    /** 一天一次:SETNX 台北日期 key,TTL 36 小時跨日自動失效。 */
    private boolean acquireDailyOnce(String prefix, Instant now) {
        String key = TenantRedisKeys.current(prefix + LocalDate.ofInstant(now, TAIPEI));
        return Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent(key, now.toString(), Duration.ofHours(36)));
    }

    /** 寫入可靠 outbox；實際通道送出由背景 worker 完成。 */
    private void send(String title, String message) {
        Instant now = Instant.now(clock);
        String deliveryKey = "weather:" + LocalDate.ofInstant(now, TAIPEI) + ":" + title;
        notificationPublisher.enqueue(new NotificationRequest(
                null, deliveryKey, null, null, title, message));
        log.info("Weather alert queued [kind={}]", title.equals("天氣警示") ? "adjust" : "umbrella");
    }
}
