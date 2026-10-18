package com.brac.query.engine.web;

import com.brac.query.engine.dto.ErrorResponse;
import com.brac.query.engine.error.EngineException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.core.JacksonException;

/**
 * Every error body is JSON with an explicit content type. Without it, a request that asked for
 * {@code application/x-ndjson} would make Spring look for an NDJSON writer for the error object
 * and answer {@code 406} instead of the real error.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Seconds suggested to clients before retrying when no database connection was available. */
    private static final String RETRY_AFTER_SECONDS = "2";

    @ExceptionHandler(EngineException.class)
    public ResponseEntity<ErrorResponse> handleEngineException(EngineException ex) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.getStatus()).contentType(MediaType.APPLICATION_JSON);
        if ("SERVICE_BUSY".equals(ex.getCode())) {
            response.header("Retry-After", RETRY_AFTER_SECONDS);
        }
        return response.body(new ErrorResponse(ex.getCode(), ex.getMessage(), ex.getQueryName()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .orElse("Invalid request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse("INVALID_REQUEST", message, null));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .findFirst()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .orElse("Invalid request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse("INVALID_REQUEST", message, null));
    }

    @ExceptionHandler(JacksonException.class)
    public ResponseEntity<ErrorResponse> handleParseError(JacksonException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse("MALFORMED_BODY", "Could not parse request body: " + ex.getOriginalMessage(), null));
    }
}
