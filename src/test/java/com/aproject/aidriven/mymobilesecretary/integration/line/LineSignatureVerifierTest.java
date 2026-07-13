package com.aproject.aidriven.mymobilesecretary.integration.line;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * LINE webhook 簽章驗證測試:正確簽章放行、任何不吻合都擋下。
 * webhook 是對外公開端點,這是唯一的安全邊界,測試要涵蓋所有繞過方式。
 */
class LineSignatureVerifierTest {

    private static final String SECRET = "test-channel-secret";
    private final LineSignatureVerifier verifier = new LineSignatureVerifier();

    /** 依 LINE 官方演算法算出正確簽章,供測試比對用。 */
    private String sign(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }

    @Test
    void validSignaturePasses() throws Exception {
        byte[] body = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);
        String signature = sign(body, SECRET);

        assertThat(verifier.verify(body, signature, SECRET)).isTrue();
    }

    @Test
    void wrongSecretFails() throws Exception {
        byte[] body = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);
        String signature = sign(body, "different-secret");

        assertThat(verifier.verify(body, signature, SECRET)).isFalse();
    }

    /** body 被竄改(哪怕一個字元)簽章就對不上——這正是驗簽存在的目的。 */
    @Test
    void tamperedBodyFails() throws Exception {
        byte[] original = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);
        String signature = sign(original, SECRET);
        byte[] tampered = "{\"events\":[{\"evil\":true}]}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify(tampered, signature, SECRET)).isFalse();
    }

    @Test
    void missingSignatureFails() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify(body, null, SECRET)).isFalse();
        assertThat(verifier.verify(body, "", SECRET)).isFalse();
    }

    @Test
    void malformedBase64SignatureFailsWithoutThrowing() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify(body, "not-even-base64!!!", SECRET)).isFalse();
    }

    @Test
    void blankChannelSecretFails() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String signature = sign(body, SECRET);

        assertThat(verifier.verify(body, signature, "")).isFalse();
    }
}
