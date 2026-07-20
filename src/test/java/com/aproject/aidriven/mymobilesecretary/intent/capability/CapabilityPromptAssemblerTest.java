package com.aproject.aidriven.mymobilesecretary.intent.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.intent.capability.core.CoreCapabilityConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CapabilityPromptAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final CapabilityPromptAssembler assembler = new CapabilityPromptAssembler(objectMapper);
    private final CoreCapabilityConfiguration capabilities = new CoreCapabilityConfiguration();

    @Test
    void buildsStableVersionedCandidateOnlyPromptWithClosedSchemas() {
        CapabilityDescriptor createTask = capabilities.createTaskCapability().descriptor();
        CapabilityDescriptor priceHistory = capabilities.askPriceHistoryCapability().descriptor();

        CapabilityPromptAssembly first = assembler.assemble(List.of(priceHistory, createTask));
        CapabilityPromptAssembly second = assembler.assemble(List.of(createTask, priceHistory));

        assertThat(first).isEqualTo(second);
        assertThat(first.promptVersion()).isEqualTo("capability-candidates/v1");
        assertThat(first.promptHash()).matches("[0-9a-f]{64}");
        assertThat(first.content()).contains(
                "promptVersion=" + first.promptVersion(),
                "promptHash=" + first.promptHash(),
                "\"id\":\"task.create\"",
                "\"id\":\"price.history\"",
                "\"risk\":\"MUTATION\"",
                "\"additionalProperties\":false",
                "\"required\":[\"title\"]",
                "\"format\":\"date-time\"");
        assertThat(first.content()).doesNotContain(
                "schedule.create",
                "task.cancel",
                "inventory.adjust",
                "上一個指令哪裡有問題");
        assertThat(first.content().indexOf("price.history"))
                .isLessThan(first.content().indexOf("task.create"));
    }

    @Test
    void emitsEnumAndDateSchemasForOnlyTheSelectedCapability() {
        CapabilityPromptAssembly schedule = assembler.assemble(List.of(
                capabilities.createScheduleCapability().descriptor()));
        CapabilityPromptAssembly dateQuery = assembler.assemble(List.of(
                capabilities.listSchedulesOnDateCapability().descriptor()));

        assertThat(schedule.content()).contains(
                "\"required\":[\"title\",\"startAt\",\"endAt\"]",
                "\"enum\":[\"NONE\",\"WEEKLY\",\"WEEKDAYS\",\"MONTHLY_NTH_WEEKDAY\"]");
        assertThat(schedule.content()).doesNotContain("task.create", "price.history");
        assertThat(dateQuery.content()).contains(
                "\"required\":[\"date\"]",
                "\"format\":\"date\"");
        assertThat(dateQuery.content()).doesNotContain("schedule.create");
    }

    @Test
    void rejectsEmptyOversizedDuplicateAndNonRecordCandidateSets() {
        CapabilityDescriptor createTask = capabilities.createTaskCapability().descriptor();

        assertThatThrownBy(() -> assembler.assemble(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 12");

        List<CapabilityDescriptor> oversized = new ArrayList<>();
        for (int index = 0; index < 13; index++) {
            oversized.add(descriptor("test.capability_" + index, StringPayload.class));
        }
        assertThatThrownBy(() -> assembler.assemble(oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 12");

        assertThatThrownBy(() -> assembler.assemble(List.of(createTask, createTask)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate prompt candidate");

        assertThatThrownBy(() -> assembler.assemble(List.of(descriptor("test.string", String.class))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputType must be a record");
    }

    private static CapabilityDescriptor descriptor(String id, Class<?> inputType) {
        return new CapabilityDescriptor(
                CapabilityId.of(id),
                1,
                CapabilityDomain.SYSTEM,
                CapabilityRisk.QUERY,
                inputType,
                "Test only",
                Set.of(),
                List.of("測試"),
                Set.of("測試"));
    }

    private record StringPayload(String value) {
    }
}
