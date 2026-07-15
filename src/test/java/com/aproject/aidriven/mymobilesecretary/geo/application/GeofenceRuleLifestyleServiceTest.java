package com.aproject.aidriven.mymobilesecretary.geo.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.geo.domain.GeofenceRule;
import com.aproject.aidriven.mymobilesecretary.geo.domain.TriggerType;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.GeofenceRuleRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GeofenceRuleLifestyleServiceTest {

    private static final Instant NOW = Instant.parse("2030-08-01T00:00:00Z");

    @Mock
    private GeofenceRuleRepository repository;
    @Mock
    private PlaceService placeService;
    @Mock
    private TaskService taskService;

    private GeofenceRuleService service;

    @BeforeEach
    void setUp() {
        service = new GeofenceRuleService(repository, placeService, taskService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void updateRequiresUniqueTaskAndPlaceRule() {
        GeofenceRule rule = GeofenceRule.create(1L, 2L, 100, TriggerType.ENTER, NOW);
        when(repository.findByTaskIdAndPlaceId(1L, 2L)).thenReturn(List.of(rule));

        GeofenceRule changed = service.updateUniqueRule(1L, 2L, 500, TriggerType.EXIT);

        assertThat(changed.getRadiusMeters()).isEqualTo(500);
        assertThat(changed.getTriggerType()).isEqualTo(TriggerType.EXIT);
    }

    @Test
    void ambiguousRulesAreNeverGuessed() {
        when(repository.findByTaskIdAndPlaceId(1L, 2L)).thenReturn(List.of(
                GeofenceRule.create(1L, 2L, 100, TriggerType.ENTER, NOW),
                GeofenceRule.create(1L, 2L, 200, TriggerType.EXIT, NOW)));

        assertThatThrownBy(() -> service.updateUniqueRule(1L, 2L, 500, null))
                .hasMessageContaining("多個");
    }

    @Test
    void removingPlaceRuleKeepsTaskAndDeletesOnlyRule() {
        GeofenceRule rule = GeofenceRule.create(1L, 2L, 100, TriggerType.ENTER, NOW);
        when(repository.findByTaskIdAndPlaceId(1L, 2L)).thenReturn(List.of(rule));

        assertThat(service.removeUniqueRule(1L, 2L)).isSameAs(rule);
        verify(repository).delete(rule);
    }
}
