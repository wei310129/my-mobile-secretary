package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ConversationCapabilityCatalogTest {

    @Test
    void catalogContainsThreeHundredSixtyFourNumberedAndRecognizedScenarios() throws Exception {
        var lines = new ClassPathResource("conversation-capabilities.txt")
                .getContentAsString(StandardCharsets.UTF_8)
                .lines()
                .filter(line -> !line.isBlank())
                .toList();

        assertThat(lines).hasSize(378);
        Set<String> commandTypes = Arrays.stream(IntentCommand.Type.values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> nonTextMarkers = Set.of(
                "RECEIPT_IMAGE", "TRAVEL_ITINERARY_IMAGE", "MEDICAL_APPOINTMENT_IMAGE",
                "WORK_SCHOOL_SUSPENSION_IMAGE", "EVENT_REGISTRATION_IMAGE", "BUSINESS_CARD_IMAGE",
                "TAX_PAYMENT_IMAGE", "BANK_TRANSFER_IMAGE", "SCHOOL_MENU_IMAGE",
                "BLOOD_DONATION_RECORD_IMAGE", "PAINT_PRODUCT_IMAGE", "PAYMENT_NOTICE_IMAGE",
                "FOLLOW_UP");

        for (int i = 0; i < lines.size(); i++) {
            String[] columns = lines.get(i).split("\\|", 4);
            assertThat(columns).as("catalog line %d", i + 1).hasSize(4);
            assertThat(columns[0]).isEqualTo("%03d".formatted(i + 1));
            assertThat(columns[1]).isNotBlank();
            assertThat(columns[3]).isNotBlank();
            for (String type : columns[2].split("\\+")) {
                assertThat(commandTypes.contains(type) || nonTextMarkers.contains(type))
                        .as("recognized command marker on line %d: %s", i + 1, type)
                        .isTrue();
            }
        }
    }
}
