package local.ddosguard.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestLimiterTest {
    private RequestLimiter limiter() { return new RequestLimiter(1, 5, 10_000_000_000L, 3_000_000_000L, 30, 0); }
    @Test void normalTrafficDoesNotAccumulateViolations() {
        var limiter = limiter();
        for (long second = 0; second < 3600; second++) assertEquals(Decision.ALLOW, limiter.check(second * 1_000_000_000L));
    }
    @Test void isolatedBurstNeverImmediatelyKicks() {
        var limiter = limiter();
        for (int i = 0; i < 100000; i++) assertNotEquals(Decision.KICK, limiter.check(0));
    }
    @Test void sustainedExcessKicksAfterGrace() {
        var limiter = limiter();
        for (int i = 0; i < 100; i++) limiter.check(0);
        assertEquals(Decision.ALLOW, limiter.check(3_000_000_000L));
        limiter.check(3_000_000_000L); limiter.check(3_000_000_000L);
        assertEquals(Decision.KICK, limiter.check(3_000_000_000L));
    }
    @Test void oldViolationWindowExpires() {
        var limiter = limiter();
        for (int i = 0; i < 100; i++) limiter.check(0);
        for (int i = 0; i < 100; i++) assertNotEquals(Decision.KICK, limiter.check(11_000_000_000L));
    }
    @Test void separateSessionsCannotPenalizeEachOther() {
        var attacker = limiter(); var normal = limiter();
        for (int i = 0; i < 100000; i++) attacker.check(0);
        assertEquals(Decision.ALLOW, normal.check(0));
    }
}
