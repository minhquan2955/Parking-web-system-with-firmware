package vn.parking.common;

public class Problem extends RuntimeException {
  public final int status;
  public final String code;
  public java.util.UUID orderId;

  public Problem(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static Problem bad(String message) {
    return new Problem(400, "VALIDATION_ERROR", message);
  }

  public static Problem conflict(String code) {
    return new Problem(409, code, "Thao tác xung đột: " + code);
  }

  public static Problem missing() {
    return new Problem(404, "NOT_FOUND", "Không tìm thấy dữ liệu");
  }

  public Problem withOrder(java.util.UUID id) {
    orderId = id;
    return this;
  }
}
