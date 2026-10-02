package vn.parking.devices;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/device")
public class DeviceController {
  private final DeviceService service;

  public DeviceController(DeviceService service) {
    this.service = service;
  }

  @PostMapping("/heartbeats")
  Object heartbeat(
      @RequestHeader(value = "X-Device-Id", defaultValue = "") String id,
      @RequestHeader(value = "X-Device-Key", defaultValue = "") String key,
      @RequestBody DeviceService.Heartbeat h,
      HttpServletRequest req) {
    return service.heartbeat(id, key, req.getRemoteAddr(), h);
  }

  @PostMapping("/access-events")
  Object scan(
      @RequestHeader(value = "X-Device-Id", defaultValue = "") String id,
      @RequestHeader(value = "X-Device-Key", defaultValue = "") String key,
      @RequestBody DeviceService.Scan s,
      HttpServletRequest req) {
    return service.scan(id, key, req.getRemoteAddr(), s);
  }
}
