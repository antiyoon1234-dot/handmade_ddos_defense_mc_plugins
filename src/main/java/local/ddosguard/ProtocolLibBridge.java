package local.ddosguard;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ArrayBlockingQueue;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import local.ddosguard.core.AdmissionGate;
import local.ddosguard.core.RequestKind;
import org.bukkit.entity.Player;

/** Optional public ProtocolLib integration. It runs after Minecraft frame decoding, not at L3/L4. */
public final class ProtocolLibBridge {
    private final DDoSGuardPlugin plugin;
    private final ProtocolManager manager;
    private final List<PacketAdapter> listeners = new ArrayList<>();
    private final AtomicBoolean warned = new AtomicBoolean();
    private record PendingClose(long deadline, Runnable action) { }
    private final ArrayBlockingQueue<PendingClose> pendingCloses = new ArrayBlockingQueue<>(512);
    private volatile boolean active;

    public ProtocolLibBridge(DDoSGuardPlugin plugin) {
        this.plugin = plugin;
        this.manager = ProtocolLibrary.getProtocolManager();
    }

    public void register() {
        try {
            PacketAdapter login = new PacketAdapter(PacketAdapter.params().plugin(plugin)
                    .listenerPriority(ListenerPriority.LOWEST).types(PacketType.Login.Client.START)
                    .options(ListenerOptions.ASYNC)) {
                @Override public void onPacketReceiving(PacketEvent event) { handleLogin(event); }
            };
            listeners.add(login);
            manager.addPacketListener(login);
            List<PacketType> types = new ArrayList<>();
            for (PacketType type : PacketType.Play.Client.getInstance())
                if (type.isSupported() && !types.contains(type)) types.add(type);
            PacketAdapter play = new PacketAdapter(PacketAdapter.params().plugin(plugin)
                    .listenerPriority(ListenerPriority.LOWEST).types(types.toArray(PacketType[]::new)).options(ListenerOptions.ASYNC)) {
                @Override public void onPacketReceiving(PacketEvent event) {
                    if (!active || !ProtocolLibBridge.this.plugin.running() || event.isCancelled() || event.isPlayerTemporary()) return;
                    Player player = event.getPlayer();
                    if (player == null) return;
                    ProtocolLibBridge.this.plugin.handleRequest(player, RequestKind.ALL_PACKETS);
                    if (event.getPacketType() == PacketType.Play.Client.CUSTOM_PAYLOAD
                            && ProtocolLibBridge.this.plugin.handleRequest(player, RequestKind.CUSTOM_PAYLOAD)) event.setCancelled(true);
                }
            };
            listeners.add(play);
            manager.addPacketListener(play);
            active = true;
        } catch (RuntimeException | LinkageError ex) {
            close();
            throw ex;
        }
    }

    private void handleLogin(PacketEvent event) {
        if (!active || !plugin.running() || event.isCancelled()) return;
        Player player = event.getPlayer();
        if (player == null || player.getAddress() == null) return;
        GuardRuntime state = plugin.runtime();
        AdmissionGate.Result result = state.admit(player.getAddress().toString(), player, true);
        if (result != AdmissionGate.Result.ALLOW && state.enforcing()) {
            event.setCancelled(true);
            state.loginDenied.increment();
            state.record("LOGIN_" + result, null);
            disconnect(player, state.settings.kickMessage());
        }
    }

    public void release(Player player) {
        if (player.getAddress() != null) plugin.runtime().admission.release(player.getAddress().toString());
    }

    public void expire() {
        GuardRuntime state = plugin.runtime();
        state.admission.removeDisconnected(player -> {
            try { return player.isOnline(); }
            catch (RuntimeException ex) { return true; } // Keep the bounded lease until timeout if the adapter cannot inspect it.
        });
        for (AdmissionGate.Lease<Player> lease : state.admission.expire(state.now())) {
            state.loginExpired.increment();
            if (state.enforcing()) {
                disconnect(lease.connection(), state.settings.kickMessage());
                state.record("LOGIN_TIMEOUT", null);
            }
        }
    }

    private void disconnect(Player player, String message) {
        AtomicBoolean closed = new AtomicBoolean();
        Runnable close = () -> { if (closed.compareAndSet(false, true)) closeConnection(player, message); };
        try {
            // TemporaryPlayer.kickPlayer only closes the transport on some Paper versions.
            // Write the LOGIN disconnect packet first, then close after the write action.
            PacketContainer packet = manager.createPacket(PacketType.Login.Server.DISCONNECT);
            packet.getChatComponents().write(0, WrappedChatComponent.fromText(message));
            NetworkMarker marker = new NetworkMarker(ConnectionSide.SERVER_SIDE, PacketType.Login.Server.DISCONNECT);
            marker.addPostListener(new PacketPostListener() {
                @Override public org.bukkit.plugin.Plugin getPlugin() { return plugin; }
                @Override public void onPostEvent(PacketEvent ignored) { close.run(); }
            });
            manager.sendServerPacket(player, packet, marker, false);
            // Bounded fallback if an adapter fails without delivering its post callback.
            if (!closed.get() && !pendingCloses.offer(new PendingClose(System.nanoTime() + 1_000_000_000L, close))) close.run();
        } catch (RuntimeException ex) {
            close.run();
            if (warned.compareAndSet(false, true)) plugin.getLogger().warning(
                    "초기 로그인 안내 패킷 전송 실패: " + ex.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("deprecation")
    private void closeConnection(Player player, String message) {
        try { player.kickPlayer(message); }
        catch (RuntimeException ex) {
            if (warned.compareAndSet(false, true)) plugin.getLogger().warning("초기 연결 종료 실패: " + ex.getClass().getSimpleName());
        }
    }

    public void flushCloses() {
        long now = System.nanoTime();
        PendingClose next;
        while ((next = pendingCloses.peek()) != null && now - next.deadline() >= 0) {
            PendingClose removed = pendingCloses.poll();
            if (removed != null) removed.action().run();
        }
    }

    public void close() {
        active = false;
        PendingClose pending;
        while ((pending = pendingCloses.poll()) != null) pending.action().run();
        for (PacketAdapter adapter : listeners) manager.removePacketListener(adapter);
        listeners.clear();
    }
}
