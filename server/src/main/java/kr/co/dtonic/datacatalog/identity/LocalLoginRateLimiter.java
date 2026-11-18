package kr.co.dtonic.datacatalog.identity;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** Bounded single-process limiter for the explicitly local authentication adapter. */
public final class LocalLoginRateLimiter {
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final Clock clock;
    private final int usernameLimit;
    private final int addressLimit;
    private final long windowMillis;
    public LocalLoginRateLimiter(Clock clock, int usernameLimit, int addressLimit, int seconds) {
        if (usernameLimit < 1 || addressLimit < 1 || seconds < 1 || seconds > 3600) throw new IllegalArgumentException("Invalid login limit");
        this.clock = clock; this.usernameLimit = usernameLimit; this.addressLimit = addressLimit; this.windowMillis = seconds * 1000L;
    }
    /** Returns retry seconds, or zero after reserving one attempt in both limits. */
    public synchronized long reserve(String username, String remoteAddress) {
        long now = clock.millis();
        buckets.entrySet().removeIf(entry -> entry.getValue().until <= now);
        String userKey = "u:" + username;
        String addressKey = "a:" + remoteAddress;
        int newKeys = (buckets.containsKey(userKey) ? 0 : 1) + (buckets.containsKey(addressKey) ? 0 : 1);
        if (buckets.size() + newKeys > 10_000) {
            return Math.max(1, windowMillis / 1000);
        }
        Bucket user = buckets.computeIfAbsent(userKey, key -> new Bucket(now + windowMillis));
        Bucket address = buckets.computeIfAbsent(addressKey, key -> new Bucket(now + windowMillis));
        if (user.count >= usernameLimit || address.count >= addressLimit) {
            return Math.max(1, (Math.max(user.until, address.until) - now + 999) / 1000);
        }
        user.count++; address.count++;
        return 0;
    }
    private static final class Bucket { final long until; int count; Bucket(long until) { this.until = until; } }
}
