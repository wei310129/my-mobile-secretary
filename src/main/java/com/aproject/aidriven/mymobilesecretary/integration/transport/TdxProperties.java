package com.aproject.aidriven.mymobilesecretary.integration.transport;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * TDX 運輸資料流通服務設定。client-id/secret 放 secrets.yaml(gitignored)。
 *
 * @param enabled 關閉時交通估算直接用直線粗估(測試環境必關,避免打真實 API)
 */
@ConfigurationProperties(prefix = "app.integration.tdx")
public record TdxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("https://tdx.transportdata.tw/api") String baseUrl,
        @DefaultValue("https://tdx.transportdata.tw/auth/realms/TDXConnect/protocol/openid-connect/token") String tokenUrl,
        @DefaultValue("") String clientId,
        @DefaultValue("") String clientSecret,
        @DefaultValue("8s") Duration timeout
) {

    /** 有憑證且啟用才算可用。 */
    public boolean usable() {
        return enabled && !clientId.isBlank() && !clientSecret.isBlank();
    }
}
