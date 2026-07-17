package com.aproject.aidriven.mymobilesecretary.integration.line;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * LINE 擁有者守門:系統是單人設計(多使用者是 Phase 5),
 * 官方帳號卻任何人都能加好友——不擋發訊者,誰都能看/改擁有者的行程(隱私洩漏)。
 *
 * 規則:
 * - 已設定 owner-user-id → 只放行擁有者,其他人一律忽略(不回覆,避免與陌生人互動)。
 * - 未設定 → 全部拒絕(fail-closed),事件仍由 webhook 記成 BLOCKED,
 *   讓擁有者從紀錄取得自己的 userId 後設定。
 */
@Component
public class LineOwnerGuard {

    private static final Logger log = LoggerFactory.getLogger(LineOwnerGuard.class);

    private final LineProperties properties;

    public LineOwnerGuard(LineProperties properties) {
        this.properties = properties;
    }

    /** 這位發訊者可否使用本系統。 */
    public boolean allows(String sourceUserId) {
        if (properties.ownerUserId().isBlank()) {
            log.error("LINE owner-user-id not configured; blocking incoming message until "
                    + "app.integration.line.owner-user-id is set");
            return false;
        }
        boolean allowed = properties.ownerUserId().equals(sourceUserId);
        if (!allowed) {
            log.warn("Ignoring LINE message from an unrecognized sender");
        }
        return allowed;
    }
}
