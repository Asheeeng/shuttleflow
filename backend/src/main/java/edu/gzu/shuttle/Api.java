package edu.gzu.shuttle;

import jakarta.validation.ConstraintViolationException;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

public record Api<T>(String code, String message, T data) {
  public static <T> Api<T> ok(T data) {
    return new Api<>("OK", "操作成功", data);
  }

  public static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }
}

class BusinessException extends RuntimeException {
  final HttpStatus status;
  final String code;

  BusinessException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  static BusinessException bad(String code, String message) {
    return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
  }

  static BusinessException conflict(String code, String message) {
    return new BusinessException(HttpStatus.CONFLICT, code, message);
  }

  static BusinessException missing() {
    return new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "记录不存在或不属于当前用户");
  }
}

@RestControllerAdvice
class ErrorAdvice {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ErrorAdvice.class);

  @ExceptionHandler(BusinessException.class)
  ResponseEntity<Api<Void>> business(BusinessException e) {
    return ResponseEntity.status(e.status).body(new Api<>(e.code, e.getMessage(), null));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Api<Void>> validation(MethodArgumentNotValidException e) {
    String message =
        e.getBindingResult().getFieldErrors().stream()
            .map(x -> x.getField() + ": " + x.getDefaultMessage())
            .findFirst()
            .orElse("参数不合法");
    return ResponseEntity.badRequest().body(new Api<>("VALIDATION_ERROR", message, null));
  }

  @ExceptionHandler({
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    ConstraintViolationException.class
  })
  ResponseEntity<Api<Void>> malformed(Exception e) {
    return ResponseEntity.badRequest().body(new Api<>("VALIDATION_ERROR", "参数格式错误，请检查输入", null));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<Api<Void>> integrity(Exception e) {
    return ResponseEntity.status(409).body(new Api<>("DATA_CONFLICT", "数据已被其他操作更新，请刷新后重试", null));
  }

  @ExceptionHandler({
    org.springframework.web.servlet.resource.NoResourceFoundException.class,
    org.springframework.web.servlet.NoHandlerFoundException.class
  })
  ResponseEntity<Api<Void>> notFound(Exception e) {
    return ResponseEntity.status(404).body(new Api<>("NOT_FOUND", "接口不存在", null));
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ResponseEntity<Api<Void>> method(Exception e) {
    return ResponseEntity.status(405).body(new Api<>("METHOD_NOT_ALLOWED", "请求方法不支持", null));
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  ResponseEntity<Api<Void>> media(Exception e) {
    return ResponseEntity.status(415)
        .body(new Api<>("UNSUPPORTED_MEDIA_TYPE", "请使用 application/json", null));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Api<Void>> unexpected(Exception e) {
    log.error("Unexpected request failure", e);
    return ResponseEntity.internalServerError()
        .body(new Api<>("INTERNAL_ERROR", "服务暂时异常，请稍后重试", null));
  }
}
