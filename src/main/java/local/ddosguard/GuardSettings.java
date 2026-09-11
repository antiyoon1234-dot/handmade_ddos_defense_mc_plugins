package local.ddosguard;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import local.ddosguard.core.Mode;
import local.ddosguard.core.RequestKind;
import org.bukkit.configuration.file.YamlConfiguration;

public record GuardSettings(Mode mode, String kickMessage, boolean nativeSpamMessage, boolean loginEnabled, double loginRate,
        int loginBurst, double protectedRate, int maxPending, long pendingTimeout,
        boolean protocolEnabled, long violationWindow, long violationSpan, int kickAfter,
        Map<RequestKind, Limit> limits, boolean pingEnabled, double pingRate, int pingBurst,
        boolean adaptiveEnabled, int enterAttempts, int enterRejected, int enterViolations,
        int enterSeconds, long minimumProtection, int recoverySeconds, int summarySeconds, int recentCount) {
    public record Limit(boolean enabled, double rate, int burst) { }

    public static GuardSettings parse(YamlConfiguration yaml) {
        String modeText = yaml.getString("mode", "enforce");
        Mode mode;
        try { mode = Mode.valueOf(modeText.toUpperCase(Locale.ROOT)); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("mode: enforce, observe, off 중 하나여야 합니다."); }
        String message = yaml.getString("kick-message", "");
        if (message.isBlank() || message.length() > 1000) throw new IllegalArgumentException("kick-message: 1~1000자 필요");
        int window = integer(yaml, "violations.window-seconds", 2, 120);
        int span = integer(yaml, "violations.minimum-span-seconds", 1, 119);
        if (span >= window) throw new IllegalArgumentException("minimum-span-seconds는 window-seconds보다 작아야 합니다.");
        Map<RequestKind, Limit> limits = new EnumMap<>(RequestKind.class);
        for (RequestKind kind : RequestKind.values()) {
            String base = "limits." + kind.path();
            limits.put(kind, new Limit(bool(yaml, base + ".enabled"), number(yaml, base + ".per-second", 0.1, 100000),
                    integer(yaml, base + ".burst", 1, 1000000)));
        }
        double loginRate = number(yaml, "login.per-second", 0.1, 100000);
        double protectedRate = number(yaml, "login.protected-per-second", 0.1, 100000);
        if (protectedRate > loginRate) throw new IllegalArgumentException("protected-per-second는 per-second 이하여야 합니다.");
        return new GuardSettings(mode, message, bool(yaml, "native-spam-kick-message"), bool(yaml, "login.enabled"), loginRate,
                integer(yaml, "login.burst", 1, 100000), protectedRate,
                integer(yaml, "login.max-pending", 1, 10000),
                seconds(integer(yaml, "login.pending-timeout-seconds", 10, 120)), bool(yaml, "protocol.enabled"),
                seconds(window), seconds(span), integer(yaml, "violations.kick-after", 2, 100000), Map.copyOf(limits),
                bool(yaml, "status-ping.enabled"), number(yaml, "status-ping.per-second", 0.1, 100000),
                integer(yaml, "status-ping.burst", 1, 1000000), bool(yaml, "adaptive.enabled"),
                integer(yaml, "adaptive.login-attempts-per-second", 1, 1000000),
                integer(yaml, "adaptive.rejected-logins-per-second", 1, 1000000),
                integer(yaml, "adaptive.request-violations-per-second", 1, 1000000),
                integer(yaml, "adaptive.consecutive-seconds", 1, 60),
                seconds(integer(yaml, "adaptive.minimum-protection-seconds", 1, 600)),
                integer(yaml, "adaptive.recovery-seconds", 1, 300),
                integer(yaml, "diagnostics.summary-seconds", 10, 3600),
                integer(yaml, "diagnostics.recent-kicks", 1, 1000));
    }

    private static long seconds(int value) { return TimeUnit.SECONDS.toNanos(value); }
    private static boolean bool(YamlConfiguration yaml, String path) {
        Object value = yaml.get(path);
        if (!(value instanceof Boolean result)) throw new IllegalArgumentException(path + ": true 또는 false 필요");
        return result;
    }
    private static double number(YamlConfiguration yaml, String path, double min, double max) {
        Object raw = yaml.get(path);
        if (!(raw instanceof Number n)) throw new IllegalArgumentException(path + ": 숫자 필요");
        double value = n.doubleValue();
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException(path + ": 범위 " + min + "~" + max);
        return value;
    }
    private static int integer(YamlConfiguration yaml, String path, int min, int max) {
        double value = number(yaml, path, min, max);
        if (value != Math.rint(value)) throw new IllegalArgumentException(path + ": 정수 필요");
        return (int) value;
    }
}
