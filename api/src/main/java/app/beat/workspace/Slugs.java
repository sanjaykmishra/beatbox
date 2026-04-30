package app.beat.workspace;

import java.security.SecureRandom;
import java.util.Locale;

public final class Slugs {

  private static final SecureRandom RNG = new SecureRandom();

  private Slugs() {}

  public static String fromName(String name) {
    String s =
        name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
    if (s.isEmpty()) {
      s = "workspace";
    }
    if (s.length() > 48) {
      s = s.substring(0, 48);
    }
    return s;
  }

  public static String suffixed(String base) {
    int n = 1000 + RNG.nextInt(9000);
    return base + "-" + n;
  }
}
