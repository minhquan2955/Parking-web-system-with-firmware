package vn.parking.auth;

import java.io.Serializable;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import vn.parking.common.Problem;

public record Actor(UUID id, String username, String role, String passwordFingerprint)
    implements Serializable {
  public boolean admin() {
    return role.equals("ADMIN");
  }

  public static Actor current() {
    var a = SecurityContextHolder.getContext().getAuthentication();
    if (a == null || !(a.getPrincipal() instanceof Actor p))
      throw new Problem(401, "AUTH_REQUIRED", "Vui lòng đăng nhập");
    return p;
  }

  public void owner(UUID id) {
    if (!admin() && !this.id.equals(id)) throw Problem.missing();
  }
}
