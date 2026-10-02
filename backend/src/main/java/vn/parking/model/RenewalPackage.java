package vn.parking.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "renewal_packages")
public class RenewalPackage extends BaseEntity {
  @Column(name = "code", nullable = false)
  public String code;

  @Column(name = "name", nullable = false)
  public String name;

  @Column(name = "duration_days", nullable = false)
  public Integer durationDays;

  @Column(name = "price_vnd", nullable = false, precision = 12, scale = 0)
  public BigDecimal priceVnd;

  @Column(name = "enabled", nullable = false)
  public Boolean enabled;
}
