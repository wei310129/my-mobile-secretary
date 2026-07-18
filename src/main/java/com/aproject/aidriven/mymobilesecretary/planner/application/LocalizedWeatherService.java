package com.aproject.aidriven.mymobilesecretary.planner.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.geo.domain.LocationEvent;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.LocationEventRepository;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 依進入管道與近期裝置位置決定天氣精度；LINE 永不假裝擁有即時 GPS。 */
@Service
public class LocalizedWeatherService {

    private static final Duration LOCATION_MAX_AGE = Duration.ofMinutes(30);
    private static final Pattern ADMIN_AREA = Pattern.compile(
            "(?<county>.{2,3}?[縣市])(?<district>.{1,4}?[區鄉鎮市])");

    private final WeatherAdvisoryService weatherService;
    private final LocationEventRepository locationRepository;
    private final PlaceRepository placeRepository;
    private final Clock clock;

    public LocalizedWeatherService(WeatherAdvisoryService weatherService,
                                   LocationEventRepository locationRepository,
                                   PlaceRepository placeRepository, Clock clock) {
        this.weatherService = weatherService;
        this.locationRepository = locationRepository;
        this.placeRepository = placeRepository;
        this.clock = clock;
    }

    public String describeCurrentForecast() {
        WorkspaceChannel channel = WorkspaceContextHolder.requireContext().channel();
        if (channel == WorkspaceChannel.LINE) {
            return countyFallback("LINE 不會提供持續或即時 GPS；本次使用設定縣市，並未宣稱是你目前所在行政區。");
        }

        Optional<LocationEvent> event = locationRepository.findTopByOrderByOccurredAtDesc()
                .filter(this::isRecentAppLocation);
        if (event.isEmpty()) {
            return countyFallback("目前沒有 30 分鐘內由 App 回報的位置，因此無法安全判定所在行政區。");
        }
        Optional<AdminArea> area = nearbyAdminArea(event.get());
        if (area.isEmpty()) {
            return countyFallback("App 已回報近期位置，但目前無法由附近已知地址判定行政區；先降級使用縣市預報。");
        }
        AdminArea resolved = area.get();
        return weatherService.describeDistrictForecast(resolved.county(), resolved.district())
                .map(forecast -> forecast + "\n定位來源：App 近期位置；精度：行政區。")
                .orElseGet(() -> countyFallback(
                        "已判定所在行政區為%s%s，但行政區預報暫時不可用；本次降級使用縣市預報。"
                                .formatted(resolved.county(), resolved.district())));
    }

    private Optional<AdminArea> nearbyAdminArea(LocationEvent event) {
        return placeRepository.findWithinRadius(
                        event.getLatitude(), event.getLongitude(), 5000).stream()
                .map(Place::getAddress).filter(address -> address != null && !address.isBlank())
                .map(LocalizedWeatherService::parseAdminArea)
                .flatMap(Optional::stream).findFirst();
    }

    private boolean isRecentAppLocation(LocationEvent event) {
        if (event.getOccurredAt() == null || event.getSource() == null) return false;
        Instant now = Instant.now(clock);
        if (event.getOccurredAt().isAfter(now.plusSeconds(60))
                || event.getOccurredAt().isBefore(now.minus(LOCATION_MAX_AGE))) return false;
        String source = event.getSource().toLowerCase(Locale.ROOT);
        return source.contains("app") || source.contains("ios")
                || source.contains("android") || source.contains("mobile");
    }

    private String countyFallback(String reason) {
        String forecast = weatherService.describeCurrentForecast()
                .orElse("目前拿不到天氣預報，稍後再試。");
        return forecast + "\n定位說明：" + reason;
    }

    static Optional<AdminArea> parseAdminArea(String address) {
        if (address == null) return Optional.empty();
        String normalized = address.strip()
                .replaceFirst("^\\d{3,5}", "")
                .replaceFirst("^(?:台灣|臺灣)", "")
                .replace('台', '臺');
        Matcher matcher = ADMIN_AREA.matcher(normalized);
        return matcher.find()
                ? Optional.of(new AdminArea(matcher.group("county"), matcher.group("district")))
                : Optional.empty();
    }

    record AdminArea(String county, String district) {
    }
}
