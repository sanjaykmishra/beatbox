package app.beat.infra;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One log line per HTTP request: {@code METHOD path -> status (Nms)}. Skips actuator and static
 * paths to keep noise down. Logs at INFO for 2xx/3xx, WARN for 4xx, ERROR for 5xx so log filtering
 * by level is useful.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RequestLogFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger("app.beat.access");

  // Healthcheck endpoints are pinged constantly by Docker / Fly / load balancers — logging every
  // hit drowns out actual request lines. Skip both shapes (Spring Actuator + our /v1/healthz).
  private static final Set<String> SKIP_PREFIXES = Set.of("/actuator/", "/health", "/v1/healthz");

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    long start = System.nanoTime();
    try {
      chain.doFilter(req, res);
    } finally {
      String path = req.getRequestURI();
      if (skip(path)) return;
      long ms = (System.nanoTime() - start) / 1_000_000;
      int status = res.getStatus();
      Object reqId = req.getAttribute(RequestIdFilter.ATTRIBUTE);
      String line = "{} {} -> {} ({}ms){}";
      Object[] args =
          new Object[] {req.getMethod(), path, status, ms, reqId == null ? "" : " req=" + reqId};
      if (status >= 500) log.error(line, args);
      else if (status >= 400) log.warn(line, args);
      else log.info(line, args);
    }
  }

  private static boolean skip(String path) {
    if (path == null) return false;
    for (String p : SKIP_PREFIXES) if (path.startsWith(p)) return true;
    return false;
  }
}
