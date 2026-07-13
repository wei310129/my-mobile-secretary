package com.aproject.aidriven.mymobilesecretary.integration.line;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LINE Messaging API 設定。channel-secret / channel-access-token 放 secrets.yaml(gitignored)。
 *
 * @param enabled 關閉時 webhook 直接回 200 不處理(測試環境必關,避免驗簽卡住)
 */
@ConfigurationProperties(prefix = "app.integration.line")
public record LineProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("") String channelSecret,
        @DefaultValue("") String channelAccessToken,
        @DefaultValue("https://api.line.me") String apiBaseUrl,
        @DefaultValue("5s") Duration timeout
) {

    /** 有密鑰才算真的可用。 */
    public boolean usable() {
        return enabled && !channelSecret.isBlank() && !channelAccessToken.isBlank();
    }
}
