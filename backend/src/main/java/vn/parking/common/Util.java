package vn.parking.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class Util {
  private Util() {}

  public static Map<String, Object> map(Object... pairs) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
    return m;
  }

  public static String sha(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String json(Object value) {
    try {
      return new ObjectMapper().findAndRegisterModules().writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String uid(String s) {
    if (s == null) throw Problem.bad("Thiếu UID");
    s = s.replaceAll("[\\s:-]", "").toUpperCase(Locale.ROOT);
    if (!s.matches("(?:[0-9A-F]{8}|[0-9A-F]{14}|[0-9A-F]{20})"))
      throw Problem.bad("UID phải có 4, 7 hoặc 10 byte hex");
    return s;
  }

  public static String username(String s) {
    if (s == null) throw Problem.bad("Thiếu username");
    s = s.strip().toLowerCase(Locale.ROOT);
    if (!s.matches("[a-z0-9._-]{3,50}")) throw Problem.bad("Username không hợp lệ");
    return s;
  }

  public static void password(String s) {
    int n = s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    if (n < 10 || n > 72) throw Problem.bad("Mật khẩu phải dài 10–72 byte UTF-8");
  }

  public static void choice(String s, String... allowed) {
    if (!Arrays.asList(allowed).contains(s)) throw Problem.bad("Giá trị không hợp lệ");
  }

  public static void reason(String s) {
    if (s == null || s.strip().length() < 10 || s.length() > 500)
      throw Problem.bad("Lý do phải có 10–500 ký tự");
  }
}
