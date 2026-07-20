package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationIntentHandler;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IntentHandlerRegistryTest {

    @Test
    void dispatchesRegisteredType() {
        IntentResult expected = IntentResult.message(IntentResult.Action.SOCIAL_REPLIED, "ok");
        IntentHandlerRegistry registry = new IntentHandlerRegistry(List.of(
                handler(Set.of(IntentCommand.Type.SOCIAL), expected)));

        IntentResult result = registry.dispatch("謝謝", command(IntentCommand.Type.SOCIAL));

        assertThat(result).isSameAs(expected);
        assertThat(registry.supports(IntentCommand.Type.SOCIAL)).isTrue();
        assertThat(registry.supports(IntentCommand.Type.CREATE_TASK)).isFalse();
    }

    @Test
    void duplicateTypeRegistrationFailsAtStartup() {
        IntentHandler first = handler(Set.of(IntentCommand.Type.SOCIAL),
                IntentResult.message(IntentResult.Action.SOCIAL_REPLIED, "first"));
        IntentHandler second = handler(Set.of(IntentCommand.Type.SOCIAL),
                IntentResult.message(IntentResult.Action.SOCIAL_REPLIED, "second"));

        assertThatThrownBy(() -> new IntentHandlerRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate intent handler registration")
                .hasMessageContaining("SOCIAL");
    }

    @Test
    void unregisteredTypeFailsClearly() {
        IntentHandlerRegistry registry = new IntentHandlerRegistry(List.of(
                handler(Set.of(IntentCommand.Type.SOCIAL),
                        IntentResult.message(IntentResult.Action.SOCIAL_REPLIED, "ok"))));

        assertThatThrownBy(() -> registry.dispatch("新增待辦", command(IntentCommand.Type.CREATE_TASK)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("no intent handler registered for type CREATE_TASK");
    }

    @Test
    void everyExecutableTypeHasExactlyOneProductionHandler() {
        IntentHandlerRegistry registry = new IntentHandlerRegistry(List.of(
                mock(ActivityIntentHandler.class, CALLS_REAL_METHODS),
                mock(BloodDonationIntentHandler.class, CALLS_REAL_METHODS),
                mock(ContactIntentHandler.class, CALLS_REAL_METHODS),
                mock(ContextIntentHandler.class, CALLS_REAL_METHODS),
                mock(ConversationIntentHandler.class, CALLS_REAL_METHODS),
                mock(PlaceIntentHandler.class, CALLS_REAL_METHODS),
                mock(PlannerIntentHandler.class, CALLS_REAL_METHODS),
                mock(ProductExperienceIntentHandler.class, CALLS_REAL_METHODS),
                mock(ReminderIntentHandler.class, CALLS_REAL_METHODS),
                mock(ScheduleMutationIntentHandler.class, CALLS_REAL_METHODS),
                mock(ScheduleQueryIntentHandler.class, CALLS_REAL_METHODS),
                mock(SchoolMealIntentHandler.class, CALLS_REAL_METHODS),
                mock(ShoppingIntentHandler.class, CALLS_REAL_METHODS),
                mock(TaskMutationIntentHandler.class, CALLS_REAL_METHODS),
                mock(TaskQueryIntentHandler.class, CALLS_REAL_METHODS),
                mock(TagGraphIntentHandler.class, CALLS_REAL_METHODS),
                mock(TravelIntentHandler.class, CALLS_REAL_METHODS),
                mock(VenueVisitInformationIntentHandler.class, CALLS_REAL_METHODS)));

        assertThat(java.util.Arrays.stream(IntentCommand.Type.values())
                .filter(type -> !registry.supports(type)))
                .containsExactly(IntentCommand.Type.UNKNOWN);
    }

    private static IntentHandler handler(Set<IntentCommand.Type> types, IntentResult result) {
        return new IntentHandler() {
            @Override
            public Set<IntentCommand.Type> supportedTypes() {
                return types;
            }

            @Override
            public IntentResult handle(String text, IntentCommand command) {
                return result;
            }
        };
    }

    private static IntentCommand command(IntentCommand.Type type) {
        return new IntentCommand(type, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
