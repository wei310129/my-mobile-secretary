package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.LifestyleWindow;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.LifestyleWindowRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LifestyleWindowServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T01:00:00Z");

    @Mock
    private LifestyleWindowRepository repository;

    @Test
    void createsFirstActorScopedWindow() {
        when(repository.findByDayTypeAndKind(
                LifestyleWindow.DayType.WEEKDAY, LifestyleWindow.Kind.BREAKFAST))
                .thenReturn(Optional.empty());
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> call.getArgument(0));
        LifestyleWindowService service = service();

        LifestyleWindow value = service.set(
                LifestyleWindow.DayType.WEEKDAY, LifestyleWindow.Kind.BREAKFAST,
                LocalTime.of(7, 0), LocalTime.of(7, 30));

        assertThat(value.getStartTime()).isEqualTo(LocalTime.of(7, 0));
        assertThat(value.getEndTime()).isEqualTo(LocalTime.of(7, 30));
        assertThat(value.getUpdatedAt()).isEqualTo(NOW);
        verify(repository).save(value);
    }

    @Test
    void updatesExistingWindowWithoutCreatingDuplicate() {
        LifestyleWindow existing = LifestyleWindow.create(
                LifestyleWindow.DayType.HOLIDAY, LifestyleWindow.Kind.SLEEP,
                LocalTime.of(23, 30), LocalTime.of(8, 0), NOW.minusSeconds(60));
        ReflectionTestUtils.setField(existing, "id", 8L);
        when(repository.findByDayTypeAndKind(
                LifestyleWindow.DayType.HOLIDAY, LifestyleWindow.Kind.SLEEP))
                .thenReturn(Optional.of(existing));
        LifestyleWindowService service = service();

        LifestyleWindow value = service.set(
                LifestyleWindow.DayType.HOLIDAY, LifestyleWindow.Kind.SLEEP,
                LocalTime.MIDNIGHT, LocalTime.of(9, 0));

        assertThat(value).isSameAs(existing);
        assertThat(value.getStartTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(value.getEndTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(value.getUpdatedAt()).isEqualTo(NOW);
        verify(repository, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void listsOnlyRequestedDayType() {
        LifestyleWindow lunch = LifestyleWindow.create(
                LifestyleWindow.DayType.WEEKDAY, LifestyleWindow.Kind.LUNCH,
                LocalTime.NOON, LocalTime.of(13, 0), NOW);
        when(repository.findByDayTypeOrderByStartTimeAsc(LifestyleWindow.DayType.WEEKDAY))
                .thenReturn(List.of(lunch));

        assertThat(service().list(LifestyleWindow.DayType.WEEKDAY)).containsExactly(lunch);
    }

    private LifestyleWindowService service() {
        return new LifestyleWindowService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
