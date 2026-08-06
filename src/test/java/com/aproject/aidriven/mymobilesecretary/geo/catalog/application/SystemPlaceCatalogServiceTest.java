package com.aproject.aidriven.mymobilesecretary.geo.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.geo.catalog.domain.SystemPlaceCatalogEntry;
import com.aproject.aidriven.mymobilesecretary.geo.catalog.persistence.SystemPlaceCatalogRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SystemPlaceCatalogServiceTest {

    private final SystemPlaceCatalogRepository repository = mock(SystemPlaceCatalogRepository.class);
    private SystemPlaceCatalogService service;

    @BeforeEach
    void setUp() {
        service = new SystemPlaceCatalogService(repository);
    }

    @Test
    void exactMatchIsReadOnlyTypedEvidence() {
        SystemPlaceCatalogEntry point = point("station.taipei.main", "station.taipei",
                "台北車站", "台北車站南一門", "臺北市", 25.0478, 121.5170);
        when(repository.findActiveMatches("台北車站", null)).thenReturn(List.of(point));

        SystemPlaceCatalogLookup lookup = service.lookup(" 台北　車站 ", null);

        assertThat(lookup.status()).isEqualTo(SystemPlaceCatalogLookup.Status.EXACT);
        assertThat(lookup.candidates()).extracting(SystemPlaceCatalogView::pointName)
                .containsExactly("台北車站南一門");
        when(repository.findById("station.taipei.main")).thenReturn(Optional.of(point));
        SystemPlaceCatalogAdoption adoption = service.adopt("station.taipei.main");
        assertThat(adoption.customPlaceCreated()).isFalse();
        assertThat(adoption.copiedToPlanSnapshot()).isFalse();
    }

    @Test
    void sameLogicalPlaceDefersPhysicalPointSelection() {
        SystemPlaceCatalogEntry first = point("airport.taoyuan.t1", "airport.taoyuan",
                "桃園國際機場", "第一航廈", "桃園市", 25.08, 121.23);
        SystemPlaceCatalogEntry second = point("airport.taoyuan.t2", "airport.taoyuan",
                "桃園國際機場", "第二航廈", "桃園市", 25.08, 121.24);
        when(repository.findActiveMatches("桃園國際機場", null)).thenReturn(List.of(second, first));

        assertThat(service.lookup("桃園國際機場", null).status())
                .isEqualTo(SystemPlaceCatalogLookup.Status.MULTIPOINT);
    }

    @Test
    void differentRegionsRemainAmbiguous() {
        SystemPlaceCatalogEntry taipei = point("hospital.taipei", "hospital.central",
                "中央醫院", "中央醫院台北院區", "臺北市", null, null);
        SystemPlaceCatalogEntry kaohsiung = point("hospital.kaohsiung", "hospital.central.kaohsiung",
                "中央醫院", "中央醫院高雄院區", "高雄市", null, null);
        when(repository.findActiveMatches("中央醫院", null)).thenReturn(List.of(taipei, kaohsiung));

        SystemPlaceCatalogLookup lookup = service.lookup("中央醫院", null);

        assertThat(lookup.status()).isEqualTo(SystemPlaceCatalogLookup.Status.CROSS_REGION);
        assertThat(lookup.candidates()).allMatch(view -> !view.hasCoordinates());
    }

    private static SystemPlaceCatalogEntry point(
            String key, String logicalKey, String logicalName, String pointName,
            String region, Double latitude, Double longitude) {
        return SystemPlaceCatalogEntry.create(key, logicalKey, logicalName, pointName, region,
                pointName + "地址", latitude, longitude, "TEST", "approved-test-fixture", null,
                Instant.EPOCH);
    }
}
