package local.ddosguard.core;

/** Called once per elapsed sample. Recovery has hysteresis and does not refresh burst credit. */
public final class ProtectionMonitor {
    private final int enterSamples;
    private final long minimumHold;
    private final int recoverySamples;
    private int highSamples;
    private int cleanSamples;
    private long enteredAt;
    private volatile boolean protecting;

    public ProtectionMonitor(int enterSamples, long minimumHold, int recoverySamples) {
        this.enterSamples = enterSamples;
        this.minimumHold = minimumHold;
        this.recoverySamples = recoverySamples;
    }

    public boolean sample(boolean high, long now) {
        boolean previous = protecting;
        if (high) {
            cleanSamples = 0;
            if (highSamples < enterSamples) highSamples++;
            if (!protecting && highSamples >= enterSamples) {
                protecting = true;
                enteredAt = now;
            }
        } else {
            highSamples = 0;
            if (cleanSamples < recoverySamples) cleanSamples++;
            if (protecting && now - enteredAt >= minimumHold && cleanSamples >= recoverySamples)
                protecting = false;
        }
        return previous != protecting;
    }

    public boolean protecting() { return protecting; }
}
