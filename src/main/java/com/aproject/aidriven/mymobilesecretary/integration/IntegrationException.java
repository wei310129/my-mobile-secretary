package com.aproject.aidriven.mymobilesecretary.integration;

/**
 * 外部 API 呼叫失敗(timeout、非 2xx、格式錯誤、無資料)。
 *
 * 關鍵規則:呼叫端必須決定 fallback(如交通估算退回直線粗估),
 * 絕不能讓這個例外往上炸到提醒/行程核心。
 */
public class IntegrationException extends RuntimeException {

    public IntegrationException(String message) {
        super(message);
    }

    public IntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
