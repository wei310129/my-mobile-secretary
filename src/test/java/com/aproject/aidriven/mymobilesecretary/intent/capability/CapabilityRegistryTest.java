package com.aproject.aidriven.mymobilesecretary.intent.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class CapabilityRegistryTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void exposesDeterministicDescriptorsDomainsAndExactVersions() {
        TestHandler<TaskArguments> taskV1 = handler(
                descriptor("task.create", 1, CapabilityDomain.TASK, TaskArguments.class),
                TaskArguments.class);
        TestHandler<TaskArguments> taskV2 = handler(
                descriptor("task.create", 2, CapabilityDomain.TASK, TaskArguments.class),
                TaskArguments.class);
        TestHandler<ScheduleArguments> schedule = handler(
                descriptor("schedule.create", 1, CapabilityDomain.SCHEDULE, ScheduleArguments.class),
                ScheduleArguments.class);

        CapabilityRegistry registry = registry(List.of(taskV1, schedule, taskV2));

        assertThat(registry.descriptors())
                .extracting(descriptor -> descriptor.id() + ":" + descriptor.version())
                .containsExactly("schedule.create:1", "task.create:2", "task.create:1");
        assertThat(registry.activeDescriptors())
                .extracting(descriptor -> descriptor.id() + ":" + descriptor.version())
                .containsExactly("schedule.create:1", "task.create:2");
        assertThat(registry.descriptors(CapabilityDomain.TASK)).containsExactly(taskV2.descriptor());
        assertThat(registry.handler(CapabilityId.of("task.create"))).contains(taskV2);
        assertThat(registry.handler(CapabilityId.of("task.create"), 1)).contains(taskV1);
        assertThat(registry.descriptor(CapabilityId.of("missing"))).isEmpty();
    }

    @Test
    void rejectsDuplicateIdAndVersionAtStartup() {
        CapabilityDescriptor descriptor = descriptor(
                "task.create", 1, CapabilityDomain.TASK, TaskArguments.class);

        assertThatThrownBy(() -> registry(List.of(
                handler(descriptor, TaskArguments.class),
                handler(descriptor, TaskArguments.class))))
                .isInstanceOf(CapabilityRegistryException.class)
                .hasMessageContaining("duplicate capability registration", "task.create", "v1");
    }

    @Test
    void rejectsDescriptorAndHandlerInputTypeMismatchAtStartup() {
        CapabilityDescriptor descriptor = descriptor(
                "task.create", 1, CapabilityDomain.TASK, TaskArguments.class);

        assertThatThrownBy(() -> registry(List.of(handler(descriptor, ScheduleArguments.class))))
                .isInstanceOf(CapabilityRegistryException.class)
                .hasMessageContaining("does not match handler inputType", "task.create");
    }

    @Test
    void mapsArgumentsToTheRegisteredTypeAndRunsBothValidationLayers() {
        TestHandler<TaskArguments> handler = handler(
                descriptor("task.create", 1, CapabilityDomain.TASK, TaskArguments.class),
                TaskArguments.class,
                arguments -> {
                    if (arguments.minutes() % 2 != 0) {
                        throw new IllegalArgumentException("minutes must be even");
                    }
                });
        CapabilityRegistry registry = registry(List.of(handler));

        ValidatedCapabilityCall<TaskArguments> call = registry.mapAndValidate(
                CapabilityId.of("task.create"),
                1,
                TaskArguments.class,
                objectMapper.createObjectNode().put("title", "倒垃圾").put("minutes", 10));

        assertThat(call.handler()).isSameAs(handler);
        assertThat(call.arguments()).isEqualTo(new TaskArguments("倒垃圾", 10));
        assertThat(call.descriptor()).isEqualTo(handler.descriptor());

        assertThatThrownBy(() -> registry.mapAndValidate(
                CapabilityId.of("task.create"),
                objectMapper.createObjectNode().put("title", " ").put("minutes", 10)))
                .isInstanceOf(CapabilityArgumentException.class)
                .hasMessageContaining("title", "must not be blank");

        assertThatThrownBy(() -> registry.mapAndValidate(
                CapabilityId.of("task.create"),
                objectMapper.createObjectNode().put("title", "倒垃圾").put("minutes", 9)))
                .isInstanceOf(CapabilityArgumentException.class)
                .hasMessageContaining("domain validation failed", "minutes must be even");
    }

    @Test
    void rejectsUnknownFieldsWrongShapesTypesAndVersionsWithoutEchoingInput() {
        CapabilityRegistry registry = registry(List.of(handler(
                descriptor("task.create", 1, CapabilityDomain.TASK, TaskArguments.class),
                TaskArguments.class)));

        assertThatThrownBy(() -> registry.mapAndValidate(
                CapabilityId.of("task.create"),
                objectMapper.createObjectNode()
                        .put("title", "secret title")
                        .put("minutes", 10)
                        .put("workspaceId", "another-workspace")))
                .isInstanceOf(CapabilityArgumentException.class)
                .hasMessageContaining("unknown capability argument", "workspaceId")
                .hasMessageNotContaining("secret title")
                .hasMessageNotContaining("another-workspace");

        assertThatThrownBy(() -> registry.mapAndValidate(
                CapabilityId.of("task.create"), objectMapper.createArrayNode()))
                .isInstanceOf(CapabilityArgumentException.class)
                .hasMessageContaining("must be a JSON object");

        assertThatThrownBy(() -> registry.mapAndValidate(
                CapabilityId.of("task.create"),
                objectMapper.createObjectNode().put("title", "test").put("minutes", "many")))
                .isInstanceOf(CapabilityArgumentException.class)
                .hasMessageContaining("type mismatch", "minutes")
                .hasMessageNotContaining("many");

        assertThatThrownBy(() -> registry.mapAndValidate(
                CapabilityId.of("task.create"),
                99,
                objectMapper.createObjectNode()))
                .isInstanceOf(CapabilityArgumentException.class)
                .hasMessageContaining("unknown capability or version");

        assertThatThrownBy(() -> registry.mapAndValidate(
                CapabilityId.of("task.create"),
                1,
                ScheduleArguments.class,
                objectMapper.createObjectNode()))
                .isInstanceOf(CapabilityArgumentException.class)
                .hasMessageContaining("input type does not match");
    }

    private CapabilityRegistry registry(List<CapabilityHandler<?>> handlers) {
        return new CapabilityRegistry(handlers, objectMapper, VALIDATOR);
    }

    private static CapabilityDescriptor descriptor(
            String id, int version, CapabilityDomain domain, Class<?> inputType) {
        return new CapabilityDescriptor(
                CapabilityId.of(id),
                version,
                domain,
                CapabilityRisk.MUTATION,
                inputType,
                "Test capability " + id,
                Set.of(ContextRequirement.CONVERSATION_HISTORY),
                List.of("建立測試"),
                Set.of("測試"));
    }

    private static <P> TestHandler<P> handler(CapabilityDescriptor descriptor, Class<P> inputType) {
        return handler(descriptor, inputType, ignored -> { });
    }

    private static <P> TestHandler<P> handler(
            CapabilityDescriptor descriptor, Class<P> inputType, Consumer<P> validation) {
        return new TestHandler<>(descriptor, inputType, validation);
    }

    private record TaskArguments(@NotBlank String title, @Min(1) int minutes) {
    }

    private record ScheduleArguments(@NotBlank String title) {
    }

    private record TestHandler<P>(
            CapabilityDescriptor descriptor,
            Class<P> inputType,
            Consumer<P> validation) implements CapabilityHandler<P> {

        @Override
        public void validate(P arguments) {
            validation.accept(arguments);
        }
    }
}
