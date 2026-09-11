package local.ddosguard;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import local.ddosguard.core.*;
import org.bukkit.entity.Player;

/** Network callbacks use only memory and bounded counters; no HTTP, disk, DNS, or Bukkit scheduling here. */
public final class GuardRuntime {
    public static final class Session {
        public final Player player;
        public final UUID id;
        public final AtomicBoolean queued = new AtomicBoolean();
        private final RequestLimiter[] limits = new RequestLimiter[RequestKind.values().length];
        Session(Player player, GuardSettings settings, long now) {
            this.player = player;
            this.id = player.getUniqueId();
            for (RequestKind kind : RequestKind.values()) {
                GuardSettings.Limit limit = settings.limits().get(kind);
                if (limit.enabled()) limits[kind.ordinal()] = new RequestLimiter(limit.rate(), limit.burst(),
                        settings.violationWindow(), settings.violationSpan(), settings.kickAfter(), now);
            }
        }
        Decision check(RequestKind kind, long now) {
            RequestLimiter limiter = limits[kind.ordinal()];
            return limiter == null ? Decision.ALLOW : limiter.check(now);
        }
    }

    public final GuardSettings settings;
    public final AdmissionGate<Player> admission;
    public final ProtectionMonitor protection;
    public final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    public final LongAdder loginAttempts = new LongAdder();
    public final LongAdder loginLimited = new LongAdder();
    public final LongAdder loginDenied = new LongAdder();
    public final LongAdder requestLimited = new LongAdder();
    public final LongAdder requestCancelled = new LongAdder();
    public final LongAdder kicks = new LongAdder();
    public final LongAdder nativeKicks = new LongAdder();
    public final LongAdder pingLimited = new LongAdder();
    public final LongAdder loginExpired = new LongAdder();
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private final LongSupplier clock;
    private final TokenBucket ping;
    private volatile Mode mode;
    private long lastAttempts, lastLimited, lastViolations, lastSample;

    public GuardRuntime(GuardSettings settings, LongSupplier clock) {
        this.settings = settings;
        this.clock = clock;
        this.mode = settings.mode();
        long now = clock.getAsLong();
        this.admission = new AdmissionGate<>(settings.loginBurst(), settings.maxPending(), settings.pendingTimeout(), now);
        this.ping = new TokenBucket(settings.pingBurst(), now);
        this.protection = new ProtectionMonitor(settings.enterSeconds(), settings.minimumProtection(), settings.recoverySeconds());
        this.lastSample = now;
    }

    public long now() { return clock.getAsLong(); }
    public Mode mode() { return mode; }
    public void mode(Mode next) { mode = next; }
    public boolean enforcing() { return mode == Mode.ENFORCE; }

    public AdmissionGate.Result admit(String key, Player connection, boolean track) {
        if (mode == Mode.OFF || !settings.loginEnabled()) return AdmissionGate.Result.ALLOW;
        loginAttempts.increment();
        double rate = protection.protecting() ? settings.protectedRate() : settings.loginRate();
        AdmissionGate.Result result = admission.admit(key, connection, now(), rate, track);
        if (result != AdmissionGate.Result.ALLOW) loginLimited.increment();
        return result;
    }

    public Session join(Player player) {
        Session session = new Session(player, settings, now());
        sessions.put(player.getUniqueId(), session);
        return session;
    }

    public void quit(Player player) {
        sessions.computeIfPresent(player.getUniqueId(), (id, session) -> session.player == player ? null : session);
    }

    public Decision check(Session session, RequestKind kind) {
        if (mode == Mode.OFF) return Decision.ALLOW;
        Decision result = session.check(kind, now());
        if (result != Decision.ALLOW) requestLimited.increment();
        return result;
    }

    public boolean allowPing() {
        if (mode == Mode.OFF || !settings.pingEnabled()) return true;
        boolean allowed = ping.take(now(), settings.pingRate());
        if (!allowed) pingLimited.increment();
        return allowed || !enforcing();
    }

    public boolean sample() {
        long now = now();
        long attempts = loginAttempts.sum(), limited = loginLimited.sum(), violations = requestLimited.sum();
        double elapsedSeconds = Math.max(1, (now - lastSample) / 1_000_000_000.0);
        boolean high = settings.adaptiveEnabled() && mode != Mode.OFF &&
                (((attempts - lastAttempts) / elapsedSeconds >= settings.enterAttempts()
                        && (limited - lastLimited) / elapsedSeconds >= settings.enterRejected())
                || (violations - lastViolations) / elapsedSeconds >= settings.enterViolations());
        lastAttempts = attempts; lastLimited = limited; lastViolations = violations; lastSample = now;
        return protection.sample(high, now);
    }

    public synchronized void record(String reason, UUID id) {
        if (recent.size() == settings.recentCount()) recent.removeFirst();
        recent.addLast(Instant.now() + " | " + reason + " | " + (id == null ? "login" : id));
    }

    public synchronized List<String> recent() { return List.copyOf(recent); }

    public void close() { sessions.clear(); admission.clear(); }
}
