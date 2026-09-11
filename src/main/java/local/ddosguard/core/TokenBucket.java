package local.ddosguard.core;

/** Monotonic time and bounded credit; failed requests cannot borrow future tokens. */
public final class TokenBucket {
    private final double capacity;
    private double tokens;
    private long last;

    public TokenBucket(double capacity, long now) {
        if (!Double.isFinite(capacity) || capacity <= 0) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
        this.tokens = capacity;
        this.last = now;
    }

    public synchronized boolean take(long now, double rate) {
        if (!Double.isFinite(rate) || rate <= 0) throw new IllegalArgumentException("rate");
        long elapsed = now - last;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed / 1_000_000_000.0 * rate);
            last = now;
        }
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }
}
