package app.beat.infra;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ProblemDetailHandler {

  @ExceptionHandler(AppException.class)
  public ResponseEntity<ProblemDetail> handleAppException(AppException ex, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
    pd.setType(java.net.URI.create(ex.type()));
    pd.setTitle(ex.title());
    pd.setInstance(java.net.URI.create(req.getRequestURI()));
    pd.setProperty("request_id", req.getAttribute(RequestIdFilter.ATTRIBUTE));
    return ResponseEntity.status(ex.status()).body(pd);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest req) {
    String msg =
        ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .findFirst()
            .orElse("Validation failed");
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, msg);
    pd.setType(java.net.URI.create("/errors/validation"));
    pd.setTitle("Validation failed");
    pd.setInstance(java.net.URI.create(req.getRequestURI()));
    pd.setProperty("request_id", req.getAttribute(RequestIdFilter.ATTRIBUTE));
    return ResponseEntity.badRequest().body(pd);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ProblemDetail> handleResponseStatus(
      ResponseStatusException ex, HttpServletRequest req) {
    HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
    String detail = ex.getReason() == null ? status.getReasonPhrase() : ex.getReason();
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
    pd.setType(java.net.URI.create("/errors/" + status.value()));
    pd.setTitle(status.getReasonPhrase());
    pd.setInstance(java.net.URI.create(req.getRequestURI()));
    pd.setProperty("request_id", req.getAttribute(RequestIdFilter.ATTRIBUTE));
    return ResponseEntity.status(status).body(pd);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleGeneric(Exception ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    pd.setType(java.net.URI.create("/errors/internal"));
    pd.setTitle("Internal server error");
    pd.setInstance(java.net.URI.create(req.getRequestURI()));
    pd.setProperty("request_id", req.getAttribute(RequestIdFilter.ATTRIBUTE));
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(pd);
  }
}
