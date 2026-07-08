package com.aproject.aidriven.mymobilesecretary.shared.error;

/**
 * 查無資源,例如任務或地點不存在。
 *
 * 由 GlobalExceptionHandler 統一轉成 HTTP 404 的 ErrorResponse。
 */
public class NotFoundException extends RuntimeException {

    /**
     * @param resource 資源名稱,例如 "Task"、"Place"
     * @param id       查詢用的識別值
     */
    public NotFoundException(String resource, Object id) {
        super("%s not found: %s".formatted(resource, id));
    }
}
