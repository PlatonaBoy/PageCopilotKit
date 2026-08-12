package com.enterprise.copilot.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
    return error(ex.getStatus(), ex.getCode(), ex.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(err -> err.getField() + " " + err.getDefaultMessage())
            .orElse("Validation failed");
    return error(HttpStatus.BAD_REQUEST, "bad_request", message);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
    return error(HttpStatus.BAD_REQUEST, "bad_request", "Malformed request body");
  }

  /**
   * Routing-level failures must keep their real status. Without these the catch-all below would
   * turn "endpoint not registered" (e.g. a dev-only route in production) into a misleading 500.
   */
  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public ResponseEntity<Map<String, Object>> handleNotFound(Exception ex) {
    return error(HttpStatus.NOT_FOUND, "not_found", "Endpoint not found");
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<Map<String, Object>> handleMethodNotAllowed(
      HttpRequestMethodNotSupportedException ex) {
    return error(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "Method not allowed");
  }

  /** Catch-all so clients always receive the documented error envelope, never a raw stack trace. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleUnexpected(
      Exception ex, HttpServletRequest request) {
    log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected server error");
  }

  /**
   * Pins the response to JSON.
   *
   * <p>The chat endpoints declare {@code produces=text/event-stream}, and clients send a matching
   * {@code Accept}. Without an explicit content type Spring would find no acceptable representation
   * for this JSON body and turn every contract error into an opaque 500.
   */
  private static ResponseEntity<Map<String, Object>> error(
      HttpStatus status, String code, String message) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("code", code);
    out.put("message", message);
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(out);
  }
}
