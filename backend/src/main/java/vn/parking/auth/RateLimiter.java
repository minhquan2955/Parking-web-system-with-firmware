package vn.parking.auth;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {
  private final Clock clock;
  private final Map<String, Deque<Instant>> buckets = new HashMap<>();

  public RateLimiter(Clock clock) {
    this.clock = clock;
  }

  public synchronized long take(String key, int limit, int seconds) {
    Instant now = clock.instant(), cut = now.minusSeconds(seconds);
    buckets
        .entrySet()
        .removeIf(
            e -> e.getValue().isEmpty() || e.getValue().peekLast().isBefore(now.minusSeconds(900)));
    var q = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
    while (!q.isEmpty() && !q.peekFirst().isAfter(cut)) q.removeFirst();
    if (q.size() >= limit)
      return Math.max(1, Duration.between(now, q.peekFirst().plusSeconds(seconds)).toSeconds() + 1);
    q.addLast(now);
    return 0;
  }

  public synchronized long remaining(String key, int limit, int seconds) {
    var q = buckets.get(key);
    if (q == null) return 0;
    Instant now = clock.instant();
    while (!q.isEmpty() && !q.peekFirst().isAfter(now.minusSeconds(seconds))) q.removeFirst();
    return q.size() < limit
        ? 0
        : Math.max(1, Duration.between(now, q.peekFirst().plusSeconds(seconds)).toSeconds() + 1);
  }

  public synchronized void clear(String key) {
    buckets.remove(key);
  }
}
