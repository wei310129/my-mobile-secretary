package com.aproject.aidriven.mymobilesecretary.integration.places;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Google Places API(New)設定。api-key 放 secrets.yaml(gitignored);
 * 這是計費服務(Text Search 按次數計),使用者 2026-07-15 拍板採用。
 *
 * @param enabled 關閉時建地點必須自帶座標
 */
@ConfigurationProperties(prefix = "app.integration.google-places")
public record GooglePlacesProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("") String apiKey,
        @DefaultValue("https://places.googleapis.com") String baseUrl,
        @DefaultValue("5s") Duration timeout
) {

    /** 有金鑰才真的可用。 */
    public boolean usable() {
        return enabled && !apiKey.isBlank();
    }
}
