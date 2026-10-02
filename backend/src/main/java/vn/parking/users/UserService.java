package vn.parking.users;

import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.parking.audit.AuditService;
import vn.parking.auth.Actor;
import vn.parking.common.*;
import vn.parking.model.*;

@Service
public class UserService {
  private final Store db;
  private final PasswordEncoder passwords;
  private final AuditService audit;

  public UserService(Store db, PasswordEncoder passwords, AuditService audit) {
    this.db = db;
    this.passwords = passwords;
    this.audit = audit;
  }

  public static Map<String, Object> view(User u) {
    return Util.map(
        "id",
        u.id,
        "username",
        u.username,
        "fullName",
        u.fullName,
        "phone",
        u.phone,
        "role",
        u.role,
        "status",
        u.status,
        "createdAt",
        u.createdAt);
  }

  @Transactional
  public Map<String, Object> create(
      String username, String password, String name, String phone, UUID actor) {
    username = Util.username(username);
    Util.password(password);
    profile(name, phone);
    db.advisory("username:" + username);
    if (db.one(User.class, "where e.username=?1", username).isPresent())
      throw Problem.conflict("USERNAME_TAKEN");
    User u = new User();
    u.username = username;
    u.passwordHash = passwords.encode(password);
    u.fullName = name.strip();
    u.phone = phone;
    u.role = "USER";
    u.status = "ACTIVE";
    db.add(u);
    audit.record(
        actor,
        actor == null ? "USER_REGISTERED" : "USER_CREATED",
        "USER",
        u.id,
        null,
        view(u),
        null);
    return view(u);
  }

  @Transactional
  public Map<String, Object> update(
      UUID id, String name, String phone, String status, Actor actor) {
    User u = db.lock(User.class, id);
    if (!id.equals(actor.id()) && (!actor.admin() || !u.role.equals("USER")))
      throw Problem.missing();
    profile(name, phone);
    var before = view(u);
    u.fullName = name.strip();
    u.phone = phone;
    if (status != null) {
      if (!actor.admin() || !u.role.equals("USER"))
        throw new Problem(403, "FORBIDDEN", "Không được đổi trạng thái ADMIN");
      Util.choice(status, "ACTIVE", "DISABLED");
      u.status = status;
    }
    audit.record(actor.id(), "USER_UPDATED", "USER", id, before, view(u), null);
    return view(u);
  }

  @Transactional
  public void password(Actor actor, String old, String next) {
    Util.password(next);
    User u = db.lock(User.class, actor.id());
    if (!passwords.matches(old, u.passwordHash))
      throw new Problem(400, "CURRENT_PASSWORD_INVALID", "Mật khẩu hiện tại không đúng");
    u.passwordHash = passwords.encode(next);
    audit.record(actor.id(), "PASSWORD_CHANGED", "USER", u.id, null, null, null);
  }

  private static void profile(String name, String phone) {
    if (name == null
        || name.isBlank()
        || name.length() > 100
        || phone != null && phone.length() > 20)
      throw Problem.bad("Họ tên hoặc số điện thoại không hợp lệ");
  }
}
