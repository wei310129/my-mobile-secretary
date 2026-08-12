package com.aproject.aidriven.mymobilesecretary.conversation.application;

import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded lexical extraction; business state and candidate validation remain typed services. */
final class RoutePlaceCreationPhrasePolicy {

    private static final Pattern DECLARATION = Pattern.compile(
            "^(?:(?:好|好的|可以|沒問題)[，,\\s]*)?"
                    + "(?<alias>[\\p{IsHan}\\p{L}\\p{N}]{1,20}?)(?:地點|的位置|位置)?"
                    + "(?:位於|就在|就是|在|是)(?<query>[^，。！？!?；;\\r\\n]{2,120})$");

    private static final Pattern OTHER_OPERATION = Pattern.compile(
            ".*(?:建立|新增|安排|規劃|規畫|提醒|修改|取消|查詢|查).*(?:行程|活動|提醒|待辦|任務|路線).*");

    private static final Pattern INCOMPATIBLE_CHILD_INPUT = Pattern.compile(
            ".*(?:不是我要|不對|有問題|沒聽懂|聽不懂|現在處理到哪|現在在處理|要怎麼做|為什麼).*");

    private RoutePlaceCreationPhrasePolicy() {}

    static Optional<Request> parse(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String normalized = Normalizer.normalize(text.strip(), Normalizer.Form.NFKC);
        Matcher matcher = DECLARATION.matcher(normalized);
        if (!matcher.matches()) return Optional.empty();
        String alias = matcher.group("alias").strip();
        String query = matcher.group("query").strip();
        if (alias.equals(query)) return Optional.empty();
        return Optional.of(new Request(alias, query));
    }

    static boolean clearlyStartsAnotherOperation(String text) {
        if (text == null || text.isBlank()) return false;
        String normalized = Normalizer.normalize(text.strip(), Normalizer.Form.NFKC);
        return OTHER_OPERATION.matcher(normalized).matches();
    }

    static boolean clearlyIncompatibleWithPlaceDetails(String text) {
        if (text == null || text.isBlank()) return false;
        String normalized = Normalizer.normalize(text.strip(), Normalizer.Form.NFKC);
        return clearlyStartsAnotherOperation(normalized)
                || INCOMPATIBLE_CHILD_INPUT.matcher(normalized).matches();
    }

    record Request(String alias, String query) {}
}
