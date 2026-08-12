package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.application.SystemPlaceCatalog;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class SystemPlaceConversationSealedHoldoutTest {

    private SystemPlaceConversationService service;

    @BeforeEach
    void setUp() {
        PlaceAliasService aliases = mock(PlaceAliasService.class);
        when(aliases.resolve(anyString())).thenReturn(Optional.empty());
        service = new SystemPlaceConversationService(
                new SystemPlaceCatalog(new ClassPathResource("system-place-catalog.tsv")), aliases);
    }

    @Test
    void unseenKnowledgeWordingAnswersTheLogicalPlaceBeforeOptions() {
        IntentResult result = service.answer("想確認一下，您認得臺北車站嗎？").orElseThrow();

        assertThat(result.message()).contains("我知道", "台北市", "台北火車站", "2 個系統點位")
                .doesNotContain("安排完整行程", "沒有建立", "現在不用先選");
    }

    @Test
    void unseenPointListWordingUsesTheSameTypedOperation() {
        IntentResult result = service.answer("把臺北車站的兩個點列給我").orElseThrow();

        assertThat(result.message()).contains("目前有 2 個系統點位", "台北捷運", "桃園捷運")
                .doesNotContain("哪個縣市", "安排完整行程");
    }

    @Test
    void locationNeighborAnswersWithoutTurningIntoAnItinerary() {
        IntentResult result = service.answer("臺北車站在什麼地方？").orElseThrow();

        assertThat(result.message()).contains("台北市", "台北火車站")
                .doesNotContain("安排完整行程", "交通方式");
    }
}
