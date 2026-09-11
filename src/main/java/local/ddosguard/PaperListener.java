package local.ddosguard;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import local.ddosguard.core.AdmissionGate;
import local.ddosguard.core.RequestKind;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;

public final class PaperListener implements Listener {
    private final DDoSGuardPlugin plugin;
    public PaperListener(DDoSGuardPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void preLogin(AsyncPlayerPreLoginEvent event) {
        if (!plugin.running() || event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED || plugin.bridge() != null) return;
        GuardRuntime state = plugin.runtime();
        AdmissionGate.Result result = state.admit("", null, false);
        if (result != AdmissionGate.Result.ALLOW && state.enforcing()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(state.settings.kickMessage()));
            state.loginDenied.increment();
            state.record("LOGIN_" + result, event.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void login(PlayerLoginEvent event) {
        ProtocolLibBridge bridge = plugin.bridge();
        if (bridge != null) bridge.release(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        plugin.runtime().join(event.getPlayer());
        ProtocolLibBridge bridge = plugin.bridge();
        if (bridge != null) bridge.release(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { plugin.runtime().quit(event.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void nativeSpamKick(PlayerKickEvent event) {
        GuardRuntime state = plugin.runtime();
        if (!state.enforcing() || !state.settings.nativeSpamMessage()) return;
        if (event.getCause() == PlayerKickEvent.Cause.SPAM || event.getCause() == PlayerKickEvent.Cause.TOO_MANY_PENDING_CHATS) {
            event.reason(Component.text(state.settings.kickMessage()));
            state.nativeKicks.increment();
            state.record("NATIVE_" + event.getCause(), event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent event) {
        if (plugin.handleRequest(event.getPlayer(), RequestKind.COMMAND)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void tab(AsyncTabCompleteEvent event) {
        if (event.getSender() instanceof Player player && plugin.handleRequest(player, RequestKind.TAB_COMPLETE)) {
            event.setCancelled(true);
            event.setHandled(true);
            event.setCompletions(java.util.List.of());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void chat(AsyncChatEvent event) {
        if (plugin.handleRequest(event.getPlayer(), RequestKind.CHAT)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void book(PlayerEditBookEvent event) {
        if (plugin.handleRequest(event.getPlayer(), RequestKind.BOOK_EDIT)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void inventory(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && plugin.handleRequest(player, RequestKind.INVENTORY_CLICK))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) {
        if (plugin.handleRequest(event.getPlayer(), RequestKind.ITEM_DROP)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void ping(PaperServerListPingEvent event) {
        if (!plugin.runtime().allowPing()) event.setCancelled(true);
    }
}
