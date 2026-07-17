package com.aproject.aidriven.mymobilesecretary.intent.capability.routing;

/** Heuristic comparison of candidate metadata against the legacy full-catalog prompt. */
public record CapabilityPromptSavings(
        boolean available,
        int legacyCharacters,
        int candidateCharacters,
        int estimatedCharacterSavings,
        int legacyEstimatedTokens,
        int candidateEstimatedTokens,
        int estimatedTokenSavings) {

    static CapabilityPromptSavings estimate(
            int legacyCharacters,
            int candidateCharacters,
            double charactersPerToken) {
        if (legacyCharacters <= 0 || candidateCharacters < 0 || charactersPerToken <= 0) {
            throw new IllegalArgumentException("prompt estimate inputs must be positive");
        }
        int legacyTokens = tokens(legacyCharacters, charactersPerToken);
        int candidateTokens = tokens(candidateCharacters, charactersPerToken);
        return new CapabilityPromptSavings(
                true,
                legacyCharacters,
                candidateCharacters,
                Math.max(legacyCharacters - candidateCharacters, 0),
                legacyTokens,
                candidateTokens,
                Math.max(legacyTokens - candidateTokens, 0));
    }

    static CapabilityPromptSavings unavailable() {
        return new CapabilityPromptSavings(false, 0, 0, 0, 0, 0, 0);
    }

    private static int tokens(int characters, double charactersPerToken) {
        return (int) Math.ceil(characters / charactersPerToken);
    }
}
