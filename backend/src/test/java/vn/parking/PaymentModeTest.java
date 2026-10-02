package vn.parking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import vn.parking.payments.*;

class PaymentModeTest {
  @Configuration(proxyBeanMethods = false)
  @Import(MockPaymentController.class)
  static class Config {
    @Bean
    PaymentService service() {
      return mock(PaymentService.class);
    }
  }

  @Test
  void mockControllerExistsOnlyInDevAndMockMode() {
    new ApplicationContextRunner()
        .withUserConfiguration(Config.class)
        .withPropertyValues("spring.profiles.active=dev", "parking.payment-mode=mock")
        .run(c -> assertThat(c).hasSingleBean(MockPaymentController.class));
    new ApplicationContextRunner()
        .withUserConfiguration(Config.class)
        .withPropertyValues("spring.profiles.active=dev", "parking.payment-mode=payos")
        .run(c -> assertThat(c).doesNotHaveBean(MockPaymentController.class));
    new ApplicationContextRunner()
        .withUserConfiguration(Config.class)
        .withPropertyValues("spring.profiles.active=production", "parking.payment-mode=mock")
        .run(c -> assertThat(c).doesNotHaveBean(MockPaymentController.class));
  }
}
