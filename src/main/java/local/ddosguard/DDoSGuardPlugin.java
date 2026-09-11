package local.ddosguard;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import local.ddosguard.core.*;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class DDoSGuardPlugin extends JavaPlugin {
    private record KickRequest(GuardRuntime runtime, GuardRuntime.Session session, String reason) { }
    private final ArrayBlockingQueue<KickRequest> kickQueue = new ArrayBlockingQueue<>(1024);
    private volatile GuardRuntime runtime;
    private volatile ProtocolLibBridge bridge;
    private volatile boolean running;
    private int ticks;
    private long lastSummary;

    @Override public void onEnable() {
        saveDefaultConfig();
        try { runtime = new GuardRuntime(readSettings(), System::nanoTime); }
        catch (Exception ex) {
            getLogger().severe("설정 오류로 DDoSGuard를 활성화하지 못했습니다: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        running = true;
        getServer().getPluginManager().registerEvents(new PaperListener(this), this);
        GuardCommand command = new GuardCommand(this);
        Objects.requireNonNull(getCommand("ddosguard")).setExecutor(command);
        Objects.requireNonNull(getCommand("ddosguard")).setTabCompleter(command);
        getServer().getOnlinePlayers().forEach(runtime::join);
        attachProtocol();
        lastSummary = runtime.now();
        getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        getLogger().info("DDoSGuard 활성화: " + runtime.mode() + ", " + scope());
    }

    private GuardSettings readSettings() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new File(getDataFolder(), "config.yml"));
        try (var stream = Objects.requireNonNull(getResource("config.yml"));
                var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            yaml.setDefaults(YamlConfiguration.loadConfiguration(reader));
        }
        return GuardSettings.parse(yaml);
    }

    public void reloadGuard() throws Exception {
        GuardSettings settings = readSettings(); // Validate before changing the live runtime.
        GuardRuntime replacement = new GuardRuntime(settings, System::nanoTime);
        getServer().getOnlinePlayers().forEach(replacement::join);
        ProtocolLibBridge oldBridge = bridge;
        bridge = null;
        if (oldBridge != null) oldBridge.close();
        GuardRuntime previous = runtime;
        runtime = replacement;
        kickQueue.clear();
        previous.close();
        attachProtocol();
        lastSummary = replacement.now();
    }

    private void attachProtocol() {
        if (!runtime.settings.protocolEnabled()) return;
        if (!getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            getLogger().warning("ProtocolLib가 없어 Paper 이벤트 보호만 활성화됩니다. 초기 로그인 및 PLAY 패킷 검사는 비활성입니다.");
            return;
        }
        try {
            ProtocolLibBridge next = new ProtocolLibBridge(this);
            next.register();
            bridge = next;
        } catch (LinkageError | RuntimeException ex) {
            getLogger().severe("ProtocolLib 연결 실패. Paper 이벤트 보호만 유지합니다: " + ex.getClass().getSimpleName());
        }
    }

    public GuardRuntime runtime() { return runtime; }
    public boolean running() { return running; }
    public ProtocolLibBridge bridge() { return bridge; }
    public String scope() { return bridge == null ? "Paper 이벤트 보호 (패킷 보호 비활성)" : "Paper + ProtocolLib 로그인/PLAY 패킷 보호"; }

    public boolean handleRequest(org.bukkit.entity.Player player, RequestKind kind) {
        GuardRuntime state = runtime;
        if (!running || state == null) return false;
        GuardRuntime.Session session = state.sessions.get(player.getUniqueId());
        if (session == null || session.player != player) return false;
        Decision decision = state.check(session, kind);
        if (decision == Decision.ALLOW || !state.enforcing()) return false;
        if (decision == Decision.KICK) queueKick(state, session, kind.name());
        // Never partially discard the generic packet stream (movement, keepalive, signed chat, etc.).
        boolean cancel = kind != RequestKind.ALL_PACKETS;
        if (cancel) state.requestCancelled.increment();
        return cancel;
    }

    private void queueKick(GuardRuntime state, GuardRuntime.Session session, String reason) {
        if (session.queued.compareAndSet(false, true) && !kickQueue.offer(new KickRequest(state, session, reason)))
            session.queued.set(false);
    }

    private void tick() {
        if (!running) return;
        ProtocolLibBridge closingBridge = bridge;
        if (closingBridge != null) closingBridge.flushCloses();
        for (int i = 0; i < 64; i++) {
            KickRequest request = kickQueue.poll();
            if (request == null) break;
            var session = request.session();
            try {
                GuardRuntime state = runtime;
                if (state == request.runtime() && state.enforcing()
                        && state.sessions.get(session.id) == session && session.player.isOnline()) {
                    session.player.kick(Component.text(state.settings.kickMessage()));
                    state.kicks.increment();
                    state.record(request.reason(), session.id);
                }
            } finally { session.queued.set(false); }
        }
        if (++ticks % 20 != 0) return;
        GuardRuntime state = runtime;
        ProtocolLibBridge currentBridge = bridge;
        if (currentBridge != null) currentBridge.expire();
        if (state.sample()) getLogger().info("보호 상태: " + (state.protection.protecting() ? "PROTECT" : "NORMAL"));
        if (state.now() - lastSummary >= state.settings.summarySeconds() * 1_000_000_000L) {
            lastSummary = state.now();
            getLogger().info("mode=" + state.mode() + " login=" + state.loginAttempts.sum()
                    + " denied=" + state.loginDenied.sum() + " requestLimited=" + state.requestLimited.sum()
                    + " kicks=" + state.kicks.sum() + " pending=" + state.admission.size());
        }
    }

    @Override public void onDisable() {
        running = false;
        getServer().getScheduler().cancelTasks(this);
        ProtocolLibBridge old = bridge;
        bridge = null;
        if (old != null) old.close();
        kickQueue.clear();
        if (runtime != null) runtime.close();
    }
}
