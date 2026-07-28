package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class CalendarRegistrationNotificationMessageFactory {

    NotificationRequest create(
            UUID targetUserId,
            UUID outboxId,
            String eventType,
            String payloadText) {
        String title = switch (eventType) {
            case "REGISTRATION_RESULT" -> "行程報名結果已更新";
            case "LATE_REGISTRATION" -> "行程已完成逾期報名";
            case "WAITLIST_OFFERED" -> "候補名額已釋出";
            case "WAITLIST_OFFER_EXPIRED" -> "候補名額邀請已到期";
            case "WITHDRAWN_BY_USER" -> "行程報名已退出";
            case "REMOVED_BY_ORGANIZER" -> "行程報名已由管理者移除";
            case "CAPACITY_OVER_CAPACITY" -> "行程報名人數超出容量";
            case "PARTICIPANT_MINIMUM_ACCESS" -> "已保留參與者最低存取權";
            case "OWNERSHIP_TRANSFER_OFFERED" -> "行程所有權移轉待確認";
            case "OWNERSHIP_TRANSFER_ACCEPTED" -> "行程所有權移轉已接受";
            case "OWNERSHIP_TRANSFER_CANCELED" -> "行程所有權移轉已取消";
            case "OWNERSHIP_TRANSFER_EXPIRED" -> "行程所有權移轉已到期";
            default -> throw new IllegalArgumentException(
                    "Unsupported calendar registration event: "
                            + eventType);
        };
        return new NotificationRequest(
                targetUserId,
                deliveryKey(outboxId),
                null,
                null,
                title,
                payloadText);
    }

    static String deliveryKey(UUID outboxId) {
        return "calendar-registration:" + outboxId;
    }
}
