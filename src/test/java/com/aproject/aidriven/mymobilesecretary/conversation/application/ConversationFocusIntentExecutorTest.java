package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.handler.IntentHandlerRegistry;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConversationFocusIntentExecutorTest {

    @Test
    void typedTaskCreationProducesResourceEnterFromPersistedHandlerResult() {
        IntentHandlerRegistry handlers = mock(IntentHandlerRegistry.class);
        ConversationFocusIntentHandler focusIntentHandler = mock(ConversationFocusIntentHandler.class);
        ConversationFocusContributorRegistry contributors = mock(ConversationFocusContributorRegistry.class);
        ConversationFocusService focusService = mock(ConversationFocusService.class);
        ConversationFocusAtomicExecutor atomicExecutor = mock(ConversationFocusAtomicExecutor.class);
        ConversationFocusIntentExecutor executor = new ConversationFocusIntentExecutor(handlers,
                focusIntentHandler, new ConversationFocusTargetResolver(), contributors, focusService,
                atomicExecutor);
        IntentCommand command = command(IntentCommand.Type.CREATE_TASK);
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(42L);
        when(task.getTitle()).thenReturn("繳電費");
        IntentResult result = new IntentResult(IntentResult.Action.TASK_CREATED, "已建立", task, null);
        when(focusIntentHandler.behaviorFor(command.type())).thenReturn(FocusBehavior.START_OR_SWITCH);
        when(focusIntentHandler.decide(eq(command.type()), any(FocusControl.class), eq(false)))
                .thenReturn(FocusDecision.transition(FocusTransitionType.ENTER));
        when(focusService.hasActiveFocus()).thenReturn(false);
        when(focusService.activeFocus()).thenReturn(Optional.empty());
        ConversationFocusContributor contributor = mock(ConversationFocusContributor.class);
        when(contributors.require("TASK")).thenReturn(contributor);
        when(contributor.isAvailable(any())).thenReturn(true);
        when(handlers.dispatch("建立待辦", command)).thenReturn(result);
        when(atomicExecutor.executeResolved(eq("a".repeat(64)), any()))
                .thenAnswer(invocation -> ((ResolvedFocusExecution<?>)
                        ((Supplier<?>) invocation.getArgument(1)).get()).response());

        IntentResult executed = executor.execute(
                "建立待辦", command, "a".repeat(64));

        assertThat(executed.focusNotice()).isNotNull();
        assertThat(executed.focusNotice().type()).isEqualTo(FocusTransitionType.ENTER);
        ArgumentCaptor<FocusControl> control = ArgumentCaptor.forClass(FocusControl.class);
        verify(focusIntentHandler).decide(eq(command.type()), control.capture(), eq(false));
        assertThat(control.getValue()).isEqualTo(new FocusControl.EnterResource(
                "TASK", "task:42", "繳電費"));
    }

    @Test
    void domainSuccessCallbackRunsInsideAtomicResolution() {
        IntentHandlerRegistry handlers = mock(IntentHandlerRegistry.class);
        ConversationFocusIntentHandler focusIntentHandler = mock(ConversationFocusIntentHandler.class);
        ConversationFocusContributorRegistry contributors = mock(ConversationFocusContributorRegistry.class);
        ConversationFocusService focusService = mock(ConversationFocusService.class);
        ConversationFocusAtomicExecutor atomicExecutor = mock(ConversationFocusAtomicExecutor.class);
        ConversationFocusIntentExecutor executor = new ConversationFocusIntentExecutor(handlers,
                focusIntentHandler, new ConversationFocusTargetResolver(), contributors, focusService,
                atomicExecutor);
        IntentCommand command = command(IntentCommand.Type.ASK_WEATHER);
        IntentResult result = IntentResult.message(IntentResult.Action.CONTEXT_UPDATED, "晴天");
        AtomicBoolean callbackRan = new AtomicBoolean();
        when(focusService.activeFocus()).thenReturn(Optional.empty());
        when(handlers.dispatch("明天天氣", command)).thenReturn(result);
        when(focusIntentHandler.behaviorFor(command.type())).thenReturn(FocusBehavior.ONE_SHOT_KEEP);
        when(atomicExecutor.executeResolved(eq("a".repeat(64)), any()))
                .thenAnswer(invocation -> {
                    ResolvedFocusExecution<?> resolved =
                            (ResolvedFocusExecution<?>) ((Supplier<?>) invocation.getArgument(1)).get();
                    assertThat(callbackRan).isTrue();
                    return resolved.response();
                });

        IntentResult executed = executor.execute(
                "明天天氣", command, "a".repeat(64), ignored -> callbackRan.set(true));

        assertThat(executed).isSameAs(result);
    }

    private static IntentCommand command(IntentCommand.Type type) {
        return new IntentCommand(type, "繳電費", null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
