package app.beat.auth;

import app.beat.infra.RequestContext;
import app.beat.workspace.WorkspaceMemberRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {

  private final SessionRepository sessions;
  private final WorkspaceMemberRepository members;

  public AuthFilter(SessionRepository sessions, WorkspaceMemberRepository members) {
    this.sessions = sessions;
    this.members = members;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String token = extractToken(req);
    if (token != null) {
      String hash = SessionTokens.hash(token);
      sessions
          .findActive(hash)
          .ifPresent(
              s -> {
                members
                    .findCurrentForUser(s.userId())
                    .ifPresent(
                        m ->
                            req.setAttribute(
                                RequestContext.ATTRIBUTE,
                                new RequestContext(s.userId(), m.workspaceId(), m.role())));
                sessions.touch(hash);
              });
    }
    chain.doFilter(req, res);
  }

  private static String extractToken(HttpServletRequest req) {
    String h = req.getHeader("Authorization");
    if (h != null && h.startsWith("Bearer ")) {
      String t = h.substring("Bearer ".length()).trim();
      if (!t.isEmpty()) return t;
    }
    if (req.getCookies() != null) {
      for (Cookie c : req.getCookies()) {
        if ("session".equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
          return c.getValue();
        }
      }
    }
    return null;
  }
}
