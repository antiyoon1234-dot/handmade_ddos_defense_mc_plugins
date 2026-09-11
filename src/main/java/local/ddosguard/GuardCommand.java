package local.ddosguard;

import java.util.List;
import java.util.Locale;
import local.ddosguard.core.Mode;
import org.bukkit.command.*;

public final class GuardCommand implements CommandExecutor, TabCompleter {
    private final DDoSGuardPlugin plugin;
    public GuardCommand(DDoSGuardPlugin plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("ddosguard.admin")) { sender.sendMessage("이 명령어를 사용할 권한이 없습니다."); return true; }
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        GuardRuntime state = plugin.runtime();
        switch (action) {
            case "status" -> {
                sender.sendMessage("DDoSGuard " + plugin.getDescription().getVersion() + " | " + state.mode() + " | " + (state.protection.protecting() ? "PROTECT" : "NORMAL"));
                sender.sendMessage(plugin.scope());
                sender.sendMessage("접속 시도 " + state.loginAttempts.sum() + " | 제한 대상 " + state.loginLimited.sum() + " | 실제 거절 " + state.loginDenied.sum());
                sender.sendMessage("요청 초과 " + state.requestLimited.sum() + " | 취소 " + state.requestCancelled.sum() + " | 추방 요청 " + state.kicks.sum());
                sender.sendMessage("Paper 기본 스팸 추방 안내 " + state.nativeKicks.sum());
                sender.sendMessage("추적 중인 유저 " + state.sessions.size() + " | 진행 중 로그인 " + state.admission.size() + " | 만료 " + state.loginExpired.sum());
                sender.sendMessage("초과 서버목록 핑 " + state.pingLimited.sum() + " | L3/L4 방어: 외부망 필요");
            }
            case "reload" -> {
                try { plugin.reloadGuard(); sender.sendMessage("설정을 다시 불러왔습니다. 현재 모드: " + plugin.runtime().mode()); }
                catch (Exception ex) { sender.sendMessage("설정 오류: " + ex.getMessage() + " 기존 설정을 유지합니다."); }
            }
            case "mode" -> {
                if (args.length != 2) { sender.sendMessage("/" + label + " mode <enforce|observe|off>"); return true; }
                try {
                    Mode next = Mode.valueOf(args[1].toUpperCase(Locale.ROOT));
                    state.mode(next);
                    sender.sendMessage("현재 실행 모드: " + next + ". 재시작/reload 시 config.yml의 mode가 적용됩니다.");
                } catch (IllegalArgumentException ex) { sender.sendMessage("모드: enforce, observe, off"); }
            }
            case "recent" -> {
                List<String> recent = state.recent();
                if (recent.isEmpty()) sender.sendMessage("기록된 접속 거절/추방 요청이 없습니다.");
                else recent.subList(Math.max(0, recent.size() - 10), recent.size()).forEach(sender::sendMessage);
            }
            default -> sender.sendMessage("/" + label + " <status|reload|mode|recent>");
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("ddosguard.admin")) return List.of();
        List<String> options = args.length == 1 ? List.of("status", "reload", "mode", "recent")
                : args.length == 2 && args[0].equalsIgnoreCase("mode") ? List.of("enforce", "observe", "off") : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(prefix)).toList();
    }
}
