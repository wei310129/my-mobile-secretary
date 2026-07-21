package com.aproject.aidriven.mymobilesecretary.conversation.application;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Immutable registry that refuses ambiguous or absent domain validation. */
@Component
public final class ConversationFocusContributorRegistry {

    private final Map<String, ConversationFocusContributor> contributors;

    public ConversationFocusContributorRegistry(List<ConversationFocusContributor> contributors) {
        Objects.requireNonNull(contributors, "contributors");
        this.contributors = contributors.stream().collect(Collectors.toUnmodifiableMap(
                contributor -> requiredDomain(contributor), Function.identity(), (first, duplicate) -> {
                    throw new IllegalStateException("duplicate focus contributor for root domain "
                            + requiredDomain(first));
                }));
    }

    public ConversationFocusContributor require(String rootDomain) {
        ConversationFocusContributor contributor = contributors.get(requiredDomain(rootDomain));
        if (contributor == null) {
            throw new IllegalStateException("missing focus contributor for root domain " + rootDomain);
        }
        return contributor;
    }

    private static String requiredDomain(ConversationFocusContributor contributor) {
        return requiredDomain(Objects.requireNonNull(contributor, "contributor").rootDomain());
    }

    private static String requiredDomain(String rootDomain) {
        if (rootDomain == null || rootDomain.isBlank()) {
            throw new IllegalArgumentException("focus root domain is required");
        }
        return rootDomain.strip();
    }
}
