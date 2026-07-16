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
}
