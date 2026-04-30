package app.beat.infra;

import org.springframework.http.HttpStatus;

public class AppException extends RuntimeException {

  private final HttpStatus status;
  private final String type;
  private final String title;

  public AppException(HttpStatus status, String type, String title, String detail) {
    super(detail);
    this.status = status;
    this.type = type;
    this.title = title;
  }

  public HttpStatus status() {
    return status;
  }

  public String type() {
    return type;
  }

  public String title() {
    return title;
  }

  public static AppException unauthorized(String detail) {
    return new AppException(
        HttpStatus.UNAUTHORIZED, "/errors/unauthorized", "Unauthorized", detail);
  }

  public static AppException forbidden(String detail) {
    return new AppException(HttpStatus.FORBIDDEN, "/errors/forbidden", "Forbidden", detail);
  }

  public static AppException notFound(String detail) {
    return new AppException(HttpStatus.NOT_FOUND, "/errors/not-found", "Not found", detail);
  }

  public static AppException conflict(String type, String title, String detail) {
    return new AppException(HttpStatus.CONFLICT, type, title, detail);
  }

  public static AppException badRequest(String type, String title, String detail) {
    return new AppException(HttpStatus.BAD_REQUEST, type, title, detail);
  }

  public static AppException unprocessable(String type, String title, String detail) {
    return new AppException(HttpStatus.UNPROCESSABLE_ENTITY, type, title, detail);
  }
}
