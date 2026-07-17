package com.aproject.aidriven.mymobilesecretary.shared.error;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全域例外處理:把四類錯誤(validation、not found、business、unexpected)
 * 統一轉成 ErrorResponse,讓所有 API 的錯誤格式一致。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    /**
     * request DTO validation 失敗 → 400,附上每個欄位的失敗原因。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<ErrorResponse.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ErrorResponse.FieldViolation(
                        fieldError.getField(),
                        // defaultMessage 為 null 時給通用字樣,避免回傳 null 給手機端
                        fieldError.getDefaultMessage() == null ? "invalid value" : fieldError.getDefaultMessage()))
                .toList();
        ErrorResponse body = new ErrorResponse(
                "VALIDATION_ERROR", "Request validation failed", Instant.now(clock), violations);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * request body 無法解析(壞 JSON、不存在的 enum 值)→ 400。
     * 沒有這個 handler 時會落到 500,誤把客戶端錯誤當伺服器錯誤。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        ErrorResponse body = ErrorResponse.of(
                "MALFORMED_REQUEST", "Request body is malformed or contains invalid values", Instant.now(clock));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 路徑/查詢參數型別錯誤(例如 /api/tasks/abc)→ 400。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ErrorResponse body = ErrorResponse.of(
                "INVALID_PARAMETER", "Parameter '%s' has invalid value".formatted(ex.getName()),
                Instant.now(clock));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 查無資源 → 404。
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex) {
        ErrorResponse body = ErrorResponse.of("NOT_FOUND", ex.getMessage(), Instant.now(clock));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * 業務規則不允許 → 422,錯誤碼由例外本身決定(例如 INVALID_STATE_TRANSITION)。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex) {
        ErrorResponse body = ErrorResponse.of(ex.getCode(), ex.getMessage(), Instant.now(clock));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    /**
     * 其餘未預期錯誤 → 500。
     *
     * 關鍵規則:細節只進 server log,不回給客戶端,避免洩漏內部實作。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        ErrorResponse body = ErrorResponse.of(
                "INTERNAL_ERROR", "An unexpected error occurred", Instant.now(clock));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
