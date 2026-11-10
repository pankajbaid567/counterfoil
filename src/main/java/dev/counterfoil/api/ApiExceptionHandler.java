package dev.counterfoil.api;

import dev.counterfoil.ledger.LedgerException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** One error shape for everything: {"error": {"code": "...", "message": "..."}}. Internal details are logged, never returned. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", Map.of("code", code, "message", message)));
    }

    @ExceptionHandler(LedgerException.class)
    ResponseEntity<Map<String, Object>> domain(LedgerException e) {
        return error(e.httpStatus(), e.code(), e.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Map<String, Object>> header(MissingRequestHeaderException e) {
        return error(400, "BAD_REQUEST", "missing header " + e.getHeaderName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> type(MethodArgumentTypeMismatchException e) {
        return error(400, "BAD_REQUEST", "invalid value for '" + e.getName() + "'");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException e) {
        return error(405, "METHOD_NOT_ALLOWED", "method not allowed");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, Object>> media(HttpMediaTypeNotSupportedException e) {
        return error(415, "UNSUPPORTED_MEDIA_TYPE", "use application/json");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> none(NoResourceFoundException e) {
        return error(404, "NOT_FOUND", "no such endpoint");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> other(Exception e) {
        log.error("unhandled error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "INTERNAL", "internal error");
    }
}
