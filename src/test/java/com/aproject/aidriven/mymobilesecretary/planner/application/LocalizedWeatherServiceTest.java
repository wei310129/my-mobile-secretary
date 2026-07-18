package com.aproject.aidriven.mymobilesecretary.planner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.geo.domain.LocationEvent;
import com.aproject.aidriven.mymobilesecretary.geo.domain.LocationEventType;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.LocationEventRepository;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalizedWeatherServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");
    private WeatherAdvisoryService weather;
    private LocationEventRepository locations;
    private PlaceRepository places;
    private LocalizedWeatherService service;

    @BeforeEach
    void setUp() {
        weather = mock(WeatherAdvisoryService.class);
        locations = mock(LocationEventRepository.class);
        places = mock(PlaceRepository.class);
        when(weather.describeCurrentForecast()).thenReturn(Optional.of("新北市縣市預報"));
        service = new LocalizedWeatherService(weather, locations, places,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void lineNeverClaimsToHaveLiveGps() throws Exception {
        String result = inScope(WorkspaceChannel.LINE, service::describeCurrentForecast);

        assertThat(result).contains("新北市縣市預報", "LINE 不會提供持續或即時 GPS",
                "並未宣稱是你目前所在行政區");
        verify(locations, never()).findTopByOrderByOccurredAtDesc();
    }

    @Test
    void recentAppLocationUsesDistrictFromNearbyKnownAddress() throws Exception {
        LocationEvent event = LocationEvent.record(LocationEventType.MANUAL_PING,
                24.98, 121.54, NOW.minusSeconds(60), "ios-app", NOW);
        Place place = Place.create("公司", "231台灣新北市新店區民權路42號",
                24.98, 121.54, "OFFICE", NOW);
        when(locations.findTopByOrderByOccurredAtDesc()).thenReturn(Optional.of(event));
        when(places.findWithinRadius(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(place));
        when(weather.describeDistrictForecast("新北市", "新店區"))
                .thenReturn(Optional.of("新北市新店區行政區預報"));

        String result = inScope(WorkspaceChannel.REST, service::describeCurrentForecast);

        assertThat(result).contains("新北市新店區行政區預報", "定位來源：App 近期位置",
                "精度：行政區");
    }

    @Test
    void staleAppLocationFallsBackAndExplainsWhy() throws Exception {
        LocationEvent event = LocationEvent.record(LocationEventType.MANUAL_PING,
                24.98, 121.54, NOW.minusSeconds(3600), "android-app", NOW);
        when(locations.findTopByOrderByOccurredAtDesc()).thenReturn(Optional.of(event));

        String result = inScope(WorkspaceChannel.REST, service::describeCurrentForecast);

        assertThat(result).contains("新北市縣市預報", "沒有 30 分鐘內由 App 回報的位置");
        verify(places, never()).findWithinRadius(anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void parsesTaiwanPostalAddressToCountyAndDistrict() {
        assertThat(LocalizedWeatherService.parseAdminArea("231台灣新北市新店區民權路42號"))
                .contains(new LocalizedWeatherService.AdminArea("新北市", "新店區"));
    }

    private static <T> T inScope(WorkspaceChannel channel,
                                 java.util.concurrent.Callable<T> action) throws Exception {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(UUID.randomUUID(), UUID.randomUUID(), channel))) {
            return action.call();
        }
    }
}
