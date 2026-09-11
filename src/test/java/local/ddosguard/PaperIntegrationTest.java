package local.ddosguard;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import java.io.File;
import java.net.InetAddress;
import java.util.UUID;
import local.ddosguard.core.Mode;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerKickEvent;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class PaperIntegrationTest {
    private ServerMock server;
    private DDoSGuardPlugin plugin;
    @BeforeEach void setup() { server = MockBukkit.mock(); plugin = MockBukkit.load(DDoSGuardPlugin.class); }
    @AfterEach void cleanup() { MockBukkit.unmock(); }
    @Test void loadsWithoutProtocolLibAndRegistersCommand() {
        assertTrue(plugin.isEnabled()); assertNull(plugin.bridge());
        assertNotNull(server.getPluginCommand("ddosguard"));
    }
    @Test void normalCommandPassesAndBurstIsCancelledWithoutInstantKick() {
        var player = server.addPlayer();
        var normal = new PlayerCommandPreprocessEvent(player, "/help");
        server.getPluginManager().callEvent(normal); assertFalse(normal.isCancelled());
        int cancelled = 0;
        for (int i = 0; i < 200; i++) {
            var event = new PlayerCommandPreprocessEvent(player, "/help");
            server.getPluginManager().callEvent(event);
            if (event.isCancelled()) cancelled++;
        }
        assertTrue(cancelled > 100);
        server.getScheduler().performTicks(2);
        assertTrue(player.isOnline());
    }
    @Test void observeDoesNotCancelExcessiveCommands() {
        var player = server.addPlayer(); plugin.runtime().mode(Mode.OBSERVE);
        for (int i = 0; i < 200; i++) {
            var event = new PlayerCommandPreprocessEvent(player, "/help");
            server.getPluginManager().callEvent(event); assertFalse(event.isCancelled());
        }
        assertTrue(plugin.runtime().requestLimited.sum() > 0); assertTrue(player.isOnline());
    }
    @Test void invalidReloadKeepsWorkingRuntime() throws Exception {
        var before = plugin.runtime();
        File file = new File(plugin.getDataFolder(), "config.yml");
        var yaml = YamlConfiguration.loadConfiguration(file); yaml.set("login.per-second", -1); yaml.save(file);
        assertThrows(IllegalArgumentException.class, plugin::reloadGuard);
        assertSame(before, plugin.runtime()); assertTrue(plugin.isEnabled());
    }
    @Test void reloadDiscardsOldSessionState() throws Exception {
        var player = server.addPlayer(); var before = plugin.runtime();
        plugin.reloadGuard();
        assertNotSame(before, plugin.runtime()); assertTrue(before.sessions.isEmpty());
        assertEquals(1, plugin.runtime().sessions.size()); assertTrue(player.isOnline());
    }
    @Test void loginRejectionUsesCommonMessageAndPreservesOtherRejections() throws Exception {
        File file = new File(plugin.getDataFolder(), "config.yml");
        var yaml = YamlConfiguration.loadConfiguration(file); yaml.set("login.burst", 1); yaml.save(file); plugin.reloadGuard();
        var listener = new PaperListener(plugin);
        var first = login(); listener.preLogin(first); assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, first.getLoginResult());
        var second = login(); listener.preLogin(second);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, second.getLoginResult());
        assertEquals(plugin.runtime().settings.kickMessage(), PlainTextComponentSerializer.plainText().serialize(second.kickMessage()));
        var alreadyDenied = login(); alreadyDenied.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, "maintenance");
        listener.preLogin(alreadyDenied); assertEquals("maintenance", alreadyDenied.getKickMessage());
    }
    @Test void unauthorizedCommandCannotDisableProtection() {
        var player = server.addPlayer(); player.setOp(false);
        server.dispatchCommand(player, "ddosguard mode off");
        assertEquals(Mode.ENFORCE, plugin.runtime().mode());
    }
    @Test void nativeSpamUsesCommonMessageButManualKickIsUntouched() {
        var player = server.addPlayer();
        var spam = new PlayerKickEvent(player, Component.text("Kicked for spamming"), Component.empty(), PlayerKickEvent.Cause.SPAM);
        new PaperListener(plugin).nativeSpamKick(spam);
        assertEquals(plugin.runtime().settings.kickMessage(), PlainTextComponentSerializer.plainText().serialize(spam.reason()));
        var manual = new PlayerKickEvent(player, Component.text("maintenance"), Component.empty(), PlayerKickEvent.Cause.KICK_COMMAND);
        new PaperListener(plugin).nativeSpamKick(manual);
        assertEquals("maintenance", PlainTextComponentSerializer.plainText().serialize(manual.reason()));
    }
    @Test void observeDoesNotRewriteNativeKickMessages() {
        plugin.runtime().mode(Mode.OBSERVE);
        var spam = new PlayerKickEvent(server.addPlayer(), Component.text("native"), Component.empty(), PlayerKickEvent.Cause.SPAM);
        new PaperListener(plugin).nativeSpamKick(spam);
        assertEquals("native", PlainTextComponentSerializer.plainText().serialize(spam.reason()));
    }
    private AsyncPlayerPreLoginEvent login() throws Exception {
        return new AsyncPlayerPreLoginEvent("Example", InetAddress.getLoopbackAddress(), UUID.randomUUID());
    }
}
