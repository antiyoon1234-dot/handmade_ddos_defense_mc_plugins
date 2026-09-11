package local.ddosguard.core;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TokenBucketTest {
    @Test void burstRefillsWithoutDebt() {
        var bucket = new TokenBucket(2, 0);
        assertTrue(bucket.take(0, 2)); assertTrue(bucket.take(0, 2));
        for (int i = 0; i < 10000; i++) assertFalse(bucket.take(0, 2));
        assertTrue(bucket.take(500_000_000, 2));
        assertFalse(bucket.take(500_000_000, 2));
    }
    @Test void idleCreditCannotExceedBurst() {
        var bucket = new TokenBucket(3, 0);
        assertTrue(bucket.take(1_000_000_000_000L, 1));
        assertTrue(bucket.take(1_000_000_000_000L, 1));
        assertTrue(bucket.take(1_000_000_000_000L, 1));
        assertFalse(bucket.take(1_000_000_000_000L, 1));
    }
    @Test void clockRegressionCannotMintTokens() {
        var bucket = new TokenBucket(1, 100);
        assertTrue(bucket.take(100, 1));
        assertFalse(bucket.take(50, 1));
        assertFalse(bucket.take(100, 1));
    }
    @Test void concurrentRequestsCannotOverspend() throws Exception {
        var bucket = new TokenBucket(100, 0);
        var count = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(8);
        try {
            var tasks = new java.util.ArrayList<Callable<Void>>();
            for (int t = 0; t < 8; t++) tasks.add(() -> {
                for (int i = 0; i < 1000; i++) if (bucket.take(0, 1)) count.incrementAndGet();
                return null;
            });
            for (var result : pool.invokeAll(tasks)) result.get();
            assertEquals(100, count.get());
        } finally { pool.shutdownNow(); }
    }
    @Test void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0, 0));
    }
}
