package app.beat.outlet;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

public final class Domains {

  private Domains() {}

  /** Returns the lowercased apex domain (e.g. "techcrunch.com") for a URL, or empty if invalid. */
  public static Optional<String> apexFromUrl(String url) {
    if (url == null) return Optional.empty();
    try {
      String host = URI.create(url.trim()).getHost();
      if (host == null || host.isBlank()) return Optional.empty();
      host = host.toLowerCase(Locale.ROOT);
      if (host.startsWith("www.")) host = host.substring(4);
      return Optional.of(host);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** Best-effort outlet name from a domain: drop TLD, title-case the rest. */
  public static String outletNameFromDomain(String domain) {
    int firstDot = domain.indexOf('.');
    String stem = firstDot > 0 ? domain.substring(0, firstDot) : domain;
    if (stem.isEmpty()) return domain;
    return Character.toUpperCase(stem.charAt(0)) + stem.substring(1);
  }
}
