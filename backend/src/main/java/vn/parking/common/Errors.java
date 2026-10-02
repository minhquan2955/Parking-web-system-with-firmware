package vn.parking.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(Problem.class)
  ResponseEntity<?> problem(Problem p, HttpServletRequest req) {
    var result = body(p.code, p.getMessage(), req);
    if (p.orderId != null) result.put("orderId", p.orderId);
    return ResponseEntity.status(p.status).body(result);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentNotValidException.class,
    MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class,
    org.springframework.web.bind.MissingRequestHeaderException.class,
    java.time.format.DateTimeParseException.class
  })
  ResponseEntity<?> validation(Exception ex, HttpServletRequest req) {
    return ResponseEntity.badRequest()
        .body(body("VALIDATION_ERROR", "Dữ liệu không hợp lệ hoặc có trường không được phép", req));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<?> db(DataAccessException ex, HttpServletRequest req) {
    return ResponseEntity.status(503)
        .body(
            body(
                "DATABASE_UNAVAILABLE",
                "Thao tác chưa hoàn tất. Hãy thử lại với cùng mã yêu cầu.",
                req));
  }

  public static Map<String, Object> body(String code, String message, HttpServletRequest req) {
    return Util.map(
        "code",
        code,
        "message",
        message,
        "fieldErrors",
        List.of(),
        "traceId",
        Objects.toString(req.getAttribute("traceId"), UUID.randomUUID().toString()));
  }
}
