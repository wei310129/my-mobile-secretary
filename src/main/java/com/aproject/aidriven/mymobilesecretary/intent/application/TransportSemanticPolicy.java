package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.regex.Pattern;

/** Classifies a transport responsibility by action role, not by a closed kinship word list. */
final class TransportSemanticPolicy {

    private static final Pattern TRANSPORT_TO_DEPENDENT_ACTIVITY = Pattern.compile(
            "(?:送|接送|載|校車接|接回|接(?!著|受|續))[^，。；;]{0,36}"
                    + "(?:上課|下課|放學|學校|補習|安親|才藝|課後班)"
                    + "|(?:下課|放學)[^，。；;]{0,24}"
                    + "(?:送|接送|載|接回|接(?!著|受|續))"
                    + "|(?:學校|補習班|安親班|才藝班|課後班)[^，。；;]{0,24}"
                    + "(?:接回|接(?!著|受|續))"
                    + "|(?:送|接送|載)[^，。；;]{1,24}(?:去|到|往)[^，。；;]{0,16}"
                    + "(?:上)?[\\p{IsHan}A-Za-z0-9]{1,12}(?:課|課程|班|教室|學校|幼兒園|園所|中心|館)"
                    + "|校車(?:接|送)[^，。；;]{0,36}");
    private static final Pattern NON_PASSENGER_DELIVERY = Pattern.compile(
            "(?:送修|送洗|寄送|配送|運送|送貨|送件|送文件|送資料|送包裹|送餐)");

    private TransportSemanticPolicy() {
    }

    static boolean isTransportToDependentActivity(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        for (String clause : compact.split("[，。；;]|(?:然後|以及|並且)")) {
            if (NON_PASSENGER_DELIVERY.matcher(clause).find()) continue;
            if (TRANSPORT_TO_DEPENDENT_ACTIVITY.matcher(clause).find()) return true;
        }
        return false;
    }
}
