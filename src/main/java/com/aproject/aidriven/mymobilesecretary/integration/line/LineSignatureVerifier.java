package com.aproject.aidriven.mymobilesecretary.integration.line;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * LINE webhook 簽章驗證:HMAC-SHA256(channel secret, raw body),Base64 編碼後
 * 與 X-Line-Signature 標頭比對。
 *
 * 關鍵規則:任何人都能對我們的 webhook URL 送假請求——沒驗簽等於後端任意執行使用者的話,
 * 這是外部輸入的安全邊界,不可跳過。用固定時間比較避免 timing attack。
 */
@Component
public class LineSignatureVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * 驗證簽章是否吻合。
     *
     * @param rawBody   請求原始 body bytes(必須是簽章前未被任何框架改動過的原文)
     * @param signature X-Line-Signature 標頭值(Base64)
     */
    public boolean verify(byte[] rawBody, String signature, String channelSecret) {
        if (signature == null || signature.isBlank() || channelSecret.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(channelSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            String expected = Base64.getEncoder().encodeToString(mac.doFinal(rawBody));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 演算法/金鑰問題視同驗證失敗,不得放行
            return false;
        }
    }
}
