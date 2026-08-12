package com.aproject.aidriven.mymobilesecretary.intent.application;

/** Shared ADD/ADHD-friendly renderer for every public multi-choice question. */
public final class PublicConversationChoiceRenderer {

    private PublicConversationChoiceRenderer() {}

    public static String render(PublicConversationChoiceQuestion question) {
        StringBuilder rendered = new StringBuilder(question.prompt().strip());
        for (int index = 0; index < question.choices().size(); index++) {
            PublicConversationChoice choice = question.choices().get(index);
            rendered.append("\n\n")
                    .append(index + 1)
                    .append(". ")
                    .append(choice.label())
                    .append("：")
                    .append(choice.effect());
        }
        return rendered.toString();
    }
}
