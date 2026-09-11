package local.ddosguard.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static local.ddosguard.core.AdmissionGate.Result.*;

class AdmissionGateTest {
    @Test void disconnectedClientsReleaseCapacityBeforeTimeout() {
        var gate = new AdmissionGate<String>(10, 2, 30, 0);
        gate.admit("a", "closed", 0, 1, true);
        gate.admit("b", "open", 0, 1, true);
        gate.removeDisconnected("open"::equals);
        assertEquals(1, gate.size());
        assertEquals(ALLOW, gate.admit("c", "new", 0, 1, true));
    }
    @Test void sameIpDifferentConnectionsHaveSeparateLeases() {
        var gate = new AdmissionGate<String>(100, 100, 30, 0);
        for (int port = 1; port <= 80; port++) assertEquals(ALLOW, gate.admit("127.0.0.1:" + port, "player", 0, 1, true));
        assertEquals(80, gate.size());
    }
    @Test void pendingMemoryIsBoundedAndRecovers() {
        var gate = new AdmissionGate<String>(10, 2, 30, 0);
        assertEquals(ALLOW, gate.admit("a", "a", 0, 1, true));
        assertEquals(ALLOW, gate.admit("b", "b", 0, 1, true));
        for (int i = 0; i < 10000; i++) assertEquals(CAPACITY, gate.admit("new" + i, "x", 0, 1, true));
        assertEquals(2, gate.size());
        assertEquals(2, gate.expire(30).size());
        assertEquals(ALLOW, gate.admit("c", "c", 30, 1, true));
    }
    @Test void completedLoginCannotExpireLater() {
        var gate = new AdmissionGate<String>(10, 10, 30, 0);
        gate.admit("a", "player", 0, 1, true);
        gate.release("a");
        assertTrue(gate.expire(100).isEmpty());
    }
    @Test void duplicateLoginStartDoesNotIncreasePending() {
        var gate = new AdmissionGate<String>(10, 10, 30, 0);
        gate.admit("a", "player", 0, 1, true);
        assertEquals(DUPLICATE, gate.admit("a", "player", 0, 1, true));
        assertEquals(1, gate.size());
    }
    @Test void fallbackDoesNotStoreClientIdentifiers() {
        var gate = new AdmissionGate<String>(2, 10, 30, 0);
        assertEquals(ALLOW, gate.admit("", null, 0, 1, false));
        assertEquals(ALLOW, gate.admit("", null, 0, 1, false));
        assertEquals(RATE, gate.admit("", null, 0, 1, false));
        assertEquals(0, gate.size());
        assertEquals(ALLOW, gate.admit("", null, 1_000_000_000L, 1, false));
    }
}
