package local.ddosguard.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded in-flight LOGIN tracking. Keys identify connections, never whole IP addresses. */
public final class AdmissionGate<T> {
    public enum Result { ALLOW, RATE, CAPACITY, DUPLICATE }
    public record Lease<T>(String key, T connection, long deadline) { }
    private final Map<String, Lease<T>> pending = new LinkedHashMap<>();
    private final TokenBucket rate;
    private final int capacity;
    private final long timeout;

    public AdmissionGate(int burst, int capacity, long timeout, long now) {
        if (capacity < 1 || timeout <= 0) throw new IllegalArgumentException("pending limits");
        rate = new TokenBucket(burst, now);
        this.capacity = capacity;
        this.timeout = timeout;
    }

    public synchronized Result admit(String key, T connection, long now, double perSecond, boolean track) {
        if (track && pending.containsKey(key)) return Result.DUPLICATE;
        if (track && pending.size() >= capacity) return Result.CAPACITY;
        if (!rate.take(now, perSecond)) return Result.RATE;
        if (track) pending.put(key, new Lease<>(key, connection, now + timeout));
        return Result.ALLOW;
    }

    public synchronized void release(String key) { pending.remove(key); }

    public synchronized void removeDisconnected(java.util.function.Predicate<T> connected) {
        pending.values().removeIf(lease -> !connected.test(lease.connection()));
    }

    public synchronized List<Lease<T>> expire(long now) {
        List<Lease<T>> expired = new ArrayList<>();
        var iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            Lease<T> lease = iterator.next();
            if (now - lease.deadline() >= 0) {
                iterator.remove();
                expired.add(lease);
            }
        }
        return expired;
    }

    public synchronized int size() { return pending.size(); }
    public synchronized void clear() { pending.clear(); }
}
