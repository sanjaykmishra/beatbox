package app.beat.infra;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-IP token bucket for the unauthenticated public endpoints. 60 requests / minute per docs/04
 * §Rate limits. Implemented with a window counter — cheap and good enough at our scale; swap for
 * Redis if we need cross-instance fairness.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RateLimitFilter extends OncePerRequestFilter {

  private static final int LIMIT_PER_MINUTE = 60;
  private static final long WINDOW_MS = Duration.ofMinutes(1).toMillis();

  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    String path = req.getRequestURI();
    return path == null || !path.startsWith("/v1/public/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String ip = clientIp(req);
    long now = System.currentTimeMillis();
    Bucket b = buckets.computeIfAbsent(ip, k -> new Bucket(new AtomicLong(now), new AtomicLong(0)));
    long windowStart = b.windowStartMs.get();
    if (now - windowStart >= WINDOW_MS) {
      b.windowStartMs.set(now);
      b.count.set(0);
    }
    long count = b.count.incrementAndGet();
    if (count > LIMIT_PER_MINUTE) {
      long retryAfterMs = WINDOW_MS - (now - b.windowStartMs.get());
      res.setStatus(429);
      res.setHeader("Retry-After", Long.toString(Math.max(1, retryAfterMs / 1000)));
      res.setContentType("text/plain");
      res.getWriter().write("Too many requests");
      return;
    }
    chain.doFilter(req, res);
  }

  private static String clientIp(HttpServletRequest req) {
    String f = req.getHeader("X-Forwarded-For");
    if (f != null && !f.isBlank()) return f.split(",")[0].trim();
    return req.getRemoteAddr();
  }

  private record Bucket(AtomicLong windowStartMs, AtomicLong count) {}
}
