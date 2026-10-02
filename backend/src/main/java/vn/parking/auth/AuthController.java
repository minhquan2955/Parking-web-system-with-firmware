package vn.parking.auth;

import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;
import vn.parking.common.*;
import vn.parking.model.User;
import vn.parking.users.UserService;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
  private final Store db;
  private final UserService users;
  private final PasswordEncoder passwords;
  private final RateLimiter rates;
  private final SecurityContextRepository contexts;
  private final CsrfTokenRepository tokens;
  private final String proxy;
  private final String dummy;

  public AuthController(
      Store db,
      UserService users,
      PasswordEncoder passwords,
      RateLimiter rates,
      SecurityContextRepository contexts,
      CsrfTokenRepository tokens,
      @Value("${parking.trusted-proxy}") String proxy) {
    this.db = db;
    this.users = users;
    this.passwords = passwords;
    this.rates = rates;
    this.contexts = contexts;
    this.tokens = tokens;
    this.proxy = proxy;
    this.dummy = passwords.encode(UUID.randomUUID().toString());
  }

  public record Register(String username, String password, String fullName, String phone) {}

  public record Login(String username, String password) {}

  public record Profile(String fullName, String phone) {}

  public record Password(String currentPassword, String newPassword) {}

  @GetMapping("/auth/csrf")
  Object csrf(CsrfToken token) {
    return Util.map("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @PostMapping("/auth/register")
  ResponseEntity<?> register(@RequestBody Register r, HttpServletRequest req) {
    if (SecurityContextHolder.getContext().getAuthentication() != null
        && SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof Actor)
      throw Problem.conflict("ALREADY_AUTHENTICATED");
    return ResponseEntity.status(201)
        .body(users.create(r.username, r.password, r.fullName, r.phone, null));
  }

  @PostMapping("/auth/login")
  ResponseEntity<?> login(@RequestBody Login r, HttpServletRequest req, HttpServletResponse res) {
    String username = Util.username(r.username);
    String key = "login:" + username + ":" + ip(req);
    long retry = rates.remaining(key, 10, 300);
    if (retry > 0) return limited(retry, "LOGIN_RATE_LIMITED", req);
    var u = db.one(User.class, "where e.username=?1", username).orElse(null);
    String candidate = r.password == null ? "" : r.password;
    boolean valid =
        candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72
            && passwords.matches(candidate, u == null ? dummy : u.passwordHash);
    if (!valid || u == null || !u.status.equals("ACTIVE")) {
      rates.take(key, 10, 300);
      throw new Problem(401, "INVALID_CREDENTIALS", "Tên đăng nhập hoặc mật khẩu không đúng");
    }
    rates.clear(key);
    req.getSession();
    req.changeSessionId();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            new Actor(u.id, u.username, u.role, Util.sha(u.passwordHash)),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_" + u.role))));
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, req, res);
    tokens.saveToken(null, req, res);
    return ResponseEntity.ok(UserService.view(u));
  }

  @PostMapping("/auth/logout")
  ResponseEntity<?> logout(HttpServletRequest req, HttpServletResponse res) {
    Actor.current();
    if (req.getSession(false) != null) req.getSession(false).invalidate();
    SecurityContextHolder.clearContext();
    tokens.saveToken(null, req, res);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/me")
  Object me() {
    return UserService.view(db.get(User.class, Actor.current().id()));
  }

  @PatchMapping("/me")
  Object profile(@RequestBody Profile r) {
    Actor a = Actor.current();
    return users.update(a.id(), r.fullName, r.phone, null, a);
  }

  @PutMapping("/me/password")
  ResponseEntity<?> password(@RequestBody Password r, HttpServletRequest req) {
    users.password(Actor.current(), r.currentPassword, r.newPassword);
    if (req.getSession(false) != null) req.getSession(false).invalidate();
    SecurityContextHolder.clearContext();
    return ResponseEntity.noContent().build();
  }

  private String ip(HttpServletRequest req) {
    String remote = req.getRemoteAddr();
    if (!proxy.isBlank() && proxy.equals(remote)) {
      String forwarded = req.getHeader("X-Forwarded-For");
      if (forwarded != null && forwarded.matches("[0-9a-fA-F:.]{3,64}")) return forwarded;
    }
    return remote;
  }

  private ResponseEntity<?> limited(long retry, String code, HttpServletRequest req) {
    return ResponseEntity.status(429)
        .header("Retry-After", Long.toString(retry))
        .body(Errors.body(code, "Vui lòng thử lại sau", req));
  }
}
