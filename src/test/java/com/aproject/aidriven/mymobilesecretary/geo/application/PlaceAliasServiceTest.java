package com.aproject.aidriven.mymobilesecretary.geo.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceAliasRepository;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlaceAliasServiceTest {
    @Mock private PlaceAliasRepository aliasRepository;
    @Mock private PlaceRepository placeRepository;
    @Mock private PlaceService placeService;

    @Test
    void specificBranchQueryIsNotCapturedByOldShortName() {
        Place old = Place.create("夏恩英語", "台北市信義區", 25.0, 121.5,
                "教育機構", Instant.parse("2030-01-01T00:00:00Z"));
        when(aliasRepository.findByAliasIgnoreCase("夏恩英語 新店七張分校"))
                .thenReturn(Optional.empty());
        when(placeRepository.findAll()).thenReturn(List.of(old));
        PlaceAliasService service = new PlaceAliasService(aliasRepository, placeRepository,
                placeService, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        assertThat(service.resolve("夏恩英語 新店七張分校")).isEmpty();
    }

    @Test
    void naturalQuestionUsesLongestUniqueUserOwnedMention() {
        Place airport = Place.create("我的桃園機場集合點", "桃園市測試路1號", 25.0, 121.2,
                "自訂集合點", Instant.parse("2030-01-01T00:00:00Z"));
        org.springframework.test.util.ReflectionTestUtils.setField(airport, "id", 41L);
        var alias = com.aproject.aidriven.mymobilesecretary.geo.domain.PlaceAlias.create(
                "桃園機場", 41L, Instant.EPOCH);
        when(aliasRepository.findAll()).thenReturn(List.of(alias));
        when(placeRepository.findAll()).thenReturn(List.of(airport));
        PlaceAliasService service = new PlaceAliasService(aliasRepository, placeRepository,
                placeService, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        assertThat(service.resolveMention("桃園機場在哪裡")).contains(airport);
    }
}
