package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeIntentTargetResolver;
import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeTarget;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CalendarCategoryConversationServiceTest {

    @Test
    void categoryChangesButColorAndOtherSemanticsStayUnsupported() {
        CalendarKnowledgeIntentTargetResolver targets =
                mock(CalendarKnowledgeIntentTargetResolver.class);
        CalendarApplicationService calendars = mock(CalendarApplicationService.class);
        UUID planId =
                UUID.fromString("30000000-0000-0000-0000-000000000003");
        when(targets.resolve("日本旅行", "PLAN", null))
                .thenReturn(Optional.of(CalendarKnowledgeTarget.plan(planId)));
        CalendarPlanIdentityView current = mock(CalendarPlanIdentityView.class);
        when(current.revision()).thenReturn(7L);
        when(calendars.lockActivePlanById(planId)).thenReturn(current);
        CalendarPlanIdentityView changed = mock(CalendarPlanIdentityView.class);
        when(changed.category()).thenReturn("交通");
        when(calendars.changePlanCategoryById(planId, "交通", 7L))
                .thenReturn(changed);
        CalendarCategoryConversationService service =
                new CalendarCategoryConversationService(targets, calendars);

        IntentResult result = service.change(new IntentCommand(
                IntentCommand.Type.CHANGE_CALENDAR_CATEGORY,
                "交通",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                IntentOptions.empty().withReferenceTitle("日本旅行")));

        verify(calendars).changePlanCategoryById(planId, "交通", 7L);
        assertThat(result.action())
                .isEqualTo(
                        IntentResult.Action
                                .CALENDAR_CATEGORY_CHANGED_COLOR_UNSUPPORTED);
        assertThat(result.message())
                .contains("分類設為「交通」", "顏色功能尚未交付")
                .contains("沒有改動重要性、權限或參加狀態");
    }
}
