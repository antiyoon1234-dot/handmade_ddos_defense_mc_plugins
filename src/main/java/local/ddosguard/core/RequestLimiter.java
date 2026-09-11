package local.ddosguard.core;

/** One instance per session and request kind. No IP, account reputation, or punishment storage. */
public final class RequestLimiter {
    private final TokenBucket bucket;
    private final double rate;
    private final long window;
    private final long minimumSpan;
    private final int kickAfter;
    private long firstViolation;
    private int violations;

    public RequestLimiter(double rate, int burst, long window, long minimumSpan, int kickAfter, long now) {
        if (window <= 0 || minimumSpan <= 0 || minimumSpan >= window || kickAfter < 2)
            throw new IllegalArgumentException("violation policy");
        this.bucket = new TokenBucket(burst, now);
        this.rate = rate;
        this.window = window;
        this.minimumSpan = minimumSpan;
        this.kickAfter = kickAfter;
    }

    public synchronized Decision check(long now) {
        if (bucket.take(now, rate)) return Decision.ALLOW;
        if (violations == 0 || now - firstViolation >= window) {
            firstViolation = now;
            violations = 1;
        } else if (violations < Integer.MAX_VALUE) {
            violations++;
        }
        return violations >= kickAfter && now - firstViolation >= minimumSpan ? Decision.KICK : Decision.LIMIT;
    }
}
