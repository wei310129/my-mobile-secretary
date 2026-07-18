package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ItemRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.ReminderPreferenceRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.TaskRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleItemRepository;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntentPromptContextBuilderTest {

    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");
    private final PlaceRepository placeRepository = mock(PlaceRepository.class);
    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final ScheduleItemRepository scheduleRepository = mock(ScheduleItemRepository.class);
    private final ItemRepository itemRepository = mock(ItemRepository.class);
    private final ReminderPreferenceRepository reminderRepository =
            mock(ReminderPreferenceRepository.class);
    private IntentPromptContextBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new IntentPromptContextBuilder(placeRepository, taskRepository,
                scheduleRepository, itemRepository, reminderRepository);
    }

    @Test
    void unrelatedSentenceDoesNotLoadAnyPersonalState() {
        String prompt = builder.build("幫我想一句生日祝福", NOW, ConversationSnapshot.empty());

        assertThat(prompt)
                .contains("current_user_message")
                .doesNotContain("known_places", "open_tasks", "schedules", "item_state",
                        "reminder_preference", "short_term_context");
        verifyNoInteractions(placeRepository, taskRepository, scheduleRepository,
                itemRepository, reminderRepository);
    }

    @Test
    void scheduleQuestionLoadsOnlyScheduleState() {
        when(scheduleRepository.findByStatusInOrderByStartAtAsc(
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        String prompt = builder.build("週會有衝突嗎？", NOW, ConversationSnapshot.empty());

        assertThat(prompt).contains("schedules")
                .doesNotContain("known_places", "open_tasks", "item_state",
                        "reminder_preference", "short_term_context");
        verify(scheduleRepository).findByStatusInOrderByStartAtAsc(
                org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(placeRepository, taskRepository, itemRepository,
                reminderRepository);
    }

    @Test
    void scheduleContextIncludesEndRecurrenceAndResolvedPlace() {
        ScheduleItem item = mock(ScheduleItem.class);
        Place place = mock(Place.class);
        when(item.getId()).thenReturn(8L);
        when(item.getTitle()).thenReturn("專案週會");
        when(item.getStartAt()).thenReturn(Instant.parse("2026-07-20T01:00:00Z"));
        when(item.getEndAt()).thenReturn(Instant.parse("2026-07-20T02:30:00Z"));
        when(item.getRecurrence()).thenReturn(ScheduleItem.Recurrence.WEEKLY);
        when(item.getPlaceId()).thenReturn(42L);
        when(place.getId()).thenReturn(42L);
        when(place.getName()).thenReturn("信義辦公室");
        when(scheduleRepository.findByStatusInOrderByStartAtAsc(
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of(item));
        when(placeRepository.findAllById(List.of(42L))).thenReturn(List.of(place));

        String prompt = builder.build("排在專案週會後面", NOW, ConversationSnapshot.empty());

        assertThat(prompt).contains(
                "8:專案週會@start=2026-07-20T01:00:00Z",
                "end=2026-07-20T02:30:00Z",
                "recurrence=WEEKLY",
                "place=信義辦公室");
    }

    @Test
    void stateRowsAndContinuationContextAreBounded() {
        List<Place> places = IntStream.rangeClosed(1, 31)
                .mapToObj(index -> Place.create("地點" + index, null,
                        25.0, 121.0, "測試", NOW))
                .toList();
        when(placeRepository.findAll()).thenReturn(places);
        ConversationSnapshot context = new ConversationSnapshot(
                null, null, null, List.of(), List.of(), "PLACE_INFO",
                "上一個", "x".repeat(3_000));

        String prompt = builder.build("上一個地點呢？", NOW, context);

        assertThat(prompt)
                .contains("地點30", "short_term_context", "…[truncated]")
                .doesNotContain("地點31");
        assertThat(prompt.length()).isLessThan(4_000);
    }

    @Test
    void commonTaiwanesePhrasesKeepOnlyTheStateNeededForResolution() {
        var agenda = IntentPromptContextBuilder.Selection.forMessage("今天有什麼事？");
        var parcel = IntentPromptContextBuilder.Selection.forMessage(
                "拿包裹是要到蝦皮店到店中興二店");
        var purchased = IntentPromptContextBuilder.Selection.forMessage("牛奶買到了");

        assertThat(agenda.tasks()).isTrue();
        assertThat(agenda.schedules()).isTrue();
        assertThat(parcel.tasks()).isTrue();
        assertThat(parcel.places()).isTrue();
        assertThat(purchased.tasks()).isTrue();
        assertThat(purchased.items()).isTrue();
    }
}
