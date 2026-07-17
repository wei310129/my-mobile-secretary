package com.aproject.aidriven.mymobilesecretary.intent.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WeightedKeywordCandidateResolverTest {

    @Test
    void ranksExactPhrasesAbovePartialKeywordsAndNormalizesUnicode() {
        CapabilityHandler<TestArguments> schedule = handler(
                "schedule.list_today",
                List.of("查看今天行程"),
                Set.of("行程", "今天"));
        CapabilityHandler<TestArguments> task = handler(
                "task.list_today",
                List.of("今天要做什麼"),
                Set.of("待辦", "今天"));
        CapabilityHandler<TestArguments> ascii = handler(
                "system.ascii",
                List.of(),
                Set.of("abc"));
        WeightedKeywordCandidateResolver resolver = resolver(List.of(task, schedule, ascii));

        List<CapabilityCandidate> candidates = resolver.resolve("  查看今天行程  ", 12);

        assertThat(candidates).extracting(candidate -> candidate.descriptor().id().value())
                .containsExactly("schedule.list_today", "task.list_today");
        assertThat(candidates.getFirst().matchedTerms()).contains("查看今天行程", "今天", "行程");
        assertThat(candidates.getFirst().score()).isGreaterThan(candidates.get(1).score());

        assertThat(resolver.resolve("ＡＢＣ", 1).getFirst().descriptor().id().value())
                .isEqualTo("system.ascii");
    }

    @Test
    void usesStableIdOrderingForEqualScoresAndHonorsTopK() {
        WeightedKeywordCandidateResolver resolver = resolver(List.of(
                handler("task.beta", List.of(), Set.of("共同")),
                handler("task.alpha", List.of(), Set.of("共同")),
                handler("task.gamma", List.of(), Set.of("共同"))));

        List<CapabilityCandidate> first = resolver.resolve("共同", 2);
        List<CapabilityCandidate> second = resolver.resolve("共同", 2);

        assertThat(first).isEqualTo(second);
        assertThat(first).extracting(candidate -> candidate.descriptor().id().value())
                .containsExactly("task.alpha", "task.beta");
    }

    @Test
    void validatesLimitAndReturnsNoCandidatesForBlankOrUnmatchedInput() {
        WeightedKeywordCandidateResolver resolver = resolver(List.of(
                handler("task.create", List.of("新增待辦"), Set.of("待辦"))));

        assertThat(resolver.resolve(" ", 12)).isEmpty();
        assertThat(resolver.resolve("完全無關", 12)).isEmpty();
        assertThatThrownBy(() -> resolver.resolve("待辦", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 12");
        assertThatThrownBy(() -> resolver.resolve("待辦", 13))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 12");
    }

    @Test
    void retrievesDeterministicallyFromOneThousandAndTwentyCapabilities() {
        List<CapabilityHandler<?>> handlers = new ArrayList<>();
        for (int index = 0; index < 1_020; index++) {
            String suffix = "%04d".formatted(index);
            handlers.add(handler(
                    "synthetic.capability_" + suffix,
                    List.of("執行合成能力 " + suffix),
                    Set.of("marker-" + suffix + "-end")));
        }
        CapabilityRegistry registry = registry(handlers);
        WeightedKeywordCandidateResolver resolver = new WeightedKeywordCandidateResolver(registry);

        List<CapabilityCandidate> candidates = resolver.resolve("marker-0777-end", 12);

        assertThat(registry.activeDescriptors()).hasSize(1_020);
        assertThat(candidates).isNotEmpty().hasSizeLessThanOrEqualTo(12);
        assertThat(candidates.getFirst().descriptor().id().value())
                .isEqualTo("synthetic.capability_0777");
        assertThat(resolver.resolve("marker-0777-end", 12)).isEqualTo(candidates);
    }

    private static WeightedKeywordCandidateResolver resolver(List<CapabilityHandler<?>> handlers) {
        return new WeightedKeywordCandidateResolver(registry(handlers));
    }

    private static CapabilityRegistry registry(List<CapabilityHandler<?>> handlers) {
        return new CapabilityRegistry(
                handlers,
                new ObjectMapper().findAndRegisterModules(),
                Validation.buildDefaultValidatorFactory().getValidator());
    }

    private static CapabilityHandler<TestArguments> handler(
            String id, List<String> phrases, Set<String> keywords) {
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
                CapabilityId.of(id),
                1,
                CapabilityDomain.TASK,
                CapabilityRisk.QUERY,
                TestArguments.class,
                "Synthetic test capability",
                Set.of(),
                phrases,
                keywords);
        return new CapabilityHandler<>() {
            @Override
            public CapabilityDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public Class<TestArguments> inputType() {
                return TestArguments.class;
            }
        };
    }

    private record TestArguments(String value) {
    }
}
