package vn.parking.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.parking.common.*;
import vn.parking.model.User;

@Component
public class RequestGuard extends OncePerRequestFilter {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RequestGuard.class);
  private final Store db;
  private final ObjectMapper json;
  private final RateLimiter rates;
  private final String proxy;

  public RequestGuard(
      Store db,
      ObjectMapper json,
      RateLimiter rates,
      @Value("${parking.trusted-proxy}") String proxy) {
    this.db = db;
    this.json = json;
    this.rates = rates;
    this.proxy = proxy;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    long started = System.nanoTime();
    String trace = UUID.randomUUID().toString();
    req.setAttribute("traceId", trace);
    res.setHeader("X-Trace-Id", trace);
    try {
      var auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth != null && auth.getPrincipal() instanceof Actor a) {
        User u = db.get(User.class, a.id());
        if (!u.status.equals("ACTIVE")
            || !Util.sha(u.passwordHash).equals(a.passwordFingerprint())) {
          if (req.getSession(false) != null) req.getSession(false).invalidate();
          SecurityContextHolder.clearContext();
          throw new Problem(401, "SESSION_INVALID", "Phiên đăng nhập không còn hợp lệ");
        }
      }
      if (req.getRequestURI().equals("/api/v1/auth/register")
          && req.getMethod().equals("POST")
          && !(auth != null && auth.getPrincipal() instanceof Actor)) {
        String ip = req.getRemoteAddr(), forwarded = req.getHeader("X-Forwarded-For");
        if (!proxy.isBlank()
            && proxy.equals(ip)
            && forwarded != null
            && forwarded.matches("[0-9a-fA-F:.]{3,64}")) ip = forwarded;
        long retry = rates.take("register:" + ip, 5, 900);
        if (retry > 0) {
          res.setHeader("Retry-After", Long.toString(retry));
          throw new Problem(429, "REGISTRATION_RATE_LIMITED", "Vui lòng thử lại sau");
        }
      }
      if (req.getRequestURI().startsWith("/api/") && !req.getMethod().equals("GET")) {
        int limit = req.getRequestURI().contains("/webhooks/") ? 262144 : 65536;
        if (req.getContentLengthLong() > limit)
          throw new Problem(413, "BODY_TOO_LARGE", "Nội dung quá lớn");
        byte[] bytes = req.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) throw new Problem(413, "BODY_TOO_LARGE", "Nội dung quá lớn");
        req =
            new HttpServletRequestWrapper(req) {
              @Override
              public ServletInputStream getInputStream() {
                var in = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                  public int read() {
                    return in.read();
                  }

                  public boolean isFinished() {
                    return in.available() == 0;
                  }

                  public boolean isReady() {
                    return true;
                  }

                  public void setReadListener(ReadListener l) {}
                };
              }

              @Override
              public BufferedReader getReader() {
                return new BufferedReader(
                    new InputStreamReader(
                        getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
              }
            };
      }
      chain.doFilter(req, res);
    } catch (Problem p) {
      res.setStatus(p.status);
      res.setContentType("application/json");
      json.writeValue(res.getOutputStream(), Errors.body(p.code, p.getMessage(), req));
    } finally {
      log.info(
          "request traceId={} method={} path={} status={} durationMs={}",
          trace,
          req.getMethod(),
          req.getRequestURI(),
          res.getStatus(),
          (System.nanoTime() - started) / 1000000);
    }
  }
}
