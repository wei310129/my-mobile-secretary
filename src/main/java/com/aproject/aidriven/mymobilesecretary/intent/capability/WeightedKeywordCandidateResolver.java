package com.aproject.aidriven.mymobilesecretary.intent.capability;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Deterministic, in-process candidate retrieval over public descriptor metadata.
 *
 * <p>The precomputed index contains capability phrases and keywords only. Messages and other
 * user-scoped data are never cached.</p>
 */
@Component
public final class WeightedKeywordCandidateResolver implements CandidateResolver {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Comparator<CapabilityCandidate> CANDIDATE_ORDER = Comparator
            .comparingLong(CapabilityCandidate::score).reversed()
            .thenComparing(candidate -> candidate.descriptor().id())
            .thenComparing(Comparator.comparingInt(
                    (CapabilityCandidate candidate) -> candidate.descriptor().version()).reversed());

    private final List<IndexedCapability> index;

    public WeightedKeywordCandidateResolver(CapabilityRegistry registry) {
        this.index = registry.activeDescriptors().stream()
                .map(WeightedKeywordCandidateResolver::index)
                .toList();
    }

    @Override
    public List<CapabilityCandidate> resolve(String message, int limit) {
        if (limit < 1 || limit > MAX_CANDIDATES) {
            throw new IllegalArgumentException("candidate limit must be between 1 and " + MAX_CANDIDATES);
        }
        String normalizedMessage = normalize(message);
        if (normalizedMessage.isEmpty()) {
            return List.of();
        }

        List<CapabilityCandidate> candidates = new ArrayList<>();
        for (IndexedCapability capability : index) {
            Score score = score(normalizedMessage, capability);
            if (score.value() > 0) {
                candidates.add(new CapabilityCandidate(
                        capability.descriptor(), score.value(), score.matchedTerms()));
            }
        }
        return candidates.stream().sorted(CANDIDATE_ORDER).limit(limit).toList();
    }

    private static IndexedCapability index(CapabilityDescriptor descriptor) {
        List<String> phrases = descriptor.phrases().stream()
                .map(WeightedKeywordCandidateResolver::normalize)
                .filter(value -> !value.isEmpty())
                .distinct()
                .sorted()
                .toList();
        List<String> keywords = descriptor.keywords().stream()
                .map(WeightedKeywordCandidateResolver::normalize)
                .filter(value -> !value.isEmpty())
                .filter(value -> !phrases.contains(value))
                .distinct()
                .sorted()
                .toList();
        return new IndexedCapability(descriptor, phrases, keywords);
    }

    private static Score score(String message, IndexedCapability capability) {
        long total = 0;
        Set<String> matchedTerms = new LinkedHashSet<>();
        for (String phrase : capability.phrases()) {
            long contribution = contribution(message, phrase, 1_000, 200);
            if (contribution > 0) {
                total += contribution;
                matchedTerms.add(phrase);
            }
        }
        for (String keyword : capability.keywords()) {
            long contribution = contribution(message, keyword, 300, 50);
            if (contribution > 0) {
                total += contribution;
                matchedTerms.add(keyword);
            }
        }
        return new Score(total, matchedTerms.stream().sorted().toList());
    }

    private static long contribution(String message, String term, long exactWeight, long containsWeight) {
        int lengthBonus = Math.min(term.codePointCount(0, term.length()), 50);
        if (message.equals(term)) {
            return exactWeight + lengthBonus;
        }
        if (message.contains(term)) {
            return containsWeight + lengthBonus;
        }
        return 0;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .strip();
        return WHITESPACE.matcher(normalized).replaceAll(" ");
    }

    private record IndexedCapability(
            CapabilityDescriptor descriptor,
            List<String> phrases,
            List<String> keywords) {
    }

    private record Score(long value, List<String> matchedTerms) {
    }
}
