package app.beat.auth;

import app.beat.infra.AppException;
import app.beat.infra.RequestContext;
import app.beat.workspace.Workspace;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {

  private final AuthService auth;
  private final UserRepository users;

  public AuthController(AuthService auth, UserRepository users) {
    this.auth = auth;
    this.users = users;
  }

  public record SignupRequest(
      @Email @NotBlank String email,
      @NotBlank @Size(min = 8) String password,
      @NotBlank String name,
      @NotBlank String workspace_name) {}

  public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}

  public record UserDto(UUID id, String email, String name) {
    public static UserDto from(User u) {
      return new UserDto(u.id(), u.email(), u.name());
    }
  }

  public record WorkspaceDto(
      UUID id, String name, String slug, String plan, Instant trial_ends_at) {
    public static WorkspaceDto from(Workspace w) {
      return new WorkspaceDto(w.id(), w.name(), w.slug(), w.plan(), w.trialEndsAt());
    }
  }

  public record AuthResponse(UserDto user, WorkspaceDto workspace, String session_token) {}

  @PostMapping("/signup")
  public ResponseEntity<AuthResponse> signup(
      @Valid @RequestBody SignupRequest body, HttpServletRequest req) {
    var r = auth.signup(body.email(), body.password(), body.name(), body.workspace_name(), req);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new AuthResponse(
                UserDto.from(r.user()), WorkspaceDto.from(r.workspace()), r.sessionToken()));
  }

  @PostMapping("/login")
  public AuthResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest req) {
    var r = auth.login(body.email(), body.password(), req);
    return new AuthResponse(
        UserDto.from(r.user()), WorkspaceDto.from(r.workspace()), r.sessionToken());
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @RequestHeader(value = "Authorization", required = false) String authHeader) {
    String token = null;
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
      token = authHeader.substring("Bearer ".length()).trim();
    }
    auth.logout(token);
    return ResponseEntity.noContent().build();
  }

  /** Returns the currently-authenticated user. SPA reads this on session load. */
  @GetMapping("/me")
  public UserDto me(HttpServletRequest req) {
    RequestContext ctx = RequestContext.require(req);
    return users
        .findById(ctx.userId())
        .map(UserDto::from)
        .orElseThrow(() -> AppException.notFound("User"));
  }
}
