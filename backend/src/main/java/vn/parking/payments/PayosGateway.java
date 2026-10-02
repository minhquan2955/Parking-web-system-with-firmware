package vn.parking.payments;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import vn.parking.common.*;
import vn.parking.model.RenewalOrder;

@Component
@ConditionalOnProperty(name = "parking.payment-mode", havingValue = "payos")
public class PayosGateway implements PaymentGateway {
  private final String clientId, apiKey, checksum, publicUrl, timezone;
  private final ObjectMapper json;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public PayosGateway(
      ObjectMapper json,
      @Value("${parking.payos-client-id}") String clientId,
      @Value("${parking.payos-api-key}") String apiKey,
      @Value("${parking.payos-checksum-key}") String checksum,
      @Value("${parking.public-url}") String publicUrl,
      @Value("${parking.payos-timezone}") String timezone) {
    if (clientId.isBlank() || apiKey.isBlank() || checksum.isBlank())
      throw new IllegalStateException(
          "payOS mode requires credentials; mock fallback is forbidden");
    if (!timezone.isBlank()) ZoneId.of(timezone);
    this.json = json;
    this.clientId = clientId;
    this.apiKey = apiKey;
    this.checksum = checksum;
    this.publicUrl = publicUrl;
    this.timezone = timezone;
  }

  public Link create(RenewalOrder o) {
    String url = publicUrl + "/payments/" + o.id;
    var data =
        Util.map(
            "amount",
            o.amountVnd.longValueExact(),
            "cancelUrl",
            url,
            "description",
            "P" + o.providerOrderCode,
            "orderCode",
            o.providerOrderCode,
            "returnUrl",
            url);
    data.put("signature", sign(json.valueToTree(data), checksum));
    data.put("expiredAt", o.expiresAt.getEpochSecond());
    JsonNode d = request("POST", "", json.valueToTree(data));
    if (d.path("orderCode").asLong() != o.providerOrderCode
        || !new BigDecimal(d.path("amount").asText("0")).equals(o.amountVnd))
      throw new Problem(503, "PROVIDER_MISMATCH", "Dữ liệu tạo QR cần tra soát");
    return new Link(text(d, "paymentLinkId"), text(d, "qrCode"), text(d, "checkoutUrl"));
  }

  public QueryResult query(RenewalOrder o) {
    JsonNode d = request("GET", "/" + o.providerOrderCode, null);
    if (d.path("orderCode").asLong() != o.providerOrderCode)
      throw new Problem(503, "PROVIDER_MISMATCH", "Mã đơn tra soát không khớp");
    List<VerifiedPayment> payments = new ArrayList<>();
    JsonNode transactions = d.path("transactions");
    if (transactions.isArray())
      for (JsonNode t : transactions) {
        payments.add(
            new VerifiedPayment(
                "payos",
                o.providerOrderCode,
                text(d, "id"),
                text(t, "reference"),
                amount(t),
                text(t, "currency") != null ? text(t, "currency") : text(d, "currency"),
                time(text(t, "transactionDateTime")),
                true,
                Util.sha(canonical(t))));
      }
    return new QueryResult(
        d.path("status").asText(),
        payments,
        new Link(text(d, "id"), text(d, "qrCode"), text(d, "checkoutUrl")));
  }

  public VerifiedPayment verify(JsonNode body) {
    JsonNode d = body.path("data");
    verifySignature(d, text(body, "signature"));
    return new VerifiedPayment(
        "payos",
        d.hasNonNull("orderCode") ? d.get("orderCode").asLong() : null,
        text(d, "paymentLinkId"),
        text(d, "reference"),
        amount(d),
        text(d, "currency"),
        time(text(d, "transactionDateTime")),
        "00".equals(text(d, "code")),
        Util.sha(canonical(d)));
  }

  private JsonNode request(String method, String path, JsonNode payload) {
    try {
      var builder =
          HttpRequest.newBuilder(
                  URI.create("https://api-merchant.payos.vn/v2/payment-requests" + path))
              .timeout(Duration.ofSeconds(10))
              .header("x-client-id", clientId)
              .header("x-api-key", apiKey)
              .header("Content-Type", "application/json");
      builder.method(
          method,
          payload == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(payload.toString()));
      var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new Problem(
            503, "PAYMENT_PROVIDER_UNAVAILABLE", "Nhà cung cấp chưa trả kết quả chắc chắn");
      JsonNode result = json.readTree(response.body());
      if (!"00".equals(text(result, "code")))
        throw new Problem(
            503, "PAYMENT_PROVIDER_UNAVAILABLE", "Cần tra soát kết quả từ nhà cung cấp");
      verifySignature(result.path("data"), text(result, "signature"));
      return result.path("data");
    } catch (Problem e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Problem(503, "PAYMENT_PROVIDER_UNAVAILABLE", "Tra soát bị gián đoạn");
    } catch (Exception e) {
      throw new Problem(503, "PAYMENT_PROVIDER_UNAVAILABLE", "Không kết nối được nhà cung cấp");
    }
  }

  private void verifySignature(JsonNode d, String signature) {
    if (!d.isObject()
        || signature == null
        || !signature.matches("[0-9a-fA-F]{64}")
        || !MessageDigest.isEqual(
            sign(d, checksum).getBytes(StandardCharsets.UTF_8),
            signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)))
      throw new Problem(400, "INVALID_SIGNATURE", "Chữ ký thanh toán không hợp lệ");
  }

  public static String sign(JsonNode d, String key) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(canonical(d).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String canonical(JsonNode d) {
    TreeMap<String, JsonNode> sorted = new TreeMap<>();
    d.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
    List<String> pairs = new ArrayList<>();
    sorted.forEach(
        (k, v) -> {
          String value;
          if (v.isNull() || v.asText().equals("null") || v.asText().equals("undefined")) value = "";
          else if (v.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode n : v) {
              TreeMap<String, JsonNode> item = new TreeMap<>();
              n.fields().forEachRemaining(e -> item.put(e.getKey(), e.getValue()));
              list.add(item);
            }
            value = Util.json(list);
          } else value = v.isValueNode() ? v.asText() : v.toString();
          pairs.add(k + "=" + value);
        });
    return String.join("&", pairs);
  }

  private Instant time(String s) {
    if (s == null) return null;
    try {
      return Instant.parse(s);
    } catch (Exception ignored) {
    }
    if (timezone.isBlank()) return null;
    try {
      return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"))
          .atZone(ZoneId.of(timezone))
          .toInstant();
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String text(JsonNode d, String key) {
    return d.hasNonNull(key) && !d.get(key).asText().isBlank() ? d.get(key).asText() : null;
  }

  private static BigDecimal amount(JsonNode d) {
    try {
      return d.hasNonNull("amount") ? new BigDecimal(d.get("amount").asText()) : null;
    } catch (Exception e) {
      return null;
    }
  }
}
