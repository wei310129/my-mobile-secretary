package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.regex.Pattern;

/** Identifies a reported event notice by speech act instead of a closed sender-role list. */
final class ReportedEventNoticePolicy {

    private static final Pattern REPORTED_SPEECH_ACT = Pattern.compile(
            "[^，,。；;]{1,24}(?:通知|告知|提醒|說)[^，,。；;]{0,100}");

    private ReportedEventNoticePolicy() {
    }

    static boolean isReportedNotice(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        if (compact.isBlank() || compact.contains("提醒我")) {
            return false;
        }
        return REPORTED_SPEECH_ACT.matcher(compact).find();
    }
}
