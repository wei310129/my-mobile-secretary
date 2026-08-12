package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A single question with two or more typed choices and deterministic answer resolution. */
public record PublicConversationChoiceQuestion(
        String code,
        String prompt,
        List<PublicConversationChoice> choices) {

    private static final Pattern DISPLAYED_ORDINAL = Pattern.compile(
            "^(?:(?:我要|我選|請選|選擇|選)?第?|(?:我要|我選|請選|選擇|選))([1-9][0-9]{0,2})(?:選項|個|項)?[.、)]?$");

    public PublicConversationChoiceQuestion {
        if (code == null || code.isBlank() || prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("choice question code and prompt are required");
        }
        if (prompt.contains("\n")) {
            throw new IllegalArgumentException("choice question prompt must fit one line");
        }
        choices = List.copyOf(choices == null ? List.of() : choices);
        if (choices.size() < 2) {
            throw new IllegalArgumentException("a choice question requires at least two options");
        }
        HashSet<String> actionCodes = new HashSet<>();
        for (PublicConversationChoice choice : choices) {
            if (!actionCodes.add(choice.actionCode())) {
                throw new IllegalArgumentException("choice action codes must be unique");
            }
        }
    }

    public Optional<String> resolveAction(String answer) {
        Optional<String> ordinalAction = resolveDisplayedOrdinal(answer);
        if (ordinalAction.isPresent()) return ordinalAction;
        String normalized = normalizeAnswer(answer);
        if (normalized.isEmpty()) return Optional.empty();
        return choices.stream()
                .filter(choice -> matches(choice, normalized))
                .map(PublicConversationChoice::actionCode)
                .findFirst();
    }

    private Optional<String> resolveDisplayedOrdinal(String answer) {
        if (answer == null || answer.isBlank()) return Optional.empty();
        String compact = Normalizer.normalize(answer, Normalizer.Form.NFKC).replaceAll("\\s", "");
        Matcher matcher = DISPLAYED_ORDINAL.matcher(compact);
        if (!matcher.matches()) return Optional.empty();
        int index = Integer.parseInt(matcher.group(1)) - 1;
        if (index < 0 || index >= choices.size()) return Optional.empty();
        return Optional.of(choices.get(index).actionCode());
    }

    private static boolean matches(PublicConversationChoice choice, String normalized) {
        if (normalizeAnswer(choice.label()).equals(normalized)) return true;
        return choice.acceptedAnswers().stream()
                .map(PublicConversationChoiceQuestion::normalizeAnswer)
                .anyMatch(normalized::equals);
    }

    private static String normalizeAnswer(String value) {
        if (value == null || value.isBlank()) return "";
        String compact = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\s，。！？!?：:；;「」『』]", "");
        compact = compact.replaceFirst("^(?:我要|我選|請選|選擇)", "");
        return compact.replaceFirst("(?:就好|即可)$", "");
    }
}
