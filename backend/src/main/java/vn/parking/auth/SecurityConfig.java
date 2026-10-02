package vn.parking.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import vn.parking.common.Errors;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder encoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  SecurityContextRepository contexts() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  CsrfTokenRepository tokens() {
    return new HttpSessionCsrfTokenRepository();
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      RequestGuard guard,
      ObjectMapper json,
      SecurityContextRepository contexts,
      CsrfTokenRepository tokens)
      throws Exception {
    return http.securityContext(c -> c.securityContextRepository(contexts))
        .csrf(
            c ->
                c.csrfTokenRepository(tokens)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .ignoringRequestMatchers(
                        "/api/v1/device/**", "/api/v1/payments/webhooks/payos"))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/v1/auth/**",
                        "/api/v1/device/**",
                        "/api/v1/payments/webhooks/payos",
                        "/actuator/health/**",
                        "/error")
                    .permitAll()
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) -> {
                          res.setStatus(401);
                          res.setContentType("application/json");
                          json.writeValue(
                              res.getOutputStream(),
                              Errors.body("AUTH_REQUIRED", "Vui lòng đăng nhập", req));
                        })
                    .accessDeniedHandler(
                        (req, res, ex) -> {
                          res.setStatus(403);
                          res.setContentType("application/json");
                          json.writeValue(
                              res.getOutputStream(),
                              Errors.body(
                                  ex instanceof CsrfException ? "CSRF_INVALID" : "FORBIDDEN",
                                  "Không có quyền hoặc token CSRF không hợp lệ",
                                  req));
                        }))
        .addFilterAfter(guard, SecurityContextHolderFilter.class)
        .logout(l -> l.disable())
        .formLogin(f -> f.disable())
        .httpBasic(b -> b.disable())
        .build();
  }

  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<RequestGuard> guardRegistration(
      RequestGuard g) {
    var b = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(g);
    b.setEnabled(false);
    return b;
  }
}
