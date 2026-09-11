package local.ddosguard;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import local.ddosguard.core.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuardSettingsTest {
    static YamlConfiguration defaults() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                GuardSettingsTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    }
    @Test void defaultsAreEnforcingAndContainTicketMessage() {
        GuardSettings settings = GuardSettings.parse(defaults());
        assertEquals(Mode.ENFORCE, settings.mode());
        assertTrue(settings.kickMessage().contains("디스코드에서 문의 티켓"));
        assertFalse(settings.kickMessage().contains("ddos 의심"));
    }
    @Test void invalidNumbersAreRejected() {
        for (Object value : new Object[]{-1, Double.NaN, "many", 0}) {
            var yaml = defaults(); yaml.set("login.per-second", value);
            assertThrows(IllegalArgumentException.class, () -> GuardSettings.parse(yaml));
        }
    }
    @Test void invalidViolationWindowIsRejected() {
        var yaml = defaults(); yaml.set("violations.minimum-span-seconds", 10);
        assertThrows(IllegalArgumentException.class, () -> GuardSettings.parse(yaml));
    }
    @Test void misspelledBooleanCannotSilentlyDisableProtection() {
        var yaml = defaults(); yaml.set("login.enabled", "ture");
        assertThrows(IllegalArgumentException.class, () -> GuardSettings.parse(yaml));
    }
    @Test void blankKickMessageIsRejected() {
        var yaml = defaults(); yaml.set("kick-message", " ");
        assertThrows(IllegalArgumentException.class, () -> GuardSettings.parse(yaml));
    }
    @Test void observeCountsWithoutRejectingAndOffDoesNotAccount() {
        var yaml = defaults(); yaml.set("mode", "observe"); yaml.set("login.burst", 1);
        var runtime = new GuardRuntime(GuardSettings.parse(yaml), () -> 0);
        runtime.admit("", null, false); runtime.admit("", null, false);
        assertEquals(1, runtime.loginLimited.sum()); assertFalse(runtime.enforcing());
        runtime.mode(Mode.OFF);
        assertEquals(AdmissionGate.Result.ALLOW, runtime.admit("", null, false));
        assertEquals(2, runtime.loginAttempts.sum());
    }
    @Test void recentHistoryCannotGrowWithoutBound() {
        var yaml = defaults(); yaml.set("diagnostics.recent-kicks", 3);
        var runtime = new GuardRuntime(GuardSettings.parse(yaml), () -> 0);
        for (int i = 0; i < 1000; i++) runtime.record("r" + i, null);
        assertEquals(3, runtime.recent().size()); assertTrue(runtime.recent().get(2).contains("r999"));
    }
    @Test void failedAdmissionsDoNotCreatePersistentBan() {
        var yaml = defaults(); yaml.set("login.burst", 1); yaml.set("login.per-second", 1); yaml.set("login.protected-per-second", 1);
        var clock = new AtomicLong();
        var runtime = new GuardRuntime(GuardSettings.parse(yaml), clock::get);
        assertEquals(AdmissionGate.Result.ALLOW, runtime.admit("same", null, false));
        assertEquals(AdmissionGate.Result.RATE, runtime.admit("same", null, false));
        clock.set(1_000_000_000L);
        assertEquals(AdmissionGate.Result.ALLOW, runtime.admit("same", null, false));
    }
}
