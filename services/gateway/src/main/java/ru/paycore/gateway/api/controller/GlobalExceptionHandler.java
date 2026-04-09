package ru.paycore.gateway.api.controller;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.paycore.gateway.domain.exception.*;

import java.net.URI;
import java.time.Instant;

/**
 * Centralized error handler.
 * Returns RFC 7807 Problem Details (application/problem+json).
 * NEVER leaks stack traces or internal details to the client.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String ERROR_TYPE_BASE = "https://paycore.ru/errors/";

    @ExceptionHandler(InsufficientFundsException.class)
    public ProblemDetail handle(InsufficientFundsException ex) {
        return problem(HttpStatus.PAYMENT_REQUIRED, ex.errorCode().name(), ex.getMessage());
    }

    @ExceptionHandler(CardBlockedException.class)
    public ProblemDetail handle(CardBlockedException ex) {
        return problem(HttpStatus.PAYMENT_REQUIRED, ex.errorCode().name(), ex.getMessage());
    }

    @ExceptionHandler(FraudSuspectedException.class)
    public ProblemDetail handle(FraudSuspectedException ex) {
        log.warn("action=fraud_decline message={}", ex.getMessage());
        return problem(HttpStatus.PAYMENT_REQUIRED, ex.errorCode().name(), "Transaction declined");
    }

    @ExceptionHandler(ProcessorUnavailableException.class)
    public ProblemDetail handle(ProcessorUnavailableException ex) {
        log.error("action=processor_unavailable message={}", ex.getMessage(), ex);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ex.errorCode().name(),
            "Payment processor temporarily unavailable. Please retry.");
    }

    @ExceptionHandler(DuplicateIdempotencyKeyException.class)
    public ProblemDetail handle(DuplicateIdempotencyKeyException ex) {
        return problem(HttpStatus.CONFLICT, ex.errorCode().name(), ex.getMessage());
    }

    @ExceptionHandler(LimitExceededException.class)
    public ProblemDetail handle(LimitExceededException ex) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, ex.errorCode().name(), ex.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail handle(InvalidRequestException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.errorCode().name(), ex.getMessage());
    }

    @ExceptionHandler(MerchantNotFoundException.class)
    public ProblemDetail handle(MerchantNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.errorCode().name(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handle(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("Validation failed");
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handle(ConstraintViolationException ex) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("action=unexpected_error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "An unexpected error occurred. Reference: " + Instant.now().toEpochMilli());
    }

    private ProblemDetail problem(HttpStatus status, String errorCode, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(ERROR_TYPE_BASE + errorCode.toLowerCase().replace('_', '-')));
        pd.setProperty("errorCode", errorCode);
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }
}
