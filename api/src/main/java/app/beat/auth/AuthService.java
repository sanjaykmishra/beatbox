package app.beat.auth;

import app.beat.activity.ActivityRecorder;
import app.beat.activity.EventKinds;
import app.beat.audit.AuditService;
import app.beat.infra.AppException;
import app.beat.workspace.Slugs;
import app.beat.workspace.Workspace;
import app.beat.workspace.WorkspaceMemberRepository;
import app.beat.workspace.WorkspaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

  private final UserRepository users;
  private final SessionRepository sessions;
  private final WorkspaceRepository workspaces;
  private final WorkspaceMemberRepository members;
  private final PasswordHasher hasher;
  private final AuditService audit;
  private final ActivityRecorder activity;

  public AuthService(
      UserRepository users,
      SessionRepository sessions,
      WorkspaceRepository workspaces,
      WorkspaceMemberRepository members,
      PasswordHasher hasher,
      AuditService audit,
      ActivityRecorder activity) {
    this.users = users;
    this.sessions = sessions;
    this.workspaces = workspaces;
    this.members = members;
    this.hasher = hasher;
    this.audit = audit;
    this.activity = activity;
  }

  public record SignupResult(User user, Workspace workspace, String sessionToken) {}

  public record LoginResult(User user, Workspace workspace, String sessionToken) {}

  @Transactional
  public SignupResult signup(
      String email, String password, String name, String workspaceName, HttpServletRequest req) {
    if (password.length() < 8) {
      throw AppException.badRequest(
          "/errors/weak-password", "Password too short", "Password must be at least 8 characters.");
    }
    String hash = hasher.hash(password);
    User user = users.insert(email.trim(), hash, name.trim());

    String slug = Slugs.fromName(workspaceName);
    if (workspaces.slugExists(slug)) {
      slug = Slugs.suffixed(slug);
    }
    Workspace ws = workspaces.insert(workspaceName.trim(), slug);
    members.insert(ws.id(), user.id(), "owner");

    String token = SessionTokens.generate();
    sessions.insert(SessionTokens.hash(token), user.id(), userAgent(req), ip(req));
    users.touchLastLogin(user.id());

    audit.record(ws.id(), user.id(), "user.signup", "user", user.id(), Map.of(), req);
    audit.record(
        ws.id(), user.id(), "workspace.created", "workspace", ws.id(), Map.of("slug", slug), req);
    activity.recordUser(ws.id(), user.id(), EventKinds.USER_SIGNED_UP, "user", user.id(), Map.of());
    activity.recordUser(
        ws.id(),
        user.id(),
        EventKinds.WORKSPACE_CREATED,
        "workspace",
        ws.id(),
        Map.of("slug", slug));

    return new SignupResult(user, ws, token);
  }

  public LoginResult login(String email, String password, HttpServletRequest req) {
    User user =
        users
            .findByEmail(email.trim())
            .orElseThrow(() -> AppException.unauthorized("Invalid email or password."));
    if (!hasher.matches(password, user.passwordHash())) {
      throw AppException.unauthorized("Invalid email or password.");
    }
    Workspace ws =
        members
            .findCurrentForUser(user.id())
            .flatMap(m -> workspaces.findById(m.workspaceId()))
            .orElseThrow(() -> AppException.forbidden("No active workspace."));

    String token = SessionTokens.generate();
    sessions.insert(SessionTokens.hash(token), user.id(), userAgent(req), ip(req));
    users.touchLastLogin(user.id());
    audit.record(ws.id(), user.id(), "user.login", "user", user.id(), Map.of(), req);
    activity.recordUser(ws.id(), user.id(), EventKinds.USER_LOGGED_IN, "user", user.id(), Map.of());
    return new LoginResult(user, ws, token);
  }

  public void logout(String token) {
    if (token == null || token.isBlank()) return;
    sessions.delete(SessionTokens.hash(token));
  }

  private static String userAgent(HttpServletRequest req) {
    return req == null ? null : req.getHeader("User-Agent");
  }

  private static String ip(HttpServletRequest req) {
    if (req == null) return null;
    String f = req.getHeader("X-Forwarded-For");
    if (f != null && !f.isBlank()) return f.split(",")[0].trim();
    return req.getRemoteAddr();
  }
}
