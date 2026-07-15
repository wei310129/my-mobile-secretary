package com.aproject.aidriven.mymobilesecretary.integration.line;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LINE Messaging API 設定。channel-id / channel-secret 放 secrets.yaml(gitignored)。
 *
 * 認證有兩條路,擇一即可(見 {@link LineTokenManager}):
 * - channel-id + channel-secret:自動向 LINE 換發 stateless token(15 分鐘效期),免手動管理。
 * - channel-access-token:直接使用長效 token(測試環境用假值走這條,避免打 token endpoint)。
 *
 * @param enabled 關閉時 webhook 直接回 200 不處理(測試環境必關,避免驗簽卡住)
 */
@ConfigurationProperties(prefix = "app.integration.line")
public record LineProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("") String channelId,
        @DefaultValue("") String channelSecret,
        @DefaultValue("") String channelAccessToken,
        // 擁有者的 LINE userId(secrets.yaml):設定後只服務擁有者,其他人一律忽略——
        // 系統是單人設計(多使用者是 Phase 5),任何加好友的人都能操作等於隱私洩漏
        @DefaultValue("") String ownerUserId,
        @DefaultValue("https://api.line.me") String apiBaseUrl,
        // 訊息內容(圖片等二進位)在 api-data 網域,與訊息 API 不同台
        @DefaultValue("https://api-data.line.me") String contentBaseUrl,
        @DefaultValue("https://api.line.me/oauth2/v3/token") String tokenUrl,
        @DefaultValue("5s") Duration timeout
) {

    /** 有辦法驗簽、也有辦法取得 access token,才算真的可用。 */
    public boolean usable() {
        return enabled && !channelSecret.isBlank()
                && (!channelAccessToken.isBlank() || !channelId.isBlank());
    }
}
