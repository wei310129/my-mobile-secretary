package com.aproject.aidriven.mymobilesecretary.shared.error;

import java.time.Instant;
import java.util.List;

/**
 * 全 API 統一的錯誤回應格式。
 *
 * 規則:所有錯誤(validation、business、not found、unexpected)都回這個結構,
 * 讓手機端只需寫一套錯誤解析邏輯。
 *
 * @param code        機器可讀的錯誤碼,例如 VALIDATION_ERROR、NOT_FOUND
 * @param message     人類可讀的錯誤說明
 * @param timestamp   錯誤發生時間
 * @param fieldErrors validation 失敗時的欄位明細;其他錯誤為空清單
 */
public record ErrorResponse(
        String code,
        String message,
        Instant timestamp,
        List<FieldViolation> fieldErrors
) {

    /**
     * 單一欄位的 validation 失敗明細。
     *
     * @param field  欄位名稱
     * @param reason 不通過的原因
     */
    public record FieldViolation(String field, String reason) {
    }

    /** 建立不含欄位明細的錯誤回應(非 validation 類錯誤用)。 */
    public static ErrorResponse of(String code, String message, Instant timestamp) {
        return new ErrorResponse(code, message, timestamp, List.of());
    }
}
