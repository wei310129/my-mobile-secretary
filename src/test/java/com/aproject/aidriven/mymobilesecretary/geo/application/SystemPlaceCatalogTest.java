package com.aproject.aidriven.mymobilesecretary.geo.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.geo.domain.SystemPlaceCategory;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class SystemPlaceCatalogTest {

    private SystemPlaceCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new SystemPlaceCatalog(new ClassPathResource("system-place-catalog.tsv"));
    }

    @Test
    void snapshotContainsEveryApprovedPublicPlaceCategoryAtNationalScale() {
        assertThat(catalog.size()).isGreaterThanOrEqualTo(530);
        assertThat(catalog.categoryCounts()).containsAllEntriesOf(Map.of(
                SystemPlaceCategory.METRO_STATION, 202L,
                SystemPlaceCategory.SEAPORT, 7L,
                SystemPlaceCategory.AIRPORT, 17L,
                SystemPlaceCategory.HSR_STATION, 12L,
                SystemPlaceCategory.RAIL_STATION, 244L,
                SystemPlaceCategory.LOCAL_GOVERNMENT, 22L,
                SystemPlaceCategory.AMUSEMENT_PARK, 27L));
    }

    @Test
    void commonAliasesResolveWithoutAnyExternalProvider() {
        assertFound("桃園機場", SystemPlaceCategory.AIRPORT, "臺灣桃園國際機場");
        assertFound("高鐵台中站", SystemPlaceCategory.HSR_STATION, "台中");
        assertFound("台鐵台北站", SystemPlaceCategory.RAIL_STATION, "臺北");
        assertFound("台北捷運市政府站", SystemPlaceCategory.METRO_STATION, "市政府");
        assertFound("高雄港", SystemPlaceCategory.SEAPORT, "高雄港");
        assertFound("台北市府", SystemPlaceCategory.LOCAL_GOVERNMENT, "臺北市政府");
        assertFound("六福村", SystemPlaceCategory.AMUSEMENT_PARK, "六福村");
    }

    @Test
    void sameStationNameAcrossSystemsRemainsAmbiguousInsteadOfGuessing() {
        assertThat(catalog.lookup("市政府站").status())
                .isEqualTo(SystemPlaceCatalog.Lookup.Status.AMBIGUOUS);
    }

    @Test
    void samePhysicalHubIsDeferredAsMultiPointWhileCrossRegionNameNeedsClarification() {
        SystemPlaceCatalog.Resolution hub = catalog.resolveMention("台北站");
        SystemPlaceCatalog.Resolution crossRegion = catalog.resolveMention("市政府站");

        assertThat(hub.status())
                .isEqualTo(SystemPlaceCatalog.Resolution.Status.LOGICAL_PLACE_MULTIPOINT);
        assertThat(hub.selected()).isNotNull();
        assertThat(hub.candidates()).hasSizeGreaterThan(1);
        assertThat(hub.logicalPlace().name()).isEqualTo("台北火車站");
        assertThat(hub.logicalPlace().region()).isEqualTo("台北市");
        assertThat(crossRegion.status())
                .isEqualTo(SystemPlaceCatalog.Resolution.Status.ENTITY_AMBIGUOUS);
        assertThat(crossRegion.selected()).isNull();
    }

    @Test
    void logicalHubCandidatesCanBeFilteredByOperatorKeyword() {
        SystemPlaceCatalog.Resolution hub = catalog.resolveMention("台北車站");

        assertThat(catalog.filterCandidates(
                        hub.candidates().stream().map(SystemPlaceCatalog.SystemPlace::key).toList(),
                        "機場捷運"))
                .singleElement()
                .satisfies(point -> assertThat(point.key()).contains("TYMC"));
        assertThat(catalog.filterCandidates(
                        hub.candidates().stream().map(SystemPlaceCatalog.SystemPlace::key).toList(),
                        "捷運"))
                .hasSize(2);
        assertThat(catalog.filterCandidates(
                        hub.candidates().stream().map(SystemPlaceCatalog.SystemPlace::key).toList(),
                        "不存在的營運系統"))
                .isEmpty();
    }

    @Test
    void publicPointLabelRoundTripsToTheExactOperatorPoint() {
        SystemPlaceCatalog.Resolution resolution =
                catalog.resolveMention("桃園捷運「捷運台北車站」");

        assertThat(resolution.status())
                .isEqualTo(SystemPlaceCatalog.Resolution.Status.EXACT);
        assertThat(resolution.selected().key()).contains("TYMC-A1");
    }

    @Test
    void categoryOnlyMentionCanBeNarrowedWithoutExternalSearch() {
        SystemPlaceCatalog.Resolution category = catalog.resolveMention("捷運站");

        assertThat(category.status())
                .isEqualTo(SystemPlaceCatalog.Resolution.Status.CATEGORY_ONLY);
        assertThat(category.category()).isEqualTo(SystemPlaceCategory.METRO_STATION);
        assertThat(catalog.search(category.category(), "台北市", 10))
                .isNotEmpty()
                .allSatisfy(place ->
                        assertThat(place.category()).isEqualTo(SystemPlaceCategory.METRO_STATION));
    }

    private void assertFound(
            String query, SystemPlaceCategory category, String expectedNameFragment) {
        SystemPlaceCatalog.Lookup lookup = catalog.lookup(query);

        assertThat(lookup.status()).isEqualTo(SystemPlaceCatalog.Lookup.Status.FOUND);
        assertThat(lookup.place().category()).isEqualTo(category);
        assertThat(lookup.place().name()).contains(expectedNameFragment);
        assertThat(lookup.place().location()).isNotBlank();
        assertThat(lookup.place().source()).isNotBlank();
    }
}
