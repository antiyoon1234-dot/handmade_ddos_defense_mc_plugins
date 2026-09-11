package local.ddosguard.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtectionMonitorTest {
    @Test void isolatedSpikeDoesNotEnterProtection() {
        var monitor = new ProtectionMonitor(3, 30, 3);
        monitor.sample(true, 1); monitor.sample(false, 2); monitor.sample(true, 3);
        assertFalse(monitor.protecting());
    }
    @Test void requiresHoldAndCleanRecovery() {
        var monitor = new ProtectionMonitor(3, 30, 3);
        monitor.sample(true, 1); monitor.sample(true, 2);
        assertTrue(monitor.sample(true, 3));
        for (int i = 4; i < 33; i++) monitor.sample(false, i);
        assertTrue(monitor.protecting());
        assertTrue(monitor.sample(false, 33));
        assertFalse(monitor.protecting());
    }
    @Test void continuedAttackPreventsRecovery() {
        var monitor = new ProtectionMonitor(1, 30, 3);
        monitor.sample(true, 0);
        for (int i = 1; i < 1000; i++) monitor.sample(i % 3 == 0, i);
        assertTrue(monitor.protecting());
    }
}
