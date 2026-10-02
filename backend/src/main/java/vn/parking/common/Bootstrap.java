package vn.parking.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.parking.model.*;

@Component
public class Bootstrap implements ApplicationRunner {
  private final Store db;
  private final PasswordEncoder passwords;
  private final Environment env;

  @Value("${parking.admin-username}")
  String username;

  @Value("${parking.admin-password}")
  String password;

  @Value("${parking.device-key}")
  String deviceKey;

  @Value("${parking.payment-mode}")
  String mode;

  public Bootstrap(Store db, PasswordEncoder passwords, Environment env) {
    this.db = db;
    this.passwords = passwords;
    this.env = env;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    Util.choice(mode, "mock", "payos");
    if (env.matchesProfiles("production") && mode.equals("mock"))
      throw new IllegalStateException("Production forbids mock payments");
    if (!username.isBlank() && db.count(User.class, "where e.role='ADMIN'") == 0) {
      Util.password(password);
      User u = new User();
      u.username = Util.username(username);
      u.passwordHash = passwords.encode(password);
      u.fullName = "Quản trị viên";
      u.role = "ADMIN";
      u.status = "ACTIVE";
      db.add(u);
    }
    if (!deviceKey.isBlank()) {
      if (deviceKey.length() < 24)
        throw new IllegalStateException("DEVICE_KEY must contain at least 24 characters");
      Device d = db.one(Device.class, "where e.code='ESP32-01'").orElseThrow();
      d.apiKeyHash = Util.sha(deviceKey);
    }
  }
}
