package jev.dashboard;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final String maxFileSize;

    ApiExceptionHandler(@Value("${spring.servlet.multipart.max-file-size:1MB}") String maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> handle(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.internalServerError().body(Map.of("error", "伺服器錯誤"));
    }

    /** Spring MVC 自己丟的例外：狀態碼照 Spring 的，訊息換成中文；headers（例如 405 的 Allow）一併帶回。 */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = switch (ex) {
            case MissingRequestHeaderException e -> "缺少 header：" + e.getHeaderName();
            case NoResourceFoundException e -> "找不到這個 API";
            case HttpRequestMethodNotSupportedException e -> "不支援這個 HTTP 方法";
            case MaxUploadSizeExceededException e -> "檔案太大，上限 " + maxFileSize;
            case HttpMessageNotReadableException e -> "請求內容不是正確的 JSON";
            case MethodArgumentTypeMismatchException e -> "參數格式錯誤：" + e.getName();
            case MissingServletRequestParameterException e -> "缺少參數：" + e.getParameterName();
            case MissingServletRequestPartException e -> "缺少參數：" + e.getRequestPartName();
            default -> "請求格式錯誤";
        };
        return ResponseEntity.status(status).headers(headers).body(Map.of("error", message));
    }
}
