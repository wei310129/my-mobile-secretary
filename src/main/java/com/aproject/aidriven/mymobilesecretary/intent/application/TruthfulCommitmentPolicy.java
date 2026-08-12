package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.Set;

/** Fails closed when legacy public text makes a commitment without matching typed evidence. */
final class TruthfulCommitmentPolicy {

    private TruthfulCommitmentPolicy() {
    }

    static String enforce(String message, Set<PublicReplyEvidence> evidence) {
        if (message == null || message.isBlank()) {
            return message;
        }
        Set<PublicReplyEvidence> verified = evidence == null ? Set.of() : evidence;
        if (claimsDurableMemory(message)
                && !containsAny(verified,
                        PublicReplyEvidence.PREFERENCE_COMMITTED,
                        PublicReplyEvidence.MUTATION_COMMITTED)) {
            return "收到。這項內容目前沒有保存成可長期沿用的偏好。";
        }
        if (claimsRepairCompleted(message)
                && !verified.contains(PublicReplyEvidence.REPAIR_COMPLETED)) {
            return "收到，這次沒有符合您的期待。";
        }
        if (claimsRepairStarted(message)
                && !containsAny(verified,
                        PublicReplyEvidence.REPAIR_STARTED,
                        PublicReplyEvidence.REPAIR_COMPLETED)) {
            return "收到。您可以先告訴我最需要釐清的部分。";
        }
        if (claimsTerminalNotification(message)
                && !(verified.contains(PublicReplyEvidence.ASYNC_COMMITTED)
                        && verified.contains(PublicReplyEvidence.NOTIFICATION_COMMITTED))) {
            return "收到，但這項處理目前尚未建立可靠的完成通知。";
        }
        return message;
    }

    private static boolean claimsDurableMemory(String value) {
        return containsAny(value, "我會記住", "我已記住", "已記住長期", "我已記下這項偏好");
    }

    private static boolean claimsRepairCompleted(String value) {
        return containsAny(value, "我已重新處理", "我已重新整理", "已依您的要求重新處理");
    }

    private static boolean claimsRepairStarted(String value) {
        return containsAny(value,
                "我會重新處理", "我會依您", "我會調整", "我會從原操作續接",
                "我會保留問題供後續修正", "我會接著處理");
    }

    private static boolean claimsTerminalNotification(String value) {
        return containsAny(value, "完成後會通知您", "完成後會通知你", "處理完成後會通知您");
    }

    private static boolean containsAny(
            Set<PublicReplyEvidence> evidence, PublicReplyEvidence... candidates) {
        for (PublicReplyEvidence candidate : candidates) {
            if (evidence.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
