package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.geo.application.GeofenceRuleService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.intent.application.BulkScheduleCancellationService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationSnapshot;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContextIntentHandlerTest {

    private ContextIntentHandler handler;
    private ConversationContextService contextService;
    private BulkScheduleCancellationService bulkCancellationService;

    @BeforeEach
    void setUp() {
        contextService = mock(ConversationContextService.class);
        bulkCancellationService = mock(BulkScheduleCancellationService.class);
        when(contextService.scheduleIdAt(null)).thenReturn(null);
        when(contextService.snapshot()).thenReturn(ConversationSnapshot.empty());
        handler = new ContextIntentHandler(
                mock(TaskService.class),
                mock(ScheduleService.class),
                contextService,
                mock(PlaceAliasService.class),
                mock(PlaceService.class),
                mock(GeofenceRuleService.class),
                bulkCancellationService);
    }

    @Test
    void registersEveryContextType() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrderElementsOf(Set.of(
                IntentCommand.Type.ACCEPT_CONTEXT,
                IntentCommand.Type.CANCEL_CONTEXT,
                IntentCommand.Type.COPY_CONTEXT,
                IntentCommand.Type.SET_CONTEXT_PLACE,
                IntentCommand.Type.SHIFT_CONTEXT_LATER));
    }

    @Test
    void acceptWithoutRememberedScheduleKeepsExistingClarification() {
        IntentResult result = handler.handle("接受", command(IntentCommand.Type.ACCEPT_CONTEXT));

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).isEqualTo(
                IntentResult.clarificationNeeded("目前沒有可接受的行程提案。").message());
    }

    @Test
    void explicitAcceptAfterBulkPreviewUsesOnlyRememberedPreviewIds() {
        var snapshot = new ConversationSnapshot(
                null, null, null, java.util.List.of(), java.util.List.of(11L, 12L),
                IntentResult.Action.SCHEDULE_CANCELLATION_PREVIEWED.name(), null, null);
        when(contextService.snapshot()).thenReturn(snapshot);
        IntentResult expected = IntentResult.message(
                IntentResult.Action.SCHEDULES_BULK_CANCELED, "已刪除預覽清單");
        when(bulkCancellationService.confirmPreview(java.util.List.of(11L, 12L)))
                .thenReturn(expected);

        assertThat(handler.handle("確認刪除剛才清單", command(IntentCommand.Type.ACCEPT_CONTEXT)))
                .isSameAs(expected);
    }

    @Test
    void missingContextExceptionIsConvertedToExistingClarification() {
        IntentResult result = handler.handle(
                "晚一點", command(IntentCommand.Type.SHIFT_CONTEXT_LATER));

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).isEqualTo(
                IntentResult.clarificationNeeded("目前沒有可承接的上一筆內容,請直接說待辦或行程名稱。").message());
    }

    private static IntentCommand command(IntentCommand.Type type) {
        return new IntentCommand(type, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
