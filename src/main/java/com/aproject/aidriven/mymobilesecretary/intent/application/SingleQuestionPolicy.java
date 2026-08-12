package com.aproject.aidriven.mymobilesecretary.intent.application;

/** Deterministically reduces a legacy needs-input message to one answerable prompt. */
final class SingleQuestionPolicy {

    private SingleQuestionPolicy() {
    }

    static PublicConversationReply.NextQuestion legacy(String message) {
        return new PublicConversationReply.NextQuestion(
                "conversation.follow-up", firstQuestion(message));
    }

    static String enforce(String message, PublicConversationReply.NextQuestion nextQuestion) {
        if (nextQuestion == null) {
            return message;
        }
        String prompt = nextQuestion.prompt().strip();
        if (questionCount(message) <= 1 && message.contains(prompt)) {
            return message;
        }
        return prompt;
    }

    private static String firstQuestion(String message) {
        if (message == null || message.isBlank()) {
            return "請告訴我這次要接著處理的內容。";
        }
        String normalized = message.replace('\r', '\n').strip();
        int questionMark = firstQuestionMark(normalized);
        if (questionMark >= 0) {
            int start = Math.max(normalized.lastIndexOf('\n', questionMark),
                    normalized.lastIndexOf('。', questionMark));
            return normalized.substring(start + 1, questionMark + 1).strip();
        }
        String[] lines = normalized.split("\\n+");
        for (int index = lines.length - 1; index >= 0; index--) {
            String candidate = lines[index].replaceFirst("^[\\s\\-•*]+", "").strip();
            if (!candidate.isBlank()) {
                return candidate;
            }
        }
        return "請告訴我這次要接著處理的內容。";
    }

    private static int firstQuestionMark(String value) {
        int fullWidth = value.indexOf('？');
        int ascii = value.indexOf('?');
        if (fullWidth < 0) return ascii;
        if (ascii < 0) return fullWidth;
        return Math.min(fullWidth, ascii);
    }

    private static long questionCount(String value) {
        return value.chars().filter(character -> character == '？' || character == '?').count();
    }
}
