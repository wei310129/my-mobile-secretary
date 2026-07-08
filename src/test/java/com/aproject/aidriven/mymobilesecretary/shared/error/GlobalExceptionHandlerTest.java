package com.aproject.aidriven.mymobilesecretary.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * 驗證四類錯誤都被轉成統一的 ErrorResponse 格式,且時間來自注入的 Clock。
 */
class GlobalExceptionHandlerTest {

    /** 固定時間,驗證 handler 用的是注入的 Clock 而不是系統時間。 */
    private static final Instant FIXED_NOW = Instant.parse("2026-07-08T10:00:00Z");

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

    @Test
    void validationErrorReturns400WithFieldViolations() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "title", "must not be blank"));
        // defaultMessage 為 null 的欄位錯誤,驗證 fallback 字樣
        bindingResult.addError(new FieldError(
                "request", "radius", null, false, null, null, null));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
                new MethodParameter(getClass().getDeclaredMethod("dummyMethod", String.class), 0),
                bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.timestamp()).isEqualTo(FIXED_NOW);
        assertThat(body.fieldErrors()).containsExactly(
                new ErrorResponse.FieldViolation("title", "must not be blank"),
                new ErrorResponse.FieldViolation("radius", "invalid value"));
    }

    @Test
    void notFoundReturns404() {
        ResponseEntity<ErrorResponse> response = handler.handleNotFound(new NotFoundException("Task", 42L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("NOT_FOUND");
        assertThat(body.message()).isEqualTo("Task not found: 42");
        assertThat(body.timestamp()).isEqualTo(FIXED_NOW);
        assertThat(body.fieldErrors()).isEmpty();
    }

    @Test
    void businessErrorReturns422WithExceptionCode() {
        ResponseEntity<ErrorResponse> response = handler.handleBusiness(
                new BusinessException("INVALID_STATE_TRANSITION", "Task already confirmed"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(body.message()).isEqualTo("Task already confirmed");
    }

    @Test
    void unexpectedErrorReturns500WithoutLeakingDetails() {
        ResponseEntity<ErrorResponse> response = handler.handleUnexpected(
                new IllegalStateException("secret internal detail"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("INTERNAL_ERROR");
        // 內部細節只能進 log,不能出現在回應內容
        assertThat(body.message()).doesNotContain("secret internal detail");
    }

    /** 只用來建 MethodParameter,模擬 controller 方法簽章。 */
    @SuppressWarnings("unused")
    private void dummyMethod(String arg) {
    }
}
