package com.aproject.aidriven.mymobilesecretary.shared.error;

/**
 * 業務規則不允許的操作,例如任務狀態不允許轉換。
 *
 * 規則:code 是機器可讀錯誤碼(如 INVALID_STATE_TRANSITION),
 * 由 GlobalExceptionHandler 統一轉成 HTTP 422 的 ErrorResponse。
 */
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
