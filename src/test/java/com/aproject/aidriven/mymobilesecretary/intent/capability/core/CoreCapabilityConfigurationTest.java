package com.aproject.aidriven.mymobilesecretary.intent.capability.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityArgumentException;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityDescriptor;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityHandler;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityRegistry;
import com.aproject.aidriven.mymobilesecretary.intent.capability.CapabilityRisk;
import com.aproject.aidriven.mymobilesecretary.intent.capability.ContextRequirement;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class CoreCapabilityConfigurationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final CoreCapabilityConfiguration configuration = new CoreCapabilityConfiguration();

    @Test
    void registersTenTypedHandlersAsSpringBeansWithExpectedRiskBoundaries() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> objectMapper);
            context.registerBean(Validator.class, () -> validator);
            context.register(CoreCapabilityConfiguration.class, CapabilityRegistry.class);
            context.refresh();

            CapabilityRegistry registry = context.getBean(CapabilityRegistry.class);
            Map<String, CapabilityDescriptor> descriptors = registry.activeDescriptors().stream()
                    .collect(Collectors.toMap(value -> value.id().value(), Function.identity()));

            assertThat(descriptors).hasSize(10);
            assertThat(descriptors.get("task.create").inputType()).isEqualTo(CreateTaskPayload.class);
            assertThat(descriptors.get("schedule.create").inputType()).isEqualTo(CreateSchedulePayload.class);
            assertThat(descriptors.get("task.cancel").risk()).isEqualTo(CapabilityRisk.DESTRUCTIVE);
            assertThat(descriptors.get("task.place.remove").risk()).isEqualTo(CapabilityRisk.DESTRUCTIVE);
            assertThat(descriptors.get("inventory.set").risk()).isEqualTo(CapabilityRisk.MUTATION);
            assertThat(descriptors.get("price.history").risk()).isEqualTo(CapabilityRisk.QUERY);
            assertThat(descriptors.get("intent.failure.explain").contextRequirements())
                    .containsExactly(ContextRequirement.LAST_INTENT_FAILURE);
        }
    }

    @Test
    void createTaskRejectsMissingTitleAndScheduleOnlyFields() {
        CapabilityRegistry registry = registry();

        assertThatThrownBy(() -> registry.mapAndValidate(
                CoreCapabilityIds.CREATE_TASK,
                objectMapper.createObjectNode().put("dueAt", "2026-07-17T22:00:00+08:00")))
                .isInstanceOf(CapabilityArgumentException.class)
                .hasMessageContaining("title", "must not be blank");

        assertThatThrownBy(() -> registry.mapAndValidate(
                CoreCapabilityIds.CREATE_TASK,
                objectMapper.createObjectNode()
                        .put("title", "倒垃圾")
                        .put("startAt", "2026-07-17T22:00:00+08:00")
                        .put("endAt", "2026-07-17T23:00:00+08:00")))
                .isInstanceOf(CapabilityArgumentException.class)
                .hasMessageContaining("unknown capability argument", "startAt");
    }

    @Test
    void createScheduleRequiresAnEndAfterStartAndValidRecurrenceCutoff() {
        CapabilityRegistry registry = registry();

        assertThatThrownBy(() -> registry.mapAndValidate(
                CoreCapabilityIds.CREATE_SCHEDULE,
                objectMapper.createObjectNode()
                        .put("title", "開會")
                        .put("startAt", "2026-07-17T15:00:00+08:00")))
                .isInstanceOf(CapabilityArgumentException.class)
                .hasMessageContaining("endAt", "must not be null");

        assertThatThrownBy(() -> registry.mapAndValidate(
                CoreCapabilityIds.CREATE_SCHEDULE,
                objectMapper.createObjectNode()
                        .put("title", "開會")
                        .put("startAt", "2026-07-17T15:00:00+08:00")
                        .put("endAt", "2026-07-17T14:00:00+08:00")))
                .isInstanceOf(CapabilityArgumentException.class)
                .hasMessageContaining("endAt must be after startAt");

        assertThatThrownBy(() -> registry.mapAndValidate(
                CoreCapabilityIds.CREATE_SCHEDULE,
                objectMapper.createObjectNode()
                        .put("title", "週會")
                        .put("startAt", "2026-07-17T15:00:00+08:00")
                        .put("endAt", "2026-07-17T16:00:00+08:00")
                        .put("recurrence", "WEEKLY")
                        .put("recurrenceUntil", "2026-07-16")))
                .isInstanceOf(CapabilityArgumentException.class)
                .hasMessageContaining("recurrenceUntil must not be before");
    }

    @Test
    void taskTargetsAndInventoryOperationsRejectAmbiguousOrInvalidArguments() {
        CapabilityRegistry registry = registry();

        assertThatThrownBy(() -> registry.mapAndValidate(
                CoreCapabilityIds.CANCEL_TASK, objectMapper.createObjectNode()))
                .isInstanceOf(CapabilityArgumentException.class)
                .hasMessageContaining("task title or ordinal is required");

        assertThatThrownBy(() -> registry.mapAndValidate(
                CoreCapabilityIds.REMOVE_TASK_PLACE,
                objectMapper.createObjectNode().put("placeName", "全聯")))
                .isInstanceOf(CapabilityArgumentException.class)
                .hasMessageContaining("task title or ordinal is required");

        assertThatThrownBy(() -> registry.mapAndValidate(
                CoreCapabilityIds.SET_INVENTORY,
                objectMapper.createObjectNode().put("itemName", "牛奶")))
                .isInstanceOf(CapabilityArgumentException.class)
                .hasMessageContaining("quantity", "must not be null");

        assertThatThrownBy(() -> registry.mapAndValidate(
                CoreCapabilityIds.ADJUST_INVENTORY,
                objectMapper.createObjectNode().put("itemName", "牛奶").put("delta", 0)))
                .isInstanceOf(CapabilityArgumentException.class)
                .hasMessageContaining("inventory delta must not be zero");
    }

    private CapabilityRegistry registry() {
        return new CapabilityRegistry(handlers(), objectMapper, validator);
    }

    private List<CapabilityHandler<?>> handlers() {
        return List.of(
                configuration.createTaskCapability(),
                configuration.createScheduleCapability(),
                configuration.askTaskInfoCapability(),
                configuration.askPriceHistoryCapability(),
                configuration.cancelTaskCapability(),
                configuration.removeTaskPlaceCapability(),
                configuration.setInventoryCapability(),
                configuration.adjustInventoryCapability(),
                configuration.listSchedulesOnDateCapability(),
                configuration.explainLastFailureCapability());
    }
}
