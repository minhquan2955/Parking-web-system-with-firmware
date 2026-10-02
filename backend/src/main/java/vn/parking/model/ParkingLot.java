package vn.parking.model;

import jakarta.persistence.*;

@Entity
@Table(name = "parking_lots")
public class ParkingLot extends BaseEntity {
  @Column(name = "code", nullable = false)
  public String code;

  @Column(name = "capacity", nullable = false)
  public Integer capacity;
}
