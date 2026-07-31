package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.regex.Pattern;

/** Classifies a transport responsibility by action role, not by a closed kinship word list. */
final class TransportSemanticPolicy {

    private static final Pattern TRANSPORT_TO_DEPENDENT_ACTIVITY = Pattern.compile(
            "(?:送|接送|載|校車接|接回|接(?!著|受|續))[^，。；;]{0,36}"
                    + "(?:上課|下課|放學|學校|補習|安親|才藝|課後班)"
                    + "|(?:下課|放學)[^，。；;]{0,24}"
                    + "(?:送|接送|載|接回|接(?!著|受|續))");

    private TransportSemanticPolicy() {
    }

    static boolean isTransportToDependentActivity(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        return TRANSPORT_TO_DEPENDENT_ACTIVITY.matcher(compact).find();
    }
}
