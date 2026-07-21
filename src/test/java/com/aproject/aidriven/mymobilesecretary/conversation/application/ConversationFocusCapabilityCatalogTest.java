package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ConversationFocusCapabilityCatalogTest {

    @Test
    void everyIntentTypeHasExactlyOneJavaFocusBehavior() {
        ConversationFocusCapabilityCatalog catalog = new ConversationFocusCapabilityCatalog();

        assertThat(catalog.behaviors()).hasSize(IntentCommand.Type.values().length);
        assertThat(catalog.behaviorFor(IntentCommand.Type.SOCIAL)).isEqualTo(FocusBehavior.NEVER_TOUCH);
        assertThat(catalog.behaviorFor(IntentCommand.Type.CREATE_TASK))
                .isEqualTo(FocusBehavior.START_OR_SWITCH);
        assertThat(catalog.behaviorFor(IntentCommand.Type.CREATE_SCHEDULE))
                .isEqualTo(FocusBehavior.START_OR_SWITCH);
        assertThat(catalog.behaviorFor(IntentCommand.Type.RESCHEDULE_SCHEDULE))
                .isEqualTo(FocusBehavior.START_OR_SWITCH);
        assertThat(catalog.behaviorFor(IntentCommand.Type.ASK_WEATHER))
                .isEqualTo(FocusBehavior.ONE_SHOT_KEEP);
        assertThat(catalog.behaviorFor(IntentCommand.Type.FEEDBACK))
                .isEqualTo(FocusBehavior.NEVER_TOUCH);
    }

    @Test
    void missingIntentMappingFailsAtConstruction() {
        EnumMap<IntentCommand.Type, FocusBehavior> incomplete =
                new EnumMap<>(IntentCommand.Type.class);
        incomplete.put(IntentCommand.Type.SOCIAL, FocusBehavior.NEVER_TOUCH);

        assertThatThrownBy(() -> new ConversationFocusCapabilityCatalog(incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing focus behavior");
    }

    @Test
    void userFacingCatalogNeverReferencesAnIntentWithoutJavaFocusClassification() throws Exception {
        ConversationFocusCapabilityCatalog catalog = new ConversationFocusCapabilityCatalog();

        new ClassPathResource("conversation-capabilities.txt")
                .getContentAsString(StandardCharsets.UTF_8)
                .lines()
                .filter(line -> !line.isBlank())
                .flatMap(line -> java.util.Arrays.stream(line.split("\\|", 4)[2].split("\\+")))
                .filter(marker -> java.util.Arrays.stream(IntentCommand.Type.values())
                        .map(Enum::name)
                        .anyMatch(marker::equals))
                .forEach(marker -> assertThat(catalog.behaviorFor(IntentCommand.Type.valueOf(marker)))
                        .isNotNull());
    }
}
