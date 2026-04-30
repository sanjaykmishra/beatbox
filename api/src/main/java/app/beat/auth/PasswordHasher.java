package app.beat.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {

  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

  public String hash(String plaintext) {
    return encoder.encode(plaintext);
  }

  public boolean matches(String plaintext, String hash) {
    return encoder.matches(plaintext, hash);
  }
}
