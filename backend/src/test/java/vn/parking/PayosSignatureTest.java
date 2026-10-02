package vn.parking;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.parking.common.Problem;
import vn.parking.payments.PayosGateway;

/** Published provider signature vector, not a live payment or a credential. */
class PayosSignatureTest {
  @Test
  void signatureMatchesProviderExampleAndTamperingFails() throws Exception {
    var json = new ObjectMapper();
    String key = "1a54716c8f0efb2744fb28b6e38b25da7f67a925d98bc1c18bd8faaecadd7675";
    var data =
        json.readTree(
            """
            {"orderCode":123,"amount":3000,"description":"VQRIO123","accountNumber":"12345678","reference":"TF230204212323","transactionDateTime":"2023-02-04 18:25:00","currency":"VND","paymentLinkId":"124c33293c43417ab7879e14c8d9eb18","code":"00","desc":"Thành công","counterAccountBankId":"","counterAccountBankName":"","counterAccountName":"","counterAccountNumber":"","virtualAccountName":"","virtualAccountNumber":""}
            """);
    assertThat(PayosGateway.sign(data, key))
        .isEqualTo("412e915d2871504ed31be63c8f62a149a4410d34c4c42affc9006ef9917eaa03");
    var adapter =
        new PayosGateway(json, "test-client", "test-api", key, "http://localhost:3000", "");
    var body = json.createObjectNode().set("data", data);
    ((com.fasterxml.jackson.databind.node.ObjectNode) body)
        .put("signature", PayosGateway.sign(data, key));
    assertThat(adapter.verify(body).paidAt()).isNull();
    ((com.fasterxml.jackson.databind.node.ObjectNode) data).put("amount", 1);
    assertThatThrownBy(() -> adapter.verify(body)).isInstanceOf(Problem.class);
  }
}
