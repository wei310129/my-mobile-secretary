package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class CalendarParticipationNotificationMessageFactory {

    NotificationRequest create(
            UUID targetUserId,
            UUID outboxId,
            String eventType,
            long sourceRevision) {
        Message message = switch (eventType) {
            case "MANDATORY_UPDATE" -> new Message(
                    "共享行程有必須確認的變更",
                    "重要行程變更已套用到你的個人行程，請確認最新內容。");
            case "REVIEW_REQUIRED_UPDATE" -> new Message(
                    "共享行程變更待確認",
                    "共享行程已有更新，請檢視後決定是否套用到個人行程。");
            case "ROUTINE_UPDATE" -> new Message(
                    "共享行程已更新",
                    "你追蹤的共享行程已有新內容。");
            case "SHARE_ACCESS_REVOKED" -> new Message(
                    "共享行程存取已撤銷",
                    "你已無法存取來源行程；保留的個人副本不會再跟隨來源更新。");
            case "SKIP_CONFIRMED" -> new Message(
                    "已略過行程更新",
                    "這次行程更新已依你的確認略過。");
            default -> throw new IllegalArgumentException(
                    "Unsupported calendar participation event: "
                            + eventType);
        };
        return new NotificationRequest(
                targetUserId,
                deliveryKey(outboxId),
                null,
                null,
                message.title(),
                message.body() + "（來源版本 " + sourceRevision + "）");
    }

    static String deliveryKey(UUID outboxId) {
        return "calendar-participation:" + outboxId;
    }

    private record Message(String title, String body) {
    }
}
