package com.aproject.aidriven.mymobilesecretary.integration.line;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 擁有者守門測試:設定後只放行擁有者(隱私洩漏的封口);
 * 未設定也拒絕(fail-closed),避免任何陌生人碰到全域資料。
 */
class LineOwnerGuardTest {

    private LineOwnerGuard guard(String ownerUserId) {
        return new LineOwnerGuard(new LineProperties(true, "cid", "secret", "token", ownerUserId,
                "https://api.line.me", "https://api-data.line.me",
                "https://api.line.me/oauth2/v3/token", Duration.ofSeconds(5)));
    }

    /** 已設定擁有者 → 只有擁有者能用;陌生人與無來源事件都擋。 */
    @Test
    void configuredOwnerBlocksEveryoneElse() {
        LineOwnerGuard guard = guard("U-owner");

        assertThat(guard.allows("U-owner")).isTrue();
        assertThat(guard.allows("U-stranger")).isFalse();
        assertThat(guard.allows(null)).isFalse();
    }

    /** 未設定 → 全擋,由 BLOCKED 紀錄引導擁有者設定。 */
    @Test
    void unconfiguredOwnerBlocksAll() {
        LineOwnerGuard guard = guard("");

        assertThat(guard.allows("U-anyone")).isFalse();
        assertThat(guard.allows(null)).isFalse();
    }
}
